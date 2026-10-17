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
 * Filters transient GPS drift before coordinates reach the map or Supabase.
 * Poor fixes are discarded, small stationary movement is held in place, and
 * larger jumps must be confirmed by consistent fixes before being accepted.
 */
internal class LocationStabilizer(
    private val stationaryDeadbandMeters: Double = 7.0,
    private val maxTrustedAccuracyMeters: Double = 25.0,
    private val jumpThresholdMeters: Double = 12.0,
    private val jumpConfirmationMs: Long = 2_500L,
) {
    private data class Candidate(
        val point: GeoPoint,
        val startedAtMs: Long,
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
            // Avoid starting the map at an obviously coarse network/cached fix.
            if (accuracy != null && accuracy > 50.0) return null
            accepted = sample
            return sample
        }

        // Cached/out-of-order samples must not pull the marker backwards.
        if (sample.timestampEpochMs > 0L && previous.timestampEpochMs > 0L &&
            sample.timestampEpochMs < previous.timestampEpochMs - 2_000L
        ) return previous

        // Accuracy is a radius estimate, not a guarantee. Do not let a poor fix
        // move the map when a reasonably accurate position has already been found.
        if (accuracy != null && accuracy > maxTrustedAccuracyMeters) {
            candidate = null
            return previous
        }

        val distance = distanceMeters(previous.point, sample.point)
        val elapsedMs = (sample.timestampEpochMs - previous.timestampEpochMs).coerceAtLeast(0L)
        val deadband = maxOf(
            stationaryDeadbandMeters,
            minOf(12.0, (accuracy ?: 8.0) * 0.65),
        )

        if (distance <= deadband) {
            candidate = null
            // Keep the point fixed; only advance time so subsequent samples are
            // compared against current measurements rather than a stale timestamp.
            accepted = previous.copy(timestampEpochMs = maxOf(previous.timestampEpochMs, sample.timestampEpochMs))
            return accepted
        }

        if (distance >= jumpThresholdMeters) {
            val now = sample.timestampEpochMs.takeIf { it > 0L } ?: System.currentTimeMillis()
            val oldCandidate = candidate
            val candidateDistance = oldCandidate?.let { distanceMeters(it.point, sample.point) } ?: Double.MAX_VALUE
            val consistencyRadius = maxOf(6.0, minOf(10.0, (accuracy ?: 8.0) * 0.5))
            val consistent = oldCandidate != null && candidateDistance <= consistencyRadius
            candidate = if (consistent) {
                oldCandidate!!.copy(
                    point = blend(oldCandidate.point, sample.point, 0.5),
                    count = oldCandidate.count + 1,
                    accuracyMeters = accuracy ?: oldCandidate.accuracyMeters,
                )
            } else {
                Candidate(sample.point, now, 1, accuracy)
            }
            val active = candidate!!
            if (active.count < 2 || now - active.startedAtMs < jumpConfirmationMs) return previous

            // Once movement is confirmed, move toward it gradually rather than
            // teleporting the marker directly to the newest noisy sample.
            val confirmed = sample.copy(point = blend(previous.point, active.point, 0.65))
            accepted = confirmed
            candidate = null
            return confirmed
        }

        candidate = null
        // Smooth plausible movement; relax the filter over longer sample intervals
        // so a moving driver does not trail too far behind.
        val alpha = when {
            elapsedMs <= 1_500L -> 0.25
            elapsedMs <= 4_000L -> 0.45
            else -> 0.70
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
