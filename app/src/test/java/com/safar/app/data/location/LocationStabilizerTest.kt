package com.safar.app.data.location

import com.safar.app.domain.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationStabilizerTest {
    private fun sample(
        lat: Double,
        lon: Double = 69.0,
        time: Long,
        accuracy: Double = 5.0,
        speed: Double? = null,
        speedAccuracy: Double? = null,
    ) = LocationSample(GeoPoint(lat, lon), time, accuracy, speed, speedAccuracy)

    @Test
    fun holdsStationaryPointAgainstSmallGpsWobble() {
        val filter = LocationStabilizer()
        val first = filter.filter(sample(40.0, time = 1_000L))!!
        val second = filter.filter(sample(40.00004, time = 2_000L))!!
        val third = filter.filter(sample(39.99996, time = 3_000L))!!

        assertEquals(first.point.latitude, second.point.latitude, 0.0)
        assertEquals(first.point.longitude, third.point.longitude, 0.0)
    }

    @Test
    fun rejectsOneOffLargeGpsJump() {
        val filter = LocationStabilizer()
        val first = filter.filter(sample(40.0, time = 1_000L))!!
        val jumped = filter.filter(sample(40.0003, time = 2_000L))!!

        assertEquals(first.point.latitude, jumped.point.latitude, 0.0)
    }

    @Test
    fun repeatedLargeStationaryGpsOffsetNeverBecomesDistance() {
        val filter = LocationStabilizer()
        val first = filter.filter(sample(40.0, time = 1_000L))!!
        val firstJump = filter.filter(sample(40.001, time = 2_000L))!!
        val secondJump = filter.filter(sample(40.001, time = 4_000L))!!
        val thirdJump = filter.filter(sample(40.001, time = 6_000L))!!
        val fourthJump = filter.filter(sample(40.001, time = 8_000L))!!

        assertEquals(40.0, firstJump.point.latitude, 0.0)
        assertEquals(40.0, secondJump.point.latitude, 0.0)
        assertEquals(first.point.latitude, thirdJump.point.latitude, 0.0)
        assertEquals(first.point.latitude, fourthJump.point.latitude, 0.0)
    }

    @Test
    fun rejectsCoherentSixtyMeterGpsDriftWhileStationary() {
        val filter = LocationStabilizer()
        val first = filter.filter(sample(40.0, time = 1_000L))!!
        val offsets = listOf(40.000585, 40.001123, 40.001662)

        val outputs = offsets.mapIndexed { index, latitude ->
            filter.filter(sample(latitude, time = 3_000L + index * 2_000L))!!
        }

        outputs.forEach { assertEquals(first.point.latitude, it.point.latitude, 0.0) }
    }

    @Test
    fun repeatedLowSpeedFixesHoldPositionDuringGpsDrift() {
        val filter = LocationStabilizer()
        val first = filter.filter(
            sample(40.0, time = 1_000L, speed = 0.2, speedAccuracy = 1.0),
        )!!
        val offsets = listOf(40.000585, 40.001123, 40.001662)

        val outputs = offsets.mapIndexed { index, latitude ->
            filter.filter(
                sample(
                    latitude,
                    time = 3_000L + index * 2_000L,
                    speed = 0.2,
                    speedAccuracy = 1.0,
                ),
            )!!
        }

        outputs.forEach { assertEquals(first.point.latitude, it.point.latitude, 0.0) }
    }

    @Test
    fun rejectsVeryInaccurateFixAfterInitialPosition() {
        val filter = LocationStabilizer()
        val first = filter.filter(sample(40.0, time = 1_000L))!!
        val inaccurate = filter.filter(sample(40.001, time = 2_000L, accuracy = 80.0))!!

        assertEquals(first.point.latitude, inaccurate.point.latitude, 0.0)
    }

    @Test
    fun holdsPositionWhenGpsAlternatesByMoreThanTenMeters() {
        val filter = LocationStabilizer()
        val first = filter.filter(sample(40.0, time = 1_000L, accuracy = 6.0))!!
        val east = filter.filter(sample(40.00012, time = 2_000L, accuracy = 6.0))!!
        val west = filter.filter(sample(39.99988, time = 3_000L, accuracy = 6.0))!!

        assertEquals(first.point.latitude, east.point.latitude, 0.0)
        assertEquals(first.point.latitude, west.point.latitude, 0.0)
    }

    @Test
    fun rejectsModeratelyPoorFixesAfterInitialPosition() {
        val filter = LocationStabilizer()
        val first = filter.filter(sample(40.0, time = 1_000L, accuracy = 5.0))!!
        val poor = filter.filter(sample(40.0003, time = 2_000L, accuracy = 30.0))!!

        assertEquals(first.point.latitude, poor.point.latitude, 0.0)
        assertEquals(first.point.longitude, poor.point.longitude, 0.0)
    }

    @Test
    fun betterAccurateFixDoesNotMoveMarkerInsideStationaryAccuracyRadius() {
        val filter = LocationStabilizer()
        val first = filter.filter(sample(40.0, time = 1_000L, accuracy = 12.0))!!
        val improved = filter.filter(sample(40.00003, time = 2_000L, accuracy = 5.0))!!

        assertEquals(first.point.latitude, improved.point.latitude, 0.0)
    }

    @Test
    fun displayFilterFollowsPlausibleMovementWithMediumAccuracyFixes() {
        val filter = LocationStabilizer.display()
        filter.filter(sample(40.0, time = 1_000L, accuracy = 5.0))

        filter.filter(sample(40.0009, time = 6_000L, accuracy = 15.0))
        filter.filter(sample(40.00126, time = 8_000L, accuracy = 15.0))
        filter.filter(sample(40.00162, time = 10_000L, accuracy = 15.0))
        val moved = filter.filter(sample(40.00198, time = 12_000L, accuracy = 15.0))!!

        assertTrue(moved.point.latitude > 40.0019)
    }

    @Test
    fun keepsFollowingConfirmedBusMovementWithoutRestartingConfirmation() {
        val filter = LocationStabilizer()
        filter.filter(sample(40.0, time = 1_000L, accuracy = 5.0))

        // About 17 m per 2 seconds (~30 km/h), with a straight, plausible path.
        val points = listOf(40.00015, 40.00030, 40.00045, 40.00060, 40.00075)
        val outputs = points.mapIndexed { index, latitude ->
            filter.filter(sample(latitude, time = 3_000L + index * 2_000L, accuracy = 5.0))!!
        }

        assertTrue("confirmed vehicle movement should keep updating the marker", outputs.last().point.latitude >= points.last())
    }

    @Test
    fun poorAccuracyMovementFixesDoNotMoveTheDriverMarker() {
        val filter = LocationStabilizer()
        val first = filter.filter(sample(40.0, time = 1_000L, accuracy = 5.0))!!

        val fixes = listOf(40.0003, 40.0006, 40.0009, 40.0012).mapIndexed { index, latitude ->
            filter.filter(sample(latitude, time = 3_000L + index * 2_000L, accuracy = 45.0))!!
        }

        fixes.forEach { assertEquals(first.point.latitude, it.point.latitude, 0.0) }
    }

    @Test
    fun strictFilterTracksConsecutivePlausibleMovementWithoutAcceptingOneOffJump() {
        val filter = LocationStabilizer()
        filter.filter(sample(40.0, time = 1_000L))
        val oneOff = filter.filter(sample(40.000108, time = 2_000L))!!
        assertEquals(40.0, oneOff.point.latitude, 0.0)

        val secondStep = filter.filter(sample(40.000216, time = 4_000L))!!
        val thirdStep = filter.filter(sample(40.000324, time = 6_000L))!!
        val moving = filter.filter(sample(40.000432, time = 8_000L))!!

        assertEquals(40.0, secondStep.point.latitude, 0.0)
        assertEquals(40.0, thirdStep.point.latitude, 0.0)
        assertTrue(moving.point.latitude > 40.0004)
    }

    @Test
    fun repeatedTwentyMeterGpsOffsetDoesNotBecomeSessionMovement() {
        val filter = LocationStabilizer()
        val first = filter.filter(sample(40.0, time = 1_000L))!!
        filter.filter(sample(40.0002, time = 2_000L))
        val second = filter.filter(sample(40.0002, time = 4_000L))!!
        val third = filter.filter(sample(40.0002, time = 6_000L))!!
        val fourth = filter.filter(sample(40.0002, time = 8_000L))!!

        assertEquals(first.point.latitude, second.point.latitude, 0.0)
        assertEquals(first.point.latitude, third.point.latitude, 0.0)
        assertEquals(first.point.latitude, fourth.point.latitude, 0.0)
    }

    @Test
    fun tracksGradualTwentyMeterWalkAfterConsecutiveDirectionalFixes() {
        val filter = LocationStabilizer()
        filter.filter(sample(40.0, time = 1_000L))
        filter.filter(sample(40.0001, time = 2_000L))
        filter.filter(sample(40.00014, time = 4_000L))
        filter.filter(sample(40.00018, time = 6_000L))
        val moved = filter.filter(sample(40.00022, time = 8_000L))!!

        assertTrue(moved.point.latitude > 40.00021)
    }

    @Test
    fun markerAndDistanceAdvanceDuringFifteenMeterWalkWithSmallGpsSteps() {
        val filter = LocationStabilizer.display()
        val start = filter.filter(sample(40.0, time = 1_000L, accuracy = 6.0))!!
        val path = listOf(40.00002, 40.00005, 40.00008, 40.00011, 40.00014)
        val outputs = path.mapIndexed { index, latitude ->
            filter.filter(sample(latitude, time = 3_000L + index * 2_000L, accuracy = 6.0))!!
        }

        val moved = outputs.last().point.latitude
        val displayedDistanceMeters = 6_371_000.0 * Math.toRadians(moved - start.point.latitude)
        assertTrue("marker should follow a confirmed short walk", moved > start.point.latitude)
        assertTrue("accepted path should represent a meaningful portion of the walk", displayedDistanceMeters >= 8.0)
    }

    @Test
    fun reportedStationarySpeedSuppressesGpsDriftForAFullMinute() {
        val filter = LocationStabilizer()
        val first = filter.filter(
            sample(40.0, time = 1_000L, speed = 0.2, speedAccuracy = 0.2),
        )!!
        val offsets = listOf(40.0002, 39.9998, 40.00025, 39.99975, 40.00015, 39.99985)

        val outputs = offsets.mapIndexed { index, latitude ->
            filter.filter(
                sample(
                    latitude,
                    time = 11_000L + index * 10_000L,
                    speed = 0.2,
                    speedAccuracy = 0.2,
                ),
            )!!
        }

        outputs.forEach { assertEquals(first.point.latitude, it.point.latitude, 0.0) }
    }
}
