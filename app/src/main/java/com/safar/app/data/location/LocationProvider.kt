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
    val speedMetersPerSecond: Double? = null,
    val speedAccuracyMetersPerSecond: Double? = null,
    val provider: String? = null,
)

/**
 * Filters transient GPS drift before coordinates reach the map or Supabase.
 * Poor fixes are discarded, small stationary movement is held in place, and
 * movement outside a fix's accuracy radius must be confirmed by a coherent path.
 */
internal class LocationStabilizer(
    private val stationaryDeadbandMeters: Double = 8.0,
    private val maxTrustedAccuracyMeters: Double = 20.0,
    private val maxMovementFixAccuracyMeters: Double = 20.0,
    private val accuracyDeadbandFactor: Double = 1.0,
    private val maxAccuracyDeadbandMeters: Double = 20.0,
    private val requiredConsecutiveMovingSteps: Int = 3,
    private val maxPlausibleSpeedMetersPerSecond: Double = 22.2,
    private val movementAccuracyFactor: Double = 1.0,
    private val minMovementThresholdMeters: Double = 8.0,
    private val maxMovementThresholdMeters: Double = 25.0,
    private val allowAccurateFixReanchor: Boolean = false,
) {
    private data class Candidate(
        val point: GeoPoint,
        val lastPoint: GeoPoint,
        val lastTimestampEpochMs: Long,
        val accuracyMeters: Double?,
        val movementStartPoint: GeoPoint? = null,
        val consecutiveMovingSteps: Int = 0,
        val consistentFixCount: Int = 1,
    )

    /** Separate, stricter filter for the driver's local map marker. */
    companion object {
        private const val MIN_DIRECTIONAL_STEP_METERS = 0.75
        private const val MAX_STATIONARY_SPEED_ACCURACY_METERS_PER_SECOND = 1.0
        private const val MAX_STATIONARY_SPEED_UPPER_BOUND_METERS_PER_SECOND = 0.75
        private const val MAX_LIKELY_STATIONARY_SPEED_METERS_PER_SECOND = 0.25
        private const val MAX_LIKELY_STATIONARY_SPEED_ACCURACY_METERS_PER_SECOND = 2.0
        private const val MAX_DISPLAY_ACCURACY_METERS = 250.0
        private const val MAX_MOVEMENT_ACCURACY_METERS = 30.0
        private const val ACCURATE_REANCHOR_ACCURACY_METERS = 30.0
        private const val REQUIRED_ACCURATE_REANCHOR_FIXES = 3

        fun display() = LocationStabilizer(
            // A blue dot is still useful with Android's approximate permission
            // or an indoor fix. This filter is display-only; the odometer keeps
            // the stricter 20 m limit from the default constructor.
            stationaryDeadbandMeters = 4.0,
            maxTrustedAccuracyMeters = MAX_DISPLAY_ACCURACY_METERS,
            // Coarse location can seed the visible dot, but only precise fixes
            // can move it or contribute to the driver's odometer.
            maxMovementFixAccuracyMeters = MAX_MOVEMENT_ACCURACY_METERS,
            accuracyDeadbandFactor = 0.2,
            maxAccuracyDeadbandMeters = 6.0,
            // The display stream is not the odometer. Two coherent fixes make
            // the blue dot respond sooner while the strict tracking stream
            // still requires three fixes before adding distance.
            requiredConsecutiveMovingSteps = 2,
            maxPlausibleSpeedMetersPerSecond = 22.2,
            movementAccuracyFactor = 0.5,
            minMovementThresholdMeters = 4.0,
            maxMovementThresholdMeters = 10.0,
            allowAccurateFixReanchor = true,
        )
    }

    private var accepted: LocationSample? = null
    private var candidate: Candidate? = null
    private var likelyStationaryFixCount = 0

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
            if (accuracy != null && accuracy > maxTrustedAccuracyMeters) return null
            accepted = sample
            return sample
        }

        // Cached/out-of-order samples must not pull the marker backwards.
        if (sample.timestampEpochMs > 0L && previous.timestampEpochMs > 0L &&
            sample.timestampEpochMs < previous.timestampEpochMs
        ) return previous

        val previousAccuracy = previous.accuracyMeters
        if (allowAccurateFixReanchor && accuracy != null &&
            accuracy <= ACCURATE_REANCHOR_ACCURACY_METERS &&
            previousAccuracy != null && previousAccuracy >= 30.0 &&
            distanceMeters(previous.point, sample.point) > maxOf(stationaryDeadbandMeters, accuracy * 0.5)
        ) {
            // A coarse first fix can be far from the real position. Wait for a
            // small cluster of precise samples before correcting the display
            // marker, and do not count that correction as travelled distance.
            val oldCandidate = candidate
            val consistencyRadius = maxOf(3.0, minOf(6.0, accuracy * 0.35))
            val matchesCluster = oldCandidate != null &&
                distanceMeters(oldCandidate.point, sample.point) <= consistencyRadius
            val consistentFixCount = if (matchesCluster) oldCandidate.consistentFixCount + 1 else 1
            if (consistentFixCount >= REQUIRED_ACCURATE_REANCHOR_FIXES) {
                accepted = sample
                candidate = null
                return sample
            }
            candidate = Candidate(
                point = if (matchesCluster) blend(oldCandidate.point, sample.point, 0.5) else sample.point,
                lastPoint = sample.point,
                lastTimestampEpochMs = sample.timestampEpochMs,
                accuracyMeters = accuracy,
                consistentFixCount = consistentFixCount,
            )
            return previous
        }

        // GNSS Doppler speed is much less sensitive to position jitter than
        // subtracting two coordinates. A speed fix whose entire uncertainty
        // interval is near zero is strong evidence that the phone is stationary.
        likelyStationaryFixCount = if (sample.isLikelyStationary()) likelyStationaryFixCount + 1 else 0
        if (sample.isConfidentlyStationary() || likelyStationaryFixCount >= 2) {
            candidate = null
            val held = sample.copy(
                point = previous.point,
                timestampEpochMs = maxOf(previous.timestampEpochMs, sample.timestampEpochMs),
                accuracyMeters = previous.accuracyMeters,
            )
            accepted = held
            return held
        }

        // Accuracy is a radius estimate, not a guarantee. Do not let a poor fix
        // move the map when a reasonably accurate position has already been found.
        if (accuracy != null && accuracy > maxTrustedAccuracyMeters) {
            candidate = null
            return previous
        }

        val distance = distanceMeters(previous.point, sample.point)
        val deadband = maxOf(
            stationaryDeadbandMeters,
            minOf(maxAccuracyDeadbandMeters, (accuracy ?: 8.0) * accuracyDeadbandFactor),
        )

        val oldCandidate = candidate
        if (distance <= deadband && oldCandidate == null) {
            candidate = null
            // Keep the movement baseline fixed while fixes wobble inside their
            // accuracy radius. Advancing it on every noisy fix turns GPS drift
            // into a random walk and inflates the odometer.
            val held = sample.copy(
                point = previous.point,
                timestampEpochMs = maxOf(previous.timestampEpochMs, sample.timestampEpochMs),
                accuracyMeters = previous.accuracyMeters,
            )
            accepted = held
            return held
        }

        if (oldCandidate == null) {
            // Retain sub-deadband steps as a path candidate. Dropping each one
            // independently meant a person walking slowly could never build
            // enough displacement to move the marker or odometer.
            if (distance < MIN_DIRECTIONAL_STEP_METERS) return previous
            candidate = Candidate(
                point = sample.point,
                lastPoint = sample.point,
                lastTimestampEpochMs = sample.timestampEpochMs,
                accuracyMeters = accuracy,
            )
            return previous
        }

        val stepDistance = distanceMeters(oldCandidate.lastPoint, sample.point)
        val stepElapsedMs = (sample.timestampEpochMs - oldCandidate.lastTimestampEpochMs).coerceAtLeast(0L)
        val stepSpeed = if (stepElapsedMs >= 500L) stepDistance / (stepElapsedMs / 1000.0) else Double.POSITIVE_INFINITY
        val plausibleStep = stepDistance >= MIN_DIRECTIONAL_STEP_METERS &&
            stepElapsedMs in 500L..10_000L &&
            stepSpeed <= maxPlausibleSpeedMetersPerSecond &&
            (accuracy == null || accuracy <= maxMovementFixAccuracyMeters)

        if (plausibleStep) {
            val directionStart = oldCandidate.movementStartPoint
            val continuesDirection = directionStart != null &&
                isDirectionallyConsistent(directionStart, oldCandidate.lastPoint, sample.point)
            val movementSteps = if (continuesDirection) oldCandidate.consecutiveMovingSteps + 1 else 1
            // Speed can confirm that the phone is moving, but a single GPS
            // coordinate can still be an outlier. Always require the position
            // path to be confirmed by the configured number of fixes.
            val requiredSteps = requiredConsecutiveMovingSteps
            val moving = oldCandidate.copy(
                point = sample.point,
                lastPoint = sample.point,
                lastTimestampEpochMs = sample.timestampEpochMs,
                accuracyMeters = bestAccuracy(oldCandidate.accuracyMeters, accuracy),
                movementStartPoint = oldCandidate.lastPoint,
                consecutiveMovingSteps = movementSteps,
                consistentFixCount = 1,
            )
            val cumulativeDistance = distanceMeters(previous.point, sample.point)
            val uncertainty = maxOf(accuracy ?: 8.0, previous.accuracyMeters ?: 0.0)
            val movementThreshold = maxOf(
                minMovementThresholdMeters,
                minOf(maxMovementThresholdMeters, uncertainty * movementAccuracyFactor),
            )
            if (movementSteps >= requiredSteps && cumulativeDistance >= movementThreshold) {
                accepted = sample
                // Keep the confirmed path alive while direction and speed stay
                // coherent. Clearing it here made each next fix start a new
                // confirmation sequence, so the display marker appeared to lag
                // by several more seconds even after movement was established.
                candidate = moving
                return sample
            }
            candidate = moving
            return previous
        }

        val consistencyRadius = maxOf(3.0, minOf(6.0, (accuracy ?: 8.0) * 0.35))
        val consistentPosition = distanceMeters(oldCandidate.point, sample.point) <= consistencyRadius
        if (consistentPosition) {
            val consistentFixCount = oldCandidate.consistentFixCount + 1
            val previousAccuracy = previous.accuracyMeters
            val materiallyBetterFix = allowAccurateFixReanchor &&
                consistentFixCount >= REQUIRED_ACCURATE_REANCHOR_FIXES &&
                accuracy != null && accuracy <= ACCURATE_REANCHOR_ACCURACY_METERS &&
                (previousAccuracy == null ||
                    previousAccuracy >= 30.0 && accuracy <= previousAccuracy * 0.5)
            if (materiallyBetterFix) {
                // A coarse first fix can be tens or hundreds of metres away.
                // Re-anchor to a much better, repeatedly confirmed fix without
                // counting that correction as travelled distance.
                accepted = sample
                candidate = null
                return sample
            }

            val stationary = oldCandidate.copy(
                point = blend(oldCandidate.point, sample.point, 0.5),
                lastPoint = sample.point,
                lastTimestampEpochMs = sample.timestampEpochMs,
                accuracyMeters = bestAccuracy(oldCandidate.accuracyMeters, accuracy),
                movementStartPoint = null,
                consecutiveMovingSteps = 0,
                consistentFixCount = consistentFixCount,
            )
            candidate = stationary
            // Repeated fixes at a shifted coordinate are not evidence of travel:
            // GPS can settle at a wrong location and stay there. Only the moving
            // path branch above is allowed to advance the odometer baseline.
            return previous
        }

        candidate = Candidate(
            point = sample.point,
            lastPoint = sample.point,
            lastTimestampEpochMs = sample.timestampEpochMs,
            accuracyMeters = accuracy,
        )
        return previous
    }

    private fun blend(from: GeoPoint, to: GeoPoint, alpha: Double) = GeoPoint(
        latitude = from.latitude + (to.latitude - from.latitude) * alpha,
        longitude = from.longitude + (to.longitude - from.longitude) * alpha,
    )

    private fun bestAccuracy(first: Double?, second: Double?): Double? =
        listOfNotNull(first, second).minOrNull()

    private fun LocationSample.isConfidentlyStationary(): Boolean {
        val speed = speedMetersPerSecond ?: return false
        val speedAccuracy = speedAccuracyMetersPerSecond ?: return false
        return speed.isFinite() && speedAccuracy.isFinite() && speed >= 0.0 &&
            speedAccuracy in 0.0..MAX_STATIONARY_SPEED_ACCURACY_METERS_PER_SECOND &&
            speed + speedAccuracy <= MAX_STATIONARY_SPEED_UPPER_BOUND_METERS_PER_SECOND
    }

    private fun LocationSample.isLikelyStationary(): Boolean {
        val speed = speedMetersPerSecond ?: return false
        val speedAccuracy = speedAccuracyMetersPerSecond ?: return false
        return speed.isFinite() && speedAccuracy.isFinite() && speed >= 0.0 &&
            speedAccuracy in 0.0..MAX_LIKELY_STATIONARY_SPEED_ACCURACY_METERS_PER_SECOND &&
            speed <= MAX_LIKELY_STATIONARY_SPEED_METERS_PER_SECOND
    }

    private fun isDirectionallyConsistent(start: GeoPoint, middle: GeoPoint, end: GeoPoint): Boolean {
        val first = localVectorMeters(start, middle)
        val second = localVectorMeters(middle, end)
        val firstLength = kotlin.math.hypot(first.first, first.second)
        val secondLength = kotlin.math.hypot(second.first, second.second)
        if (firstLength < MIN_DIRECTIONAL_STEP_METERS || secondLength < MIN_DIRECTIONAL_STEP_METERS) return false
        val cosine = (first.first * second.first + first.second * second.second) / (firstLength * secondLength)
        return cosine >= 0.5
    }

    private fun localVectorMeters(from: GeoPoint, to: GeoPoint): Pair<Double, Double> {
        val earthRadius = 6_371_000.0
        val averageLatitude = Math.toRadians((from.latitude + to.latitude) / 2.0)
        val north = Math.toRadians(to.latitude - from.latitude) * earthRadius
        val east = Math.toRadians(to.longitude - from.longitude) * earthRadius * kotlin.math.cos(averageLatitude)
        return east to north
    }

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
