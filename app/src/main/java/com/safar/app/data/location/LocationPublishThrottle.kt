package com.safar.app.data.location

/**
 * Prevents duplicate or overly frequent GPS samples from generating backend writes.
 */
internal class LocationPublishThrottle(
    private val minimumIntervalMs: Long = 5_000L,
) {
    private var lastAcceptedTimestampEpochMs = 0L
    private var lastAcceptedElapsedRealtimeMs = Long.MIN_VALUE

    @Synchronized
    fun shouldPublish(timestampEpochMs: Long, elapsedRealtimeMs: Long): Boolean {
        if (timestampEpochMs <= 0L || timestampEpochMs <= lastAcceptedTimestampEpochMs) return false

        val previousElapsed = lastAcceptedElapsedRealtimeMs
        if (previousElapsed != Long.MIN_VALUE &&
            elapsedRealtimeMs - previousElapsed < minimumIntervalMs
        ) return false

        lastAcceptedTimestampEpochMs = timestampEpochMs
        lastAcceptedElapsedRealtimeMs = elapsedRealtimeMs
        return true
    }
}
