package com.safar.app.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.util.Log
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Looper
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

        Log.d(TAG, "locationSamples: gps=$gpsEnabled network=$networkEnabled")
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
            }
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
            }
        }

        // Fused is retained as a second source, because some Samsung devices
        // provide a faster fix through Google Play Services.
        val fusedRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
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
        }

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
        // Immediately expose the best cached fix, then allow the fresh Fused fix
        // to replace it. The fresh callback is intentionally not suppressed.
        val gps = runCatching {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        }.getOrNull()
        val network = runCatching {
            locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()
        val fallback = listOfNotNull(gps, network).maxByOrNull { it.elapsedRealtimeNanos }

        if (fallback != null && fallback.latitude.isFinite() && fallback.longitude.isFinite()) {
            onResult(fallback.toLocationSample())
        }

        fusedClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            CancellationTokenSource().token,
        ).addOnSuccessListener { location ->
            Log.d(TAG, "getCurrentLocation result: ${location?.latitude},${location?.longitude}")
            if (location != null && location.latitude.isFinite() && location.longitude.isFinite()) {
                onResult(location.toLocationSample())
            }
        }.addOnFailureListener { error ->
            Log.w(TAG, "getCurrentLocation failed", error)
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

    private companion object { const val TAG = "SafarGPS" }

    private fun Location.toGeoPoint() = GeoPoint(latitude, longitude)
    private fun Location.toLocationSample() = LocationSample(
        point = toGeoPoint(),
        timestampEpochMs = time,
    )
}
