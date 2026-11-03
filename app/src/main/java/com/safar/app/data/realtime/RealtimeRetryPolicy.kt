package com.safar.app.data.realtime

import kotlin.math.min
import kotlin.math.roundToLong

internal object RealtimeRetryPolicy {
    const val MIN_DELAY_MS = 1_000L
    const val MAX_DELAY_MS = 60_000L

    /** jitterFactor is sampled uniformly from [0.75, 1.25] by the caller. */
    fun delayMs(attempt: Int, jitterFactor: Double): Long {
        require(attempt >= 1)
        require(jitterFactor.isFinite() && jitterFactor in 0.75..1.25)
        val exponent = min(attempt - 1, 16)
        val exponential = (MIN_DELAY_MS * (1L shl exponent)).coerceAtMost(MAX_DELAY_MS)
        return (exponential * jitterFactor).roundToLong()
            .coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
    }
}
