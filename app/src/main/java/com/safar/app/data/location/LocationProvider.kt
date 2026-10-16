package com.safar.app.data.location

import com.safar.app.domain.model.GeoPoint
import kotlinx.coroutines.flow.Flow

/** Platform-neutral location boundary. */
interface LocationProvider {
    val locations: Flow<GeoPoint>
    suspend fun start()
    suspend fun stop()
}

/** A location sample with Android's timestamp and horizontal accuracy. */
data class LocationSample(
    val point: GeoPoint,
    val timestampEpochMs: Long,
    val accuracyMeters: Double? = null,
)

/**
 * Rejects short-lived GPS teleportation and damps stationary drift before
 * coordinates reach either map UI or Supabase. It is intentionally conservative:
 * ordinary movement is smoothed, while a large jump must persist for 2.5 seconds
 * and agree across at least two fixes before the accepted position moves.
 */
internal class LocationStabilizer(
    private val stationaryDeadbandMeters: Double = 4.0,
    private val maxTrustedAccuracyMeters: Double = 45.0,
    private val jumpThresholdMeters: Double = 14.0,
    private val jumpConfirmationMs: Long = 2_500L,
) {
    private data class Candidate(
        val point: GeoPoint,
        val startedAtMs: Long,
        val lastFixAtMs: Long,
        val count: Int,
        val accuracyMeters: Double?,
    )

    private var accepted: LocationSample? = null
    private var candidate: Candidate? = null

    @Synchronized
    fun filter(sample: LocationSample): LocationSample? {
        val lat = sample.point.latitude
        val lon = sample.point.longitude
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val accuracy = sample.accuracyMeters
        if (accuracy != null && (!accuracy.isFinite() || accuracy < 0.0)) return null

        val previous = accepted
        if (previous == null) {
            if (accuracy != null && accuracy > 100.0) return null
            accepted = sample
            return sample
        }

        // Old cached fixes must never pull the marker backwards.
        if (sample.timestampEpochMs > 0L && previous.timestampEpochMs > 0L &&
            sample.timestampEpochMs < previous.timestampEpochMs - 2_000L
        ) return previous

        if (accuracy != null && accuracy > maxTrustedAccuracyMeters) {
            candidate = null
            return previous
        }

        val distance = distanceMeters(previous.point, sample.point)
        val elapsedMs = (sample.timestampEpochMs - previous.timestampEpochMs).coerceAtLeast(0L)
        val deadband = maxOf(
            stationaryDeadbandMeters,
            minOf(9.0, (accuracy ?: 8.0) * 0.45),
        )

        if (distance <= deadband) {
            candidate = null
            // Keep the accepted coordinate fixed for small stationary GPS noise.
            accepted = previous.copy(timestampEpochMs = maxOf(previous.timestampEpochMs, sample.timestampEpochMs))
            return accepted
        }

        if (distance >= jumpThresholdMeters) {
            val now = sample.timestampEpochMs.takeIf { it > 0L } ?: System.currentTimeMillis()
            val oldCandidate = candidate
            val candidateDistance = oldCandidate?.let { distanceMeters(it.point, sample.point) } ?: Double.MAX_VALUE
            val consistent = oldCandidate != null && candidateDistance <= maxOf(8.0, (accuracy ?: 8.0) * 0.6)
            candidate = if (consistent) {
                oldCandidate!!.copy(
                    point = blend(oldCandidate.point, sample.point, 0.5),
                    lastFixAtMs = now,
                    count = oldCandidate.count + 1,
                    accuracyMeters = accuracy ?: oldCandidate.accuracyMeters,
                )
            } else {
                Candidate(sample.point, now, now, 1, accuracy)
            }
            val active = candidate!!
            if (active.count < 2 || now - active.startedAtMs < jumpConfirmationMs) return previous
            val confirmed = sample.copy(point = active.point)
            accepted = confirmed
            candidate = null
            return confirmed
        }

        candidate = null
        // For plausible motion, apply a low-pass filter instead of rendering raw GPS jitter.
        val alpha = when {
            elapsedMs <= 1_500L -> 0.35
            elapsedMs <= 4_000L -> 0.55
            else -> 0.75
        }
        val smoothed = sample.copy(point = blend(previous.point, sample.point, alpha))
        accepted = smoothed
        return smoothed
    }

    private fun blend(from: GeoPoint, to: GeoPoint, alpha: Double) = GeoPoint(
        latitude = from.latitude + (to.latitude - from.latitude) * alpha,
        longitude = from.longitude + (to.longitude - from.longitude) * alpha,
    )

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val h = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(lat1) * kotlin.math.cos(lat2) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return 2.0 * earthRadius * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
    }
}
