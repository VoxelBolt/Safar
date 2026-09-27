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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.collect
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
    private val lastPublishedLocationTimestamp = AtomicLong(0L)
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
            val notification = buildNotification(if (networkAvailable) "Геопозиция передаётся пассажирам" else "Нет интернета — отправка приостановлена")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            stopSelf()
            return START_NOT_STICKY
        }
        startPublishingIfNeeded()
        return START_STICKY
    }

    private fun startPublishingIfNeeded() {
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

        // Never move the server heartbeat backwards if Android gives us a cached
        // location after a newer sample has already been published.
        val previous = lastPublishedLocationTimestamp.get()
        if (timestampEpochMs <= previous) return
        if (!lastPublishedLocationTimestamp.compareAndSet(previous, timestampEpochMs)) return

        val recordedAtIso = Instant.ofEpochMilli(timestampEpochMs).toString()
        publishDriverSample(sample.point.latitude, sample.point.longitude, recordedAtIso)
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
        )

        android.util.Log.d(
            TAG,
            "Vehicle location saved: vehicle=${driver.vehicleId} route=$routeId " +
                "lat=$latitude lon=$longitude recordedAt=$timestamp",
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
        private const val TAG = "SafarDriverGPS"
        fun start(context: Context) { ContextCompat.startForegroundService(context, Intent(context, DriverLocationService::class.java)) }
        fun stop(context: Context) { context.startService(Intent(context, DriverLocationService::class.java).setAction(ACTION_STOP)) }
    }
}
