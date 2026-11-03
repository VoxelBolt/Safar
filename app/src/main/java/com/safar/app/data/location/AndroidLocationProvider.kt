package com.safar.app.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.util.Log
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Looper
import android.content.Intent
import android.provider.Settings
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.safar.app.domain.model.GeoPoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.sample
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AndroidLocationProvider(context: Context) {
    private val context = context.applicationContext
    private val fusedClient = LocationServices.getFusedLocationProviderClient(this.context)
    private val locationManager = this.context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    // Shared by streaming and one-shot fixes so cached/current callbacks cannot
    // overwrite a stable map point with an unfiltered GPS wobble.
    private val locationStabilizer = LocationStabilizer()
    // Keep a separate display filter so the local marker can show Android's
    // approximate-location fixes without relaxing the driver's odometer filter.
    private val displayLocationStabilizer = LocationStabilizer.display()

    @SuppressLint("MissingPermission")
    fun locationSamples(): Flow<LocationSample> = filteredLocationSamples(
        stabilizer = locationStabilizer,
        maxAcceptedAccuracyMeters = TRACKING_MAX_ACCURACY_METERS,
    )

    @SuppressLint("MissingPermission")
    private fun filteredLocationSamples(
        stabilizer: LocationStabilizer,
        maxAcceptedAccuracyMeters: Double,
        diagnosticStream: String = "tracking",
    ): Flow<LocationSample> = SharedAndroidLocationFeed.locations(context)
        .filter { sample -> sample.isRecentAccurateFix(maxAcceptedAccuracyMeters) }
        .mapNotNull { sample ->
            val accepted = stabilizer.filter(sample)
            GpsDiagnosticLog.record(context, diagnosticStream, sample, accepted)
            accepted
        }
        .distinctUntilChanged()

    /** Single fused/platform GPS request shared by the map and foreground service. */
    @SuppressLint("MissingPermission")
    internal fun rawLocationSamples(): Flow<LocationSample> = callbackFlow {
        val platformFallbackRequested = AtomicBoolean(false)
        // Lock one provider for the lifetime of this stream. Switching between
        // Fused and LocationManager fixes can turn their coordinate offsets
        // into apparent movement even when both fixes are recent.
        val selectedSource = AtomicInteger(SOURCE_UNDECIDED)

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!location.isRecentAccurateFix(MAX_DISPLAY_ACCURACY_METERS)) return
                if (!selectedSource.compareAndSet(SOURCE_UNDECIDED, SOURCE_PLATFORM) &&
                    selectedSource.get() != SOURCE_PLATFORM
                ) return
                trySend(location.toLocationSample())
            }
        }

        val gpsEnabled = runCatching {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
        val networkEnabled = runCatching {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
        val locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { locationManager.isLocationEnabled }.getOrDefault(false)
        } else {
            gpsEnabled || networkEnabled
        }

        fun requestPlatformFallback() {
            if (!platformFallbackRequested.compareAndSet(false, true)) return
            val fallbackProvider = when {
                hasFineLocationPermission() && gpsEnabled -> LocationManager.GPS_PROVIDER
                networkEnabled -> LocationManager.NETWORK_PROVIDER
                gpsEnabled -> LocationManager.GPS_PROVIDER
                else -> null
            } ?: return

            runCatching {
                locationManager.requestLocationUpdates(
                    fallbackProvider,
                    5_000L,
                    SHARED_MIN_UPDATE_DISTANCE_METERS,
                    listener,
                    Looper.getMainLooper(),
                )
            }.onSuccess {
                // Fused can recover while the platform fallback is being started.
                if (selectedSource.get() == SOURCE_FUSED && platformFallbackRequested.compareAndSet(true, false)) {
                    locationManager.removeUpdates(listener)
                }
            }.onFailure {
                platformFallbackRequested.set(false)
                Log.w(TAG, "Platform GPS fallback failed", it)
            }
        }

        Log.d(TAG, "locationSamples: locationEnabled=$locationEnabled gps=$gpsEnabled network=$networkEnabled")

        // Use one live provider so raw GPS and fused fixes cannot alternate and
        // turn source-to-source offsets into apparent movement. Fall back to the
        // platform provider only if fused updates fail or never deliver a fix.
        val fusedPriority = if (hasFineLocationPermission()) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY

        val fusedRequest = LocationRequest.Builder(fusedPriority, 2_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .setMinUpdateDistanceMeters(SHARED_MIN_UPDATE_DISTANCE_METERS)
            .setWaitForAccurateLocation(true)
            .build()

        val fusedCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.maxByOrNull { it.elapsedRealtimeNanos }?.let {
                    if (it.isRecentAccurateFix(MAX_DISPLAY_ACCURACY_METERS) &&
                        (selectedSource.compareAndSet(SOURCE_UNDECIDED, SOURCE_FUSED) ||
                            selectedSource.get() == SOURCE_FUSED)
                    ) {
                        if (platformFallbackRequested.compareAndSet(true, false)) {
                            locationManager.removeUpdates(listener)
                        }
                        trySend(it.toLocationSample())
                    }
                }
            }
        }

        runCatching {
            fusedClient.requestLocationUpdates(
                fusedRequest,
                fusedCallback,
                Looper.getMainLooper(),
            )
        }.onSuccess { task ->
            task.addOnFailureListener { error ->
                Log.w(TAG, "Fused requestLocationUpdates failed; using platform GPS", error)
                requestPlatformFallback()
            }
        }.onFailure {
            Log.w(TAG, "Fused requestLocationUpdates failed; using platform GPS", it)
            requestPlatformFallback()
        }

        launch {
            delay(10_000L)
            if (selectedSource.get() == SOURCE_UNDECIDED) requestPlatformFallback()
        }

        awaitClose {
            locationManager.removeUpdates(listener)
            fusedClient.removeLocationUpdates(fusedCallback)
        }
    }

    @SuppressLint("MissingPermission")
    fun locations(): Flow<GeoPoint> = locationSamples().map { it.point }

    /** Local driver marker stream; tracking and passenger location publishing stay strict. */
    @SuppressLint("MissingPermission")
    fun displayLocations(): Flow<GeoPoint> = displayLocationSamples().map { it.point }

    /** Best recent position for the local blue dot, including Android approximate-location fixes. */
    @SuppressLint("MissingPermission")
    fun displayLocationSamples(): Flow<LocationSample> = filteredLocationSamples(
        stabilizer = displayLocationStabilizer,
        maxAcceptedAccuracyMeters = displayFixAccuracyLimit(),
        diagnosticStream = "display",
    )

    /**
     * Device orientation as a compass azimuth in degrees clockwise from north.
     * Sensor updates are independent of GPS, so heading remains available while stopped.
     */
    fun headingDegrees(): Flow<Float> = SharedAndroidLocationFeed.headings(context)

    @OptIn(FlowPreview::class)
    @SuppressLint("MissingPermission")
    internal fun rawHeadingDegrees(): Flow<Float> = callbackFlow {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        // Fallback for devices that expose only the game rotation vector. It still
        // tracks physical turns when a calibrated compass vector is unavailable.
        val gameRotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        @Suppress("DEPRECATION")
        val legacyOrientationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION)

        // Prefer the rotation vector. Fall back to accelerometer + magnetometer,
        // then the legacy orientation sensor on devices exposing neither pair.
        if (rotationSensor == null && gameRotationSensor == null &&
            (accelerometer == null || magnetometer == null) &&
            legacyOrientationSensor == null
        ) {
            Log.e(TAG, "No usable orientation sensor found; heading stream unavailable")
            close()
            return@callbackFlow
        }

        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        var gravityValues: FloatArray? = null
        var magneticValues: FloatArray? = null
        var lastEmittedHeading: Float? = null
        // Android's azimuth is measured clockwise from magnetic north using the
        // device's natural Y axis (the top edge in portrait). Read that value
        // directly from the sensor matrix. The previous display-axis remapping
        // used the current UI rotation as a second coordinate transform and could
        // cancel/offset the physical yaw on some handsets, leaving the sector static.
        fun emitHeadingFromMatrix() {
            SensorManager.getOrientation(rotationMatrix, orientation)
            val rawDegrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
            val degrees = ((rawDegrees % 360f) + 360f) % 360f
            if (!degrees.isFinite()) return

            // Filter only sub-degree sensor noise. A physical turn must update both
            // the driver's heading sector and the heading sent to passenger maps.
            val previous = lastEmittedHeading
            val delta = if (previous == null) 360f else kotlin.math.abs(
                ((degrees - previous + 540f) % 360f) - 180f
            )
            if (delta >= 1.0f) {
                lastEmittedHeading = degrees
                trySend(degrees)
            }
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR ||
                    event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR
                ) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    emitHeadingFromMatrix()
                    return
                }

                // Last-resort compatibility path for older or unusual Android devices.
                @Suppress("DEPRECATION")
                if (event.sensor.type == Sensor.TYPE_ORIENTATION) {
                    val degrees = ((event.values[0] % 360f) + 360f) % 360f
                    val previous = lastEmittedHeading
                    val delta = if (previous == null) 360f else kotlin.math.abs(
                        ((degrees - previous + 540f) % 360f) - 180f
                    )
                    if (degrees.isFinite() && delta >= 1f) {
                        lastEmittedHeading = degrees
                        trySend(degrees)
                        Log.d(TAG, "Legacy compass heading updated: ${degrees.toInt()}°")
                    }
                    return
                }

                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> gravityValues = event.values.clone()
                    Sensor.TYPE_MAGNETIC_FIELD -> magneticValues = event.values.clone()
                }
                val gravity = gravityValues ?: return
                val magnetic = magneticValues ?: return
                if (SensorManager.getRotationMatrix(rotationMatrix, null, gravity, magnetic)) {
                    emitHeadingFromMatrix()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        // Register exactly one heading source. Registering ROTATION_VECTOR and the
        // legacy ORIENTATION sensor together lets their different coordinate conventions
        // overwrite each other, which can make the heading appear frozen on some devices.
        // Prefer an absolute compass source; use one fallback only if registration fails.
        var registered = false
        if (rotationSensor != null) {
            registered = sensorManager.registerListener(
                listener, rotationSensor, SensorManager.SENSOR_DELAY_GAME
            )
            Log.d(TAG, "Heading sensor started: rotation vector, registered=$registered")
        }

        if (!registered && accelerometer != null && magnetometer != null) {
            val accelRegistered = sensorManager.registerListener(
                listener, accelerometer, SensorManager.SENSOR_DELAY_GAME
            )
            val magneticRegistered = sensorManager.registerListener(
                listener, magnetometer, SensorManager.SENSOR_DELAY_GAME
            )
            registered = accelRegistered && magneticRegistered
            if (!registered) sensorManager.unregisterListener(listener)
            Log.d(TAG, "Heading sensor started: accelerometer + magnetometer, registered=$registered")
        }

        if (!registered && legacyOrientationSensor != null) {
            @Suppress("DEPRECATION")
            registered = sensorManager.registerListener(
                listener, legacyOrientationSensor, SensorManager.SENSOR_DELAY_GAME
            )
            Log.d(TAG, "Heading sensor started: legacy orientation, registered=$registered")
        }

        if (!registered && gameRotationSensor != null) {
            registered = sensorManager.registerListener(
                listener, gameRotationSensor, SensorManager.SENSOR_DELAY_GAME
            )
            Log.d(TAG, "Heading sensor started: game rotation vector, registered=$registered")
        }

        if (!registered) {
            Log.e(TAG, "Compass sensor registration failed; heading stream cannot update")
            close()
            return@callbackFlow
        }

        awaitClose { sensorManager.unregisterListener(listener) }
    }.distinctUntilChanged().sample(200L)

    @SuppressLint("MissingPermission")
    fun currentLocation(onResult: (GeoPoint?) -> Unit) {
        currentLocationSample { sample -> onResult(sample?.point) }
    }

    @SuppressLint("MissingPermission")
    fun currentLocationSample(onResult: (LocationSample?) -> Unit) =
        currentLocationSample(locationStabilizer, TRACKING_MAX_ACCURACY_METERS, onResult)

    @SuppressLint("MissingPermission")
    fun currentDisplayLocationSample(onResult: (LocationSample?) -> Unit) =
        currentFusedLocationSample(
            displayLocationStabilizer,
            displayFixAccuracyLimit(),
            onResult,
        )

    /** Keeps one-shot map recentering on the same source as the live fused stream. */
    @SuppressLint("MissingPermission")
    private fun currentFusedLocationSample(
        stabilizer: LocationStabilizer,
        maxAcceptedAccuracyMeters: Double,
        onResult: (LocationSample?) -> Unit,
    ) {
        val priority = if (hasFineLocationPermission()) Priority.PRIORITY_HIGH_ACCURACY
            else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        fusedClient.getCurrentLocation(priority, CancellationTokenSource().token)
            .addOnSuccessListener { location ->
                val sample = location
                    ?.takeIf { it.isRecentAccurateFix(maxAcceptedAccuracyMeters) }
                    ?.toLocationSample()
                    ?.let(stabilizer::filter)
                onResult(sample)
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Fused display location request failed", error)
                onResult(null)
            }
    }

    @SuppressLint("MissingPermission")
    private fun currentLocationSample(
        stabilizer: LocationStabilizer,
        maxAcceptedAccuracyMeters: Double,
        onResult: (LocationSample?) -> Unit,
    ) {
        val fine = hasFineLocationPermission()
        val coarse = hasCoarseLocationPermission()
        val gpsEnabled = runCatching {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
        val networkEnabled = runCatching {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
        val locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { locationManager.isLocationEnabled }.getOrDefault(false)
        } else {
            gpsEnabled || networkEnabled
        }

        Log.d(
            TAG,
            "currentLocation: fine=$fine coarse=$coarse locationEnabled=$locationEnabled gps=$gpsEnabled network=$networkEnabled"
        )

        // First use an already cached fix if Android has one.
        val cached = listOfNotNull(
            runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull(),
            runCatching { locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull(),
        ).maxByOrNull { it.elapsedRealtimeNanos }

        if (cached != null && cached.isRecentAccurateFix(maxAcceptedAccuracyMeters)) {
            Log.d(TAG, "LocationManager cached fix: ${cached.latitude},${cached.longitude} accuracy=${cached.accuracy}")
            stabilizer.filter(cached.toLocationSample())?.let(onResult)
        }

        // If the phone-wide Location switch is off, no provider can produce
        // a coordinate. Open the system Location settings immediately instead
        // of silently returning null.
        if (!locationEnabled) {
            Log.w(TAG, "Location services are disabled - opening system Location settings")
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure {
                Log.e(TAG, "Could not open Location settings", it)
            }
            return
        }

        // On Android 11+ ask Android's own location manager for a fresh fix.
        // This is independent from Google Play Services and is important when
        // FusedLocationProvider returns null without a cached location.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && locationEnabled) {
            val providers = buildList {
                if (fine && gpsEnabled) add(LocationManager.GPS_PROVIDER)
                if (networkEnabled) add(LocationManager.NETWORK_PROVIDER)
                if (!fine && gpsEnabled) add(LocationManager.GPS_PROVIDER)
            }.distinct()

            for (provider in providers) {
                runCatching {
                    locationManager.getCurrentLocation(
                        provider,
                        null,
                        context.mainExecutor,
                    ) { location ->
                        Log.d(
                            TAG,
                            "LocationManager current fix provider=$provider result=${location?.latitude},${location?.longitude}"
                        )
                        if (location != null && location.isRecentAccurateFix(maxAcceptedAccuracyMeters)) {
                            stabilizer.filter(location.toLocationSample())?.let(onResult)
                        }
                    }
                }.onFailure {
                    Log.w(TAG, "LocationManager getCurrentLocation failed provider=$provider", it)
                }
            }
        } else if (!locationEnabled) {
            Log.w(TAG, "Location services are disabled")
        }

        val priority = if (fine) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        Log.d(TAG, "Fused request: priority=$priority")

        fusedClient.getCurrentLocation(
            priority,
            CancellationTokenSource().token,
        ).addOnSuccessListener { location ->
            Log.d(TAG, "Fused getCurrentLocation result: ${location?.latitude},${location?.longitude}")
            if (location != null && location.isRecentAccurateFix(maxAcceptedAccuracyMeters)) {
                stabilizer.filter(location.toLocationSample())?.let(onResult)
            }
        }.addOnFailureListener { error ->
            Log.w(TAG, "Fused getCurrentLocation failed", error)
        }
    }
    @SuppressLint("MissingPermission")
    fun lastKnownLocation(onResult: (GeoPoint?) -> Unit) {
        lastKnownLocation(locationStabilizer, TRACKING_MAX_ACCURACY_METERS, onResult)
    }

    @SuppressLint("MissingPermission")
    fun lastKnownDisplayLocation(onResult: (GeoPoint?) -> Unit) {
        lastKnownLocation(displayLocationStabilizer, displayFixAccuracyLimit(), onResult)
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(
        stabilizer: LocationStabilizer,
        maxAcceptedAccuracyMeters: Double,
        onResult: (GeoPoint?) -> Unit,
    ) {
        val candidates = listOfNotNull(
            runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull(),
            runCatching { locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull(),
        )
        val best = candidates.filter { it.isRecentAccurateFix(maxAcceptedAccuracyMeters) }
            .maxByOrNull { it.elapsedRealtimeNanos }
        if (best != null) {
            val stable = stabilizer.filter(best.toLocationSample())
            if (stable != null) onResult(stable.point)
            return
        }

        fusedClient.lastLocation
            .addOnSuccessListener { location ->
                val stable = location?.takeIf { it.isRecentAccurateFix(maxAcceptedAccuracyMeters) }
                    ?.toLocationSample()?.let(stabilizer::filter)
                onResult(stable?.point)
            }
            .addOnFailureListener { onResult(null) }
    }

    private fun hasFineLocationPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun hasCoarseLocationPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun displayFixAccuracyLimit(): Double =
        if (hasFineLocationPermission()) PRECISE_DISPLAY_MAX_ACCURACY_METERS else DISPLAY_MAX_ACCURACY_METERS

    private companion object {
        const val TAG = "SafarGPS"
        const val SOURCE_UNDECIDED = 0
        const val SOURCE_FUSED = 1
        const val SOURCE_PLATFORM = 2
        const val TRACKING_MAX_ACCURACY_METERS = 20.0
        const val SHARED_MIN_UPDATE_DISTANCE_METERS = 2f
        const val MAX_DISPLAY_ACCURACY_METERS = 250.0
        const val PRECISE_DISPLAY_MAX_ACCURACY_METERS = 30.0
        const val DISPLAY_MAX_ACCURACY_METERS = 250.0
    }

    private fun Location.isRecentAccurateFix(maxAccuracyMeters: Double): Boolean {
        if (!latitude.isFinite() || !longitude.isFinite() || !hasAccuracy() ||
            !accuracy.isFinite() || accuracy < 0f || accuracy.toDouble() > maxAccuracyMeters
        ) return false
        val ageMs = System.currentTimeMillis() - time
        return time > 0L && ageMs in -5_000L..15_000L
    }

    private fun LocationSample.isRecentAccurateFix(maxAccuracyMeters: Double): Boolean {
        val accuracy = accuracyMeters ?: return false
        if (!point.latitude.isFinite() || !point.longitude.isFinite() ||
            accuracy.isFinite().not() || accuracy < 0.0 || accuracy > maxAccuracyMeters
        ) return false
        val ageMs = System.currentTimeMillis() - timestampEpochMs
        return timestampEpochMs > 0L && ageMs in -5_000L..15_000L
    }

    private fun Location.toGeoPoint() = GeoPoint(latitude, longitude)
    private fun Location.toLocationSample() = LocationSample(
        point = toGeoPoint(),
        timestampEpochMs = time,
        accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
        speedMetersPerSecond = if (hasSpeed() && speed.isFinite() && speed >= 0f) speed.toDouble() else null,
        speedAccuracyMetersPerSecond = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasSpeedAccuracy() && speedAccuracyMetersPerSecond.isFinite()
        ) speedAccuracyMetersPerSecond.toDouble() else null,
        provider = provider,
    )

}

/** Shares one Android location callback between driver display and publishing flows. */
private object SharedAndroidLocationFeed {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var sharedLocations: SharedFlow<LocationSample>? = null
    @Volatile private var sharedHeadings: SharedFlow<Float>? = null

    fun locations(context: Context): SharedFlow<LocationSample> {
        sharedLocations?.let { return it }
        return synchronized(this) {
            sharedLocations ?: AndroidLocationProvider(context.applicationContext)
                .rawLocationSamples()
                .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L), replay = 1)
                .also { sharedLocations = it }
        }
    }

    fun headings(context: Context): SharedFlow<Float> {
        sharedHeadings?.let { return it }
        return synchronized(this) {
            sharedHeadings ?: AndroidLocationProvider(context.applicationContext)
                .rawHeadingDegrees()
                .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L), replay = 1)
                .also { sharedHeadings = it }
        }
    }
}
