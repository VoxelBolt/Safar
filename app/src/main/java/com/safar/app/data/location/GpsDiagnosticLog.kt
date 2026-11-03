package com.safar.app.data.location

import android.content.Context
import android.net.Uri
import com.safar.app.domain.model.GeoPoint
import java.io.File
import java.io.OutputStream
import java.util.Locale

/** Local-only diagnostic capture. Coordinates are never uploaded by this class. */
object GpsDiagnosticLog {
    private const val PREFS = "gps_diagnostics"
    private const val ENABLED = "enabled"
    private const val FILE_NAME = "safar-gps-diagnostics.csv"
    private const val HEADER = "event_time_ms,stream,fix_time_ms,provider,latitude,longitude,accuracy_m,speed_mps,speed_accuracy_mps,raw_fix_used,stabilized_latitude,stabilized_longitude,stabilized_delta_m\n"
    private val lock = Any()
    private val lastAccepted = mutableMapOf<String, GeoPoint>()

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
        synchronized(lock) {
            lastAccepted.clear()
            val file = file(context)
            if (enabled) file.writeText(HEADER)
        }
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun record(context: Context, stream: String, raw: LocationSample, accepted: LocationSample?) {
        if (!isEnabled(context)) return
        synchronized(lock) {
            val prior = lastAccepted[stream]
            val acceptedDelta = if (accepted != null && prior != null) distanceMeters(prior, accepted.point) else null
            if (accepted != null) lastAccepted[stream] = accepted.point
            val rawFixUsed = accepted != null && distanceMeters(raw.point, accepted.point) < 0.1
            val fields = listOf(
                System.currentTimeMillis().toString(), csv(stream), raw.timestampEpochMs.toString(),
                csv(raw.provider), number(raw.point.latitude), number(raw.point.longitude),
                number(raw.accuracyMeters), number(raw.speedMetersPerSecond),
                number(raw.speedAccuracyMetersPerSecond), rawFixUsed.toString(),
                accepted?.point?.latitude?.let(::number).orEmpty(),
                accepted?.point?.longitude?.let(::number).orEmpty(), number(acceptedDelta),
            )
            file(context).appendText(fields.joinToString(",") + "\n")
        }
    }

    fun copyTo(context: Context, output: OutputStream) {
        val file = file(context)
        if (!file.exists()) file.writeText(HEADER)
        file.inputStream().use { input -> input.copyTo(output) }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
    private fun number(value: Double?): String = value?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.7f", it) }.orEmpty()
    private fun csv(value: String?): String = "\"${(value ?: "").replace("\"", "\"\"")}\""

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val result = FloatArray(1)
        android.location.Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, result)
        return result[0].toDouble()
    }
}
