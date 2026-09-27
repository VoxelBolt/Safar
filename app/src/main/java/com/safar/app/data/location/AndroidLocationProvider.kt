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
import kotlinx.coroutines.FlowPreview

class AndroidLocationProvider(private val context: Context) {
    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val acceptedLocationLock = Any()
    private var lastAcceptedLocation: Location? = null

    @SuppressLint("MissingPermission")
    fun locationSamples(): Flow<LocationSample> = callbackFlow {
        var emitted = false

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                acceptLocation(location, "LocationManager")?.let { accepted ->
                    emitted = true
                    Log.d(
                        TAG,
                        "LocationManager fix accepted: ${accepted.latitude},${accepted.longitude} " +
                            "accuracy=${accepted.accuracy}"
                    )
                    trySend(accepted.toLocationSample())
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
            candidates.maxByOrNull { it.elapsedRealtimeNanos }?.let { candidate ->
                acceptLocation(candidate, "last-known")?.let { accepted ->
                    emitted = true
                    trySend(accepted.toLocationSample())
                }
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

        // When fine GPS is available, do not mix the coarse network provider
        // into the same stream. Network fixes are a common source of 50-100m
        // jumps while the phone is stationary. Keep it only as a fallback
        // when GPS/fine permission is unavailable.
        if (networkEnabled && (!gpsEnabled || !hasFineLocationPermission())) {
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
                        acceptLocation(it, "Fused")?.let { accepted ->
                            emitted = true
                            Log.d(
                                TAG,
                                "Fused fix accepted: ${accepted.latitude},${accepted.longitude} " +
                                    "accuracy=${accepted.accuracy}"
                            )
                            trySend(accepted.toLocationSample())
                        }
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

    @SuppressLint("MissingPermission")
    fun headingDegrees(): Flow<Float> = callbackFlow {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: run { close(); return@callbackFlow }
        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val rotation = windowManager.defaultDisplay.rotation
                val remapped = FloatArray(9)
                when (rotation) {
                    Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped)
                    Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remapped)
                    Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped)
                    else -> rotationMatrix.copyInto(remapped)
                }
                SensorManager.getOrientation(remapped, orientation)
                var degrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
                if (degrees < 0f) degrees += 360f
                trySend(degrees)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
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
                if (fine && gpsEnabled) {
                    add(LocationManager.GPS_PROVIDER)
                } else if (networkEnabled) {
                    add(LocationManager.NETWORK_PROVIDER)
                } else if (gpsEnabled) {
                    add(LocationManager.GPS_PROVIDER)
                }
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
                        if (location != null) {
                            acceptLocation(location, "current-${provider}")?.let { accepted ->
                                onResult(accepted.toLocationSample())
                            }
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
            if (location != null) {
                acceptLocation(location, "fused-current")?.let { accepted ->
                    onResult(accepted.toLocationSample())
                }
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

    private companion object {
        const val TAG = "SafarGPS"
        const val MAX_ACCEPTED_ACCURACY_METERS = 65f
    }


    /**
     * Rejects bad-accuracy fixes and physically implausible jumps. A much more
     * accurate newer fix is allowed to replace a coarse previous fix (for
     * example, GPS replacing a network location).
     */
    private fun acceptLocation(location: Location, source: String): Location? {
        if (!location.latitude.isFinite() || !location.longitude.isFinite()) return null
        if (location.hasAccuracy() && location.accuracy > MAX_ACCEPTED_ACCURACY_METERS) {
            Log.d(TAG, "GPS fix rejected: source=$source accuracy=${location.accuracy}m")
            return null
        }

        synchronized(acceptedLocationLock) {
            val previous = lastAcceptedLocation
            if (previous == null) {
                lastAcceptedLocation = location
                return location
            }

            val elapsedSeconds = (
                (location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000_000.0
            )

            if (elapsedSeconds <= 0.0) {
                if (location.time <= previous.time) return null
                lastAcceptedLocation = location
                return location
            }

            val distanceMeters = previous.distanceTo(location)
            val currentAccuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 0.0
            val previousAccuracy = if (previous.hasAccuracy()) previous.accuracy.toDouble() else 0.0

            // Let a clearly better GPS fix replace a coarse network/fused fix
            // even if the two points are several metres apart.
            if (
                currentAccuracy > 0.0 &&
                previousAccuracy > 0.0 &&
                currentAccuracy + 5.0 < previousAccuracy
            ) {
                lastAcceptedLocation = location
                return location
            }

            val maxPlausibleSpeed = 32.0 // ~115 km/h, above normal city minibus speed.
            val allowedJump = max(
                25.0,
                maxPlausibleSpeed * elapsedSeconds +
                    min(currentAccuracy, 25.0) +
                    min(previousAccuracy, 25.0)
            )

            if (distanceMeters > allowedJump) {
                Log.d(
                    TAG,
                    "GPS jump rejected: source=$source distance=${"%.1f".format(distanceMeters)}m " +
                        "elapsed=${"%.2f".format(elapsedSeconds)}s " +
                        "accuracy=${"%.1f".format(currentAccuracy)}m"
                )
                return null
            }

            lastAcceptedLocation = location
            return location
        }
    }


    private fun Location.toGeoPoint() = GeoPoint(latitude, longitude)
    private fun Location.toLocationSample() = LocationSample(
        point = toGeoPoint(),
        timestampEpochMs = time,
    )
}
