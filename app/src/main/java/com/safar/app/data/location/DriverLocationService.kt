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
    private val locationPublishMutex = Mutex()
    private var lastPublishedLocationTimestamp = 0L
    @Volatile private var lastSuccessfulPublishAtMs = 0L
    private val driverSnapshot = AtomicReference<com.safar.app.domain.model.Driver?>(null)
    @Volatile private var driverSnapshotLoadedAt = 0L
    @Volatile private var networkAvailable = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networkAvailable = true
            updateNotification("Геопозиция передаётся пассажирам")
            publishCurrentLocationOnce()
        }
        override fun onLost(network: Network) {
            networkAvailable = isNetworkAvailable()
            if (!networkAvailable) updateNotification("Нет интернета — отправка приостановлена")
        }
    }

    override fun onCreate() {
        super.onCreate()
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
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
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
                                    publishCurrentLocationOnce()
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
                        if (!networkAvailable || !hasLocationPermission()) return@collect
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

        // Safety channel: ask Android for a fresh single fix every few seconds.
        publishingJob = scope.launch {
            while (currentCoroutineContext().isActive && hasLocationPermission()) {
                try {
                    if (networkAvailable) {
                        publishCurrentLocationOnce()
                        updateNotification("Геопозиция передаётся пассажирам")
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

    private fun publishCurrentLocationOnce() {
        if (!networkAvailable || !hasLocationPermission()) return
        locationProvider.currentLocationSample { sample ->
            if (sample == null || !networkAvailable) {
                android.util.Log.w(TAG, "No fresh current-location sample received")
                return@currentLocationSample
            }
            scope.launch {
                try {
                    publishLocationSample(sample)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    android.util.Log.e(TAG, "Driver location publish failed", error)
                    updateNotification("Не удалось передать координату — повторяю автоматически")
                }
            }
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

        // Stream and fallback fixes can arrive concurrently. Serialize them so
        // the same fix cannot trigger overlapping backend writes.
        locationPublishMutex.withLock {
            val publishNow = System.currentTimeMillis()
            val isNewGpsFix = timestampEpochMs > lastPublishedLocationTimestamp
            val heading = latestHeadingDegrees
            val headingChanged = heading != null && (
                lastPublishedHeadingDegrees == null ||
                    angularDifferenceDegrees(lastPublishedHeadingDegrees!!, heading) >= HEADING_CHANGE_THRESHOLD_DEGREES
                )
            val canPublishHeading = headingChanged &&
                publishNow - lastHeadingPublishedAtMs >= HEADING_PUBLISH_MIN_INTERVAL_MS

            // Some Android devices repeatedly return the same cached timestamp
            // while stationary. Refresh that coordinate at the normal GPS
            // heartbeat interval, otherwise cleanup_expired_location_state()
            // deletes it after four minutes and passengers see "no active vehicle".
            val heartbeatDue = !isNewGpsFix &&
                publishNow - lastSuccessfulPublishAtMs >= PUBLISH_INTERVAL_MS

            if (!isNewGpsFix && !canPublishHeading && !heartbeatDue) return

            // A heartbeat gets a fresh timestamp only when the underlying fix is
            // still recent. This keeps the server TTL meaningful and allows the
            // passenger map to continue receiving a stationary driver's marker.
            val publishTimestamp = if (isNewGpsFix) timestampEpochMs else publishNow
            val recordedAtIso = Instant.ofEpochMilli(publishTimestamp).toString()
            publishDriverSample(sample.point.latitude, sample.point.longitude, recordedAtIso)

            lastSuccessfulPublishAtMs = System.currentTimeMillis()
            if (isNewGpsFix) lastPublishedLocationTimestamp = timestampEpochMs
            if (heading != null) {
                lastPublishedHeadingDegrees = heading
                lastHeadingPublishedAtMs = publishNow
            }
            android.util.Log.d(
                TAG,
                "Published driver fix: newGps=" + isNewGpsFix +
                    " heartbeat=" + heartbeatDue + " heading=" + heading +
                    " lat=" + sample.point.latitude + " lon=" + sample.point.longitude,
            )
        }
    }

    private fun angularDifferenceDegrees(a: Float, b: Float): Float {
        val difference = kotlin.math.abs(a - b) % 360f
        return kotlin.math.min(difference, 360f - difference)
    }

    private suspend fun publishDriverSample(latitude: Double, longitude: Double, recordedAtIso: String?) {
        val now = System.currentTimeMillis()
        if (driverSnapshot.get() == null || now - driverSnapshotLoadedAt >= DRIVER_REFRESH_MS) {
            val driver = AppContainer.driverRepository.getDriver()
            driverSnapshot.set(driver)
            driverSnapshotLoadedAt = now
        }
        val driver = driverSnapshot.get() ?: error("Driver profile not found")
        val routeId = driver.routeId ?: error("Driver route is not assigned yet")
        val timestamp = recordedAtIso ?: Instant.now().toString()

        // The database write is authoritative. Do not broadcast a location to
        // passengers if saving the current state failed.
        AppContainer.driverLocationRepository.submitLocation(
            latitude = latitude,
            longitude = longitude,
            recordedAtIso = timestamp,
            headingDegrees = latestHeadingDegrees,
        )

        // Persist the heading above, then broadcast the same GPS fix and compass
        // azimuth so passenger maps can rotate the vehicle marker immediately.
        runCatching {
            AppContainer.realtimeTransport.connect(routeId)
            AppContainer.realtimeTransport.sendDriverLocation(
                routeId = routeId,
                vehicleId = driver.vehicleId,
                latitude = latitude,
                longitude = longitude,
                recordedAtIso = timestamp,
                headingDegrees = latestHeadingDegrees,
            )
        }.onFailure { error ->
            android.util.Log.w(TAG, "Realtime vehicle location broadcast failed", error)
        }

        android.util.Log.d(
            TAG,
            "Vehicle location saved: vehicle=${driver.vehicleId} route=$routeId",
        )

        updateNotification("Геопозиция передаётся пассажирам")
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
        private const val DRIVER_REFRESH_MS = 30_000L
        private const val PUBLISH_INTERVAL_MS = 10_000L
        private const val HEADING_CHANGE_THRESHOLD_DEGREES = 4f
        private const val HEADING_PUBLISH_MIN_INTERVAL_MS = 1_000L
        private const val MAX_REUSABLE_FIX_AGE_MS = 3 * 60 * 1_000L
        private const val TAG = "SafarDriverGPS"
        fun start(context: Context) {
            val hasPermission =
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                android.util.Log.w(TAG, "DriverLocationService.start skipped: location permission is not granted")
                return
            }
            ContextCompat.startForegroundService(
                context,
                Intent(context, DriverLocationService::class.java),
            )
        }
        fun stop(context: Context) { context.startService(Intent(context, DriverLocationService::class.java).setAction(ACTION_STOP)) }
    }
}
