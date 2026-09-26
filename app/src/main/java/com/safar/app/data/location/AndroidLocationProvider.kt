package com.safar.app.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.FlowPreview

class AndroidLocationProvider(private val context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    fun locationSamples(): Flow<LocationSample> = callbackFlow {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsEnabled = runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(true)
        val networkEnabled = runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(true)
        if (!gpsEnabled && !networkEnabled) {
            close(IllegalStateException("Службы геолокации отключены"))
            return@callbackFlow
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .setMaxUpdateDelayMillis(2_000L)
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations
                    .asSequence()
                    .filter { it.latitude.isFinite() && it.longitude.isFinite() }
                    .maxByOrNull { it.elapsedRealtimeNanos }
                    ?.let { location ->
                        trySend(location.toLocationSample()).isSuccess
                    }
            }
        }

        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { close(it) }

        awaitClose { client.removeLocationUpdates(callback) }
    }.distinctUntilChanged()

    @SuppressLint("MissingPermission")
    fun locations(): Flow<GeoPoint> = locationSamples().map { it.point }

    @OptIn(FlowPreview::class)
    fun headingDegrees(): Flow<Float> = callbackFlow {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: run { close(); return@callbackFlow }
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
    fun currentLocation(onResult: (GeoPoint?) -> Unit) { currentLocationSample { sample -> onResult(sample?.point) } }

    @SuppressLint("MissingPermission")
    fun currentLocationSample(onResult: (LocationSample?) -> Unit) {
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { location: Location? -> onResult(location?.toLocationSample()) }
            .addOnFailureListener { onResult(null) }
    }

    @SuppressLint("MissingPermission")
    fun lastKnownLocation(onResult: (GeoPoint?) -> Unit) {
        client.lastLocation.addOnSuccessListener { location: Location? -> onResult(location?.toGeoPoint()) }.addOnFailureListener { onResult(null) }
    }

    private fun Location.toGeoPoint() = GeoPoint(latitude, longitude)
    private fun Location.toLocationSample() = LocationSample(point = toGeoPoint(), timestampEpochMs = time)
}
