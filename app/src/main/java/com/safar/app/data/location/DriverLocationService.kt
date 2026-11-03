package com.safar.app.data.location

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.safar.app.R
import com.safar.app.di.AppContainer
import com.safar.app.domain.model.Driver
import com.safar.app.domain.model.DriverLocationFix
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

class DriverLocationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locationProvider by lazy { AndroidLocationProvider(applicationContext) }
    private lateinit var connectivityManager: ConnectivityManager
    private var publishingJob: Job? = null
    private var locationStreamJob: Job? = null
    private var headingJob: Job? = null
    @Volatile private var latestHeadingDegrees: Float? = null
    @Volatile private var lastPublishedHeadingDegrees: Float? = null
    @Volatile private var lastHeadingPublishRequestAtMs = 0L
    @Volatile private var lastHeadingPublishedAtMs = 0L
    @Volatile private var lastAcceptedLocation: LocationSample? = null
    private val locationPublishMutex = Mutex()
    private var lastPublishedLocationTimestamp = 0L
    @Volatile private var lastSuccessfulPublishAtMs = 0L
    private val driverSnapshot = AtomicReference<Driver?>(null)
    @Volatile private var driverSnapshotLoadedAt = 0L
    @Volatile private var networkAvailable = false
    @Volatile private var expectedDriverId: String? = null
    @Volatile private var expectedRouteId: String? = null
    private val identityPreferences by lazy { getSharedPreferences(IDENTITY_PREFS, Context.MODE_PRIVATE) }
    private val pendingLocationQueue by lazy {
        PendingDriverLocationQueue(
            File(noBackupFilesDir, PENDING_LOCATION_FILE),
            onCorrupt = { error -> android.util.Log.e(TAG, "Discarded a corrupt pending GPS queue", error) },
        )
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networkAvailable = true
            updateNotification("Геопозиция передаётся пассажирам")
            scope.launch { publishLastAcceptedLocation() }
        }
        override fun onLost(network: Network) {
            networkAvailable = isNetworkAvailable()
            if (!networkAvailable) updateNotification("Нет интернета — отправка приостановлена")
        }
    }

    override fun onCreate() {
        super.onCreate()
        restoreExpectedDriverIdentity()
        createNotificationChannel()

        // Android gives a foreground service started with startForegroundService()
        // only a short window to promote itself. Do this before any network or
        // location setup so registration/callback work can never consume that
        // window.
        if (!hasLocationPermission()) {
            stopSelf()
            return
        }
        runCatching {
            startForegroundNow("Запуск геолокации водителя")
        }.onFailure { error ->
            android.util.Log.e(TAG, "Could not promote DriverLocationService to foreground", error)
            stopSelf()
        }

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkAvailable = isNetworkAvailable()
        runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopPublishing()
            runCatching { pendingLocationQueue.clear() }
                .onFailure { android.util.Log.e(TAG, "Could not clear pending GPS fixes on sign out", it) }
            identityPreferences.edit().clear().apply()
            expectedDriverId = null
            expectedRouteId = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        updateExpectedDriverIdentity(intent)
        if (!hasLocationPermission()) {
            updateNotification("Разрешение на геолокацию не предоставлено")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForegroundNow(
                if (networkAvailable) "Геопозиция передаётся пассажирам"
                else "Нет интернета — отправка приостановлена"
            )
        } catch (_: SecurityException) {
            stopSelf()
            return START_NOT_STICKY
        }
        startPublishingIfNeeded()
        return START_STICKY
    }

    private fun startForegroundNow(content: String) {
        val notification = buildNotification(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startPublishingIfNeeded() {
        // Always restore the compass collector first. The GPS publisher may already
        // be running after Android restarts the service or the sensor collector ends.

        // Capture phone orientation independently of GPS, including while stationary.
        if (headingJob?.isActive != true) {
            headingJob = scope.launch {
                // A sensor stream can end normally if Android temporarily unregisters
                // it. Recreate it instead of permanently freezing the vehicle rotation.
                while (currentCoroutineContext().isActive) {
                    try {
                        locationProvider.headingDegrees().collectLatest { degrees ->
                            if (degrees.isFinite()) {
                                val normalized = ((degrees % 360f) + 360f) % 360f
                                latestHeadingDegrees = normalized

                                // GPS timestamps often stop changing while the vehicle is parked.
                                // Publish meaningful compass turns independently of new GPS fixes.
                                val lastPublished = lastPublishedHeadingDegrees
                                val now = System.currentTimeMillis()
                                val headingChanged = lastPublished == null ||
                                    angularDifferenceDegrees(lastPublished, normalized) >= HEADING_CHANGE_THRESHOLD_DEGREES
                                if (networkAvailable && headingChanged &&
                                    now - lastHeadingPublishRequestAtMs >= HEADING_PUBLISH_MIN_INTERVAL_MS
                                ) {
                                    lastHeadingPublishRequestAtMs = now
                                    publishLastAcceptedLocation()
                                }
                            }
                        }
                        android.util.Log.w(TAG, "Compass stream ended; retrying")
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        android.util.Log.w(TAG, "Compass stream failed; retrying", error)
                    }
                    delay(1_000L)
                }
            }
        }

        // Do not duplicate GPS loops, but never let this guard prevent the
        // heading collector above from being restored.
        if (publishingJob?.isActive == true) return

        // Primary channel: continuous Android location updates. This is the same
        // stream that already drives the blue dot on the driver's map.
        locationStreamJob = scope.launch {
            while (currentCoroutineContext().isActive && hasLocationPermission()) {
                try {
                    locationProvider.locationSamples().collect { sample ->
                        if (!hasLocationPermission()) return@collect
                        publishLocationSample(sample)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    android.util.Log.e(TAG, "Location stream failed; restarting", error)
                    delay(1_000L)
                }
            }
        }

        // Heartbeat the last stabilized coordinate. Requesting another one-shot
        // fix here mixed fresh fused updates with cached GPS/network fixes and
        // let source changes inflate the session distance while standing still.
        publishingJob = scope.launch {
            while (currentCoroutineContext().isActive && hasLocationPermission()) {
                try {
                    if (networkAvailable) {
                        publishLastAcceptedLocation()
                        if (lastAcceptedLocation != null) {
                            updateNotification("Геопозиция передаётся пассажирам")
                        }
                    } else {
                        updateNotification("Нет интернета — отправка приостановлена")
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: SecurityException) {
                    updateNotification("Нет доступа к геолокации")
                    break
                } catch (error: Exception) {
                    android.util.Log.e(TAG, "Driver GPS cycle failed", error)
                    updateNotification("Ошибка GPS — повторяю автоматически")
                }
                delay(PUBLISH_INTERVAL_MS)
            }
            if (!hasLocationPermission()) stopSelf()
        }
    }

    private suspend fun publishLocationSample(sample: LocationSample) {
        val timestampEpochMs = sample.timestampEpochMs
        if (timestampEpochMs <= 0L) return

        // Never refresh a location from an excessively old cached GPS fix.
        // The database cleanup removes location state after four minutes; allow
        // a shorter reuse window so stationary devices can heartbeat without
        // keeping genuinely stale coordinates alive.
        val now = System.currentTimeMillis()
        val fixAgeMs = now - timestampEpochMs
        if (fixAgeMs < -30_000L || fixAgeMs > MAX_REUSABLE_FIX_AGE_MS) {
            android.util.Log.w(TAG, "Skipping stale GPS fix: ageMs=$fixAgeMs")
            return
        }

        // Serialize location writes, offline queue flushes, compass updates, and
        // heartbeats so the server always receives monotonically ordered fixes.
        locationPublishMutex.withLock {
            lastAcceptedLocation = sample
            if (!networkAvailable) {
                enqueuePendingFix(sample)
                return
            }

            try {
                val driver = requireVerifiedDriver()
                flushPendingFixes(driver)
                publishAcceptedSample(driver, sample)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                enqueuePendingFix(sample)
                android.util.Log.w(TAG, "Could not publish GPS fix; queued it for retry", error)
                updateNotification("Нет соединения с сервером — GPS сохраняется локально")
            }
        }
    }

    private suspend fun publishLastAcceptedLocation() {
        if (!networkAvailable || !hasLocationPermission()) return
        locationPublishMutex.withLock {
            try {
                val driver = requireVerifiedDriver()
                flushPendingFixes(driver)
                val latest = lastAcceptedLocation ?: return
                val now = System.currentTimeMillis()
                val fixAgeMs = now - latest.timestampEpochMs
                // Do not reactivate an old point after an outage. A fresh GPS
                // callback will make the vehicle active again immediately.
                if (fixAgeMs < -30_000L || fixAgeMs > MAX_HEARTBEAT_FIX_AGE_MS) return
                publishAcceptedSample(driver, latest)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w(TAG, "Could not restore driver location publishing", error)
            }
        }
    }

    private suspend fun publishAcceptedSample(driver: Driver, sample: LocationSample) {
        val publishNow = System.currentTimeMillis()
        val isNewGpsFix = sample.timestampEpochMs > lastPublishedLocationTimestamp
        val heading = latestHeadingDegrees
        val headingChanged = heading != null && (
            lastPublishedHeadingDegrees == null ||
                angularDifferenceDegrees(lastPublishedHeadingDegrees!!, heading) >= HEADING_CHANGE_THRESHOLD_DEGREES
            )
        val canPublishHeading = headingChanged &&
            publishNow - lastHeadingPublishedAtMs >= HEADING_PUBLISH_MIN_INTERVAL_MS
        val heartbeatDue = !isNewGpsFix &&
            publishNow - lastSuccessfulPublishAtMs >= PUBLISH_INTERVAL_MS

        if (!isNewGpsFix && !canPublishHeading && !heartbeatDue) return

        val publishTimestamp = if (isNewGpsFix) sample.timestampEpochMs else publishNow
        AppContainer.driverLocationRepository.submitLocation(
            latitude = sample.point.latitude,
            longitude = sample.point.longitude,
            recordedAtIso = Instant.ofEpochMilli(publishTimestamp).toString(),
            headingDegrees = heading,
        )

        lastSuccessfulPublishAtMs = System.currentTimeMillis()
        if (isNewGpsFix) lastPublishedLocationTimestamp = sample.timestampEpochMs
        if (heading != null) {
            lastPublishedHeadingDegrees = heading
            lastHeadingPublishedAtMs = publishNow
        }
        android.util.Log.d(
            TAG,
            "Published driver fix: newGps=$isNewGpsFix heartbeat=$heartbeatDue heading=$heading " +
                "lat=${sample.point.latitude} lon=${sample.point.longitude}",
        )
        updateNotification("Геопозиция передаётся пассажирам")
    }

    private suspend fun requireVerifiedDriver(): Driver {
        val now = System.currentTimeMillis()
        val cached = driverSnapshot.get()
        val identityMismatch = cached != null && (
            expectedDriverId?.let { it != cached.userId } == true ||
                expectedRouteId?.let { it != cached.routeId } == true
            )
        if (cached == null || identityMismatch || now - driverSnapshotLoadedAt >= DRIVER_REFRESH_MS) {
            val loaded = AppContainer.driverRepository.getDriver()
                ?: error("Driver profile not found")
            driverSnapshot.set(loaded)
            driverSnapshotLoadedAt = now
        }

        val driver = driverSnapshot.get() ?: error("Driver profile not found")
        check(driver.verified) { "Driver is not verified" }
        val routeId = driver.routeId ?: error("Driver route is not assigned yet")
        check(expectedDriverId == null || expectedDriverId == driver.userId) {
            "Authenticated user changed while the GPS service was running"
        }
        check(expectedRouteId == null || expectedRouteId == routeId) {
            "Driver route changed while the GPS service was running"
        }
        if (expectedDriverId == null || expectedRouteId == null) {
            expectedDriverId = driver.userId
            expectedRouteId = routeId
            identityPreferences.edit()
                .putString(EXTRA_DRIVER_ID, driver.userId)
                .putString(EXTRA_ROUTE_ID, routeId)
                .apply()
        }
        return driver
    }

    private fun enqueuePendingFix(sample: LocationSample) {
        val driverId = expectedDriverId ?: driverSnapshot.get()?.userId
        val routeId = expectedRouteId ?: driverSnapshot.get()?.routeId
        if (driverId.isNullOrBlank() || routeId.isNullOrBlank()) {
            android.util.Log.w(TAG, "Cannot persist an offline GPS fix before driver identity and route are known")
            return
        }
        runCatching {
            pendingLocationQueue.enqueue(
                driverId = driverId,
                routeId = routeId,
                sample = sample,
                headingDegrees = latestHeadingDegrees,
                nowEpochMs = System.currentTimeMillis(),
            )
        }.onFailure { error ->
            android.util.Log.e(TAG, "Could not persist pending driver GPS fix", error)
        }
    }

    private suspend fun flushPendingFixes(driver: Driver) {
        val routeId = requireNotNull(driver.routeId)
        val fixes = pendingLocationQueue.pendingFor(driver.userId, routeId, System.currentTimeMillis())
        if (fixes.isEmpty()) return

        val batch = fixes.take(MAX_BATCHED_LOCATION_FIXES)
        AppContainer.driverLocationRepository.submitLocations(
            batch.map { fix ->
                DriverLocationFix(
                    latitude = fix.sample.point.latitude,
                    longitude = fix.sample.point.longitude,
                    timestampEpochMs = fix.sample.timestampEpochMs,
                    headingDegrees = fix.headingDegrees,
                )
            },
        )
        val lastFix = batch.last()
        pendingLocationQueue.acknowledgeThrough(driver.userId, routeId, lastFix.sample.timestampEpochMs)
        lastPublishedLocationTimestamp = maxOf(lastPublishedLocationTimestamp, lastFix.sample.timestampEpochMs)
        lastSuccessfulPublishAtMs = System.currentTimeMillis()
        lastPublishedHeadingDegrees = lastFix.headingDegrees
        lastHeadingPublishedAtMs = lastSuccessfulPublishAtMs
        android.util.Log.i(TAG, "Uploaded ${batch.size} queued GPS fixes for route $routeId")
    }

    private fun restoreExpectedDriverIdentity() {
        expectedDriverId = identityPreferences.getString(EXTRA_DRIVER_ID, null)
        expectedRouteId = identityPreferences.getString(EXTRA_ROUTE_ID, null)
    }

    private fun updateExpectedDriverIdentity(intent: Intent?) {
        val driverId = intent?.getStringExtra(EXTRA_DRIVER_ID)?.takeIf(String::isNotBlank) ?: return
        val routeId = intent.getStringExtra(EXTRA_ROUTE_ID)?.takeIf(String::isNotBlank) ?: return
        val changed = expectedDriverId != driverId || expectedRouteId != routeId
        expectedDriverId = driverId
        expectedRouteId = routeId
        identityPreferences.edit()
            .putString(EXTRA_DRIVER_ID, driverId)
            .putString(EXTRA_ROUTE_ID, routeId)
            .apply()
        if (changed) {
            driverSnapshot.set(null)
            driverSnapshotLoadedAt = 0L
            lastPublishedLocationTimestamp = 0L
            lastAcceptedLocation = null
        }
    }

    private fun angularDifferenceDegrees(a: Float, b: Float): Float {
        val difference = kotlin.math.abs(a - b) % 360f
        return kotlin.math.min(difference, 360f - difference)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun buildNotification(content: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Геолокация водителя", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun stopPublishing() {
        publishingJob?.cancel()
        publishingJob = null
        locationStreamJob?.cancel()
        locationStreamJob = null
        headingJob?.cancel()
        headingJob = null
        latestHeadingDegrees = null
    }

    override fun onDestroy() {
        stopPublishing()
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "driver_location"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.safar.app.STOP_DRIVER_LOCATION"
        private const val EXTRA_DRIVER_ID = "com.safar.app.DRIVER_LOCATION_DRIVER_ID"
        private const val EXTRA_ROUTE_ID = "com.safar.app.DRIVER_LOCATION_ROUTE_ID"
        private const val IDENTITY_PREFS = "driver_location_identity"
        private const val PENDING_LOCATION_FILE = "pending_driver_gps.json"
        private const val DRIVER_REFRESH_MS = 30_000L
        private const val PUBLISH_INTERVAL_MS = 10_000L
        private const val HEADING_CHANGE_THRESHOLD_DEGREES = 4f
        private const val HEADING_PUBLISH_MIN_INTERVAL_MS = 1_000L
        private const val MAX_REUSABLE_FIX_AGE_MS = 3 * 60 * 1_000L
        private const val MAX_HEARTBEAT_FIX_AGE_MS = 20_000L
        private const val MAX_BATCHED_LOCATION_FIXES = 100
        private const val TAG = "SafarDriverGPS"
        fun start(context: Context, driverId: String? = null, routeId: String? = null) {
            val hasPermission =
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                android.util.Log.w(TAG, "DriverLocationService.start skipped: location permission is not granted")
                return
            }
            val intent = Intent(context, DriverLocationService::class.java)
            if (!driverId.isNullOrBlank() && !routeId.isNullOrBlank()) {
                intent.putExtra(EXTRA_DRIVER_ID, driverId)
                intent.putExtra(EXTRA_ROUTE_ID, routeId)
            }
            ContextCompat.startForegroundService(
                context,
                intent,
            )
        }
        fun stop(context: Context) { context.startService(Intent(context, DriverLocationService::class.java).setAction(ACTION_STOP)) }
    }
}
