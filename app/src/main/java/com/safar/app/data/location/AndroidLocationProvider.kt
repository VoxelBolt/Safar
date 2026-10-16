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
import android.view.Surface
import android.view.WindowManager
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.safar.app.domain.model.GeoPoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample

class AndroidLocationProvider(private val context: Context) {
    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission")
    fun locationSamples(): Flow<LocationSample> = callbackFlow {
        var emitted = false

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (location.latitude.isFinite() && location.longitude.isFinite()) {
                    emitted = true
                    Log.d(TAG, "LocationManager fix: ${location.latitude},${location.longitude} accuracy=${location.accuracy}")
                    trySend(location.toLocationSample())
                }
            }
        }

        fun emitLastKnown() {
            val candidates = buildList {
                runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }
                    .getOrNull()?.let(::add)
                runCatching { locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }
                    .getOrNull()?.let(::add)
            }
            candidates.maxByOrNull { it.elapsedRealtimeNanos }?.let {
                emitted = true
                trySend(it.toLocationSample())
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

        Log.d(TAG, "locationSamples: locationEnabled=$locationEnabled gps=$gpsEnabled network=$networkEnabled")
        emitLastKnown()

        if (gpsEnabled) {
            runCatching {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1_000L,
                    0f,
                    listener,
                    Looper.getMainLooper(),
                )
            }.onFailure { Log.w(TAG, "GPS requestLocationUpdates failed", it) }
        }

        if (networkEnabled) {
            runCatching {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1_000L,
                    0f,
                    listener,
                    Looper.getMainLooper(),
                )
            }.onFailure { Log.w(TAG, "Network requestLocationUpdates failed", it) }
        }

        // Fused is retained as a second source, because some Samsung devices
        // provide a faster fix through Google Play Services.
        val fusedPriority = if (hasFineLocationPermission()) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY

        val fusedRequest = LocationRequest.Builder(fusedPriority, 2_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .setWaitForAccurateLocation(false)
            .build()

        val fusedCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.maxByOrNull { it.elapsedRealtimeNanos }?.let {
                    if (it.latitude.isFinite() && it.longitude.isFinite()) {
                        emitted = true
                        Log.d(TAG, "Fused fix: ${it.latitude},${it.longitude} accuracy=${it.accuracy}")
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
        }.onFailure { Log.w(TAG, "Fused requestLocationUpdates failed", it) }

        awaitClose {
            locationManager.removeUpdates(listener)
            fusedClient.removeLocationUpdates(fusedCallback)
        }
    }.distinctUntilChanged()

    @SuppressLint("MissingPermission")
    fun locations(): Flow<GeoPoint> = locationSamples().map { it.point }

    /**
     * Device orientation as a compass azimuth in degrees clockwise from north.
     * Sensor updates are independent of GPS, so heading remains available while stopped.
     */
    @SuppressLint("MissingPermission")
    fun headingDegrees(): Flow<Float> = callbackFlow {
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
        var gravityValues: FloatArray? = null
        var magneticValues: FloatArray? = null
        var lastEmittedHeading: Float? = null
        @Suppress("DEPRECATION")
        val displayRotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.rotation

        // Follow the phone's top edge rather than device Z-axis azimuth. This works
        // while the phone is held upright as well as when it is flat.
        fun emitHeadingFromMatrix() {
            val east: Float
            val north: Float
            when (displayRotation) {
                Surface.ROTATION_90 -> { east = rotationMatrix[0]; north = rotationMatrix[3] }
                Surface.ROTATION_180 -> { east = -rotationMatrix[1]; north = -rotationMatrix[4] }
                Surface.ROTATION_270 -> { east = -rotationMatrix[0]; north = -rotationMatrix[3] }
                else -> { east = rotationMatrix[1]; north = rotationMatrix[4] }
            }
            val horizontalMagnitude = kotlin.math.sqrt(east * east + north * north)
            if (horizontalMagnitude < 0.15f) return
            val rawDegrees = Math.toDegrees(kotlin.math.atan2(east.toDouble(), north.toDouble())).toFloat()
            val degrees = ((rawDegrees % 360f) + 360f) % 360f
            if (!degrees.isFinite()) return
            val previous = lastEmittedHeading
            val delta = if (previous == null) 360f else kotlin.math.abs(((degrees - previous + 540f) % 360f) - 180f)
            if (delta >= 1.0f) {
                lastEmittedHeading = degrees
                trySend(degrees)
                Log.d(TAG, "Phone top-edge compass heading updated: " + degrees.toInt() + "°")
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

        // Register one source at a time. Mixing the rotation vector and legacy
        // orientation sensor can overwrite a correct top-edge heading with an
        // azimuth measured around a different device axis.
        var registered = false
        if (rotationSensor != null) {
            registered = sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
            Log.d(TAG, "Heading sensor: rotation vector, registered=" + registered)
        }
        if (!registered && gameRotationSensor != null) {
            registered = sensorManager.registerListener(listener, gameRotationSensor, SensorManager.SENSOR_DELAY_GAME)
            Log.d(TAG, "Heading sensor: game rotation vector, registered=" + registered)
        }
        if (!registered && accelerometer != null && magnetometer != null) {
            val accelRegistered = sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
            val magneticRegistered = sensorManager.registerListener(listener, magnetometer, SensorManager.SENSOR_DELAY_GAME)
            registered = accelRegistered && magneticRegistered
            Log.d(TAG, "Heading sensor: accelerometer + magnetometer, registered=" + registered)
        }
        if (!registered && legacyOrientationSensor != null) {
            @Suppress("DEPRECATION")
            registered = sensorManager.registerListener(listener, legacyOrientationSensor, SensorManager.SENSOR_DELAY_GAME)
            Log.d(TAG, "Heading sensor: legacy orientation fallback, registered=" + registered)
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
    fun currentLocationSample(onResult: (LocationSample?) -> Unit) {
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

        if (cached != null && cached.latitude.isFinite() && cached.longitude.isFinite()) {
            Log.d(TAG, "LocationManager cached fix: ${cached.latitude},${cached.longitude} accuracy=${cached.accuracy}")
            onResult(cached.toLocationSample())
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
                        if (location != null && location.latitude.isFinite() && location.longitude.isFinite()) {
                            onResult(location.toLocationSample())
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
            if (location != null && location.latitude.isFinite() && location.longitude.isFinite()) {
                onResult(location.toLocationSample())
            }
        }.addOnFailureListener { error ->
            Log.w(TAG, "Fused getCurrentLocation failed", error)
        }
    }
    @SuppressLint("MissingPermission")
    fun lastKnownLocation(onResult: (GeoPoint?) -> Unit) {
        val candidates = listOfNotNull(
            runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull(),
            runCatching { locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull(),
        )
        val best = candidates.maxByOrNull { it.elapsedRealtimeNanos }
        if (best != null) {
            onResult(best.toGeoPoint())
            return
        }

        fusedClient.lastLocation
            .addOnSuccessListener { location -> onResult(location?.toGeoPoint()) }
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

    private companion object { const val TAG = "SafarGPS" }

    private fun Location.toGeoPoint() = GeoPoint(latitude, longitude)
    private fun Location.toLocationSample() = LocationSample(
        point = toGeoPoint(),
        timestampEpochMs = time,
    )
}
