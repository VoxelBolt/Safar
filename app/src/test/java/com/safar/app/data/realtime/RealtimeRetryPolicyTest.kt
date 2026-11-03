package com.safar.app.data.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeRetryPolicyTest {
    @Test
    fun retryDelayGrowsExponentiallyAndCapsAtOneMinute() {
        assertEquals(1_000L, RealtimeRetryPolicy.delayMs(1, 1.0))
        assertEquals(2_000L, RealtimeRetryPolicy.delayMs(2, 1.0))
        assertEquals(4_000L, RealtimeRetryPolicy.delayMs(3, 1.0))
        assertEquals(60_000L, RealtimeRetryPolicy.delayMs(20, 1.0))
    }

    @Test
    fun retryDelayAppliesBoundedJitterWithoutExceedingMaximum() {
        assertTrue(RealtimeRetryPolicy.delayMs(3, 0.75) in 3_000L..3_001L)
        assertEquals(5_000L, RealtimeRetryPolicy.delayMs(3, 1.25))
        assertEquals(60_000L, RealtimeRetryPolicy.delayMs(20, 1.25))
    }
}
