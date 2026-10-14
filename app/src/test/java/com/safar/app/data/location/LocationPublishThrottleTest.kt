package com.safar.app.data.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPublishThrottleTest {
    @Test
    fun allowsFirstFreshFix() {
        val throttle = LocationPublishThrottle()
        assertTrue(throttle.shouldPublish(1_000L, 0L))
    }

    @Test
    fun dropsRapidFixesUntilFiveSecondsHavePassed() {
        val throttle = LocationPublishThrottle()
        assertTrue(throttle.shouldPublish(1_000L, 10_000L))
        assertFalse(throttle.shouldPublish(2_000L, 12_000L))
        assertTrue(throttle.shouldPublish(6_000L, 15_000L))
    }

    @Test
    fun rejectsDuplicateAndOutOfOrderTimestamps() {
        val throttle = LocationPublishThrottle()
        assertTrue(throttle.shouldPublish(5_000L, 10_000L))
        assertFalse(throttle.shouldPublish(5_000L, 15_000L))
        assertFalse(throttle.shouldPublish(4_000L, 20_000L))
    }
}
