package com.safar.app.data.location

import com.safar.app.domain.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationStabilizerTest {
    private fun sample(lat: Double, lon: Double = 69.0, time: Long, accuracy: Double = 5.0) =
        LocationSample(GeoPoint(lat, lon), time, accuracy)

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
    fun acceptsLargeMoveOnlyAfterConsistentFixesPersist() {
        val filter = LocationStabilizer()
        filter.filter(sample(40.0, time = 1_000L))
        val firstJump = filter.filter(sample(40.0003, time = 2_000L))!!
        val secondJump = filter.filter(sample(40.0003, time = 4_800L))!!

        assertEquals(40.0, firstJump.point.latitude, 0.0)
        assertNotNull(secondJump)
        assertTrue(secondJump.point.latitude > 40.0)
        assertEquals(40.0003, secondJump.point.latitude, 0.00001)
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
}
