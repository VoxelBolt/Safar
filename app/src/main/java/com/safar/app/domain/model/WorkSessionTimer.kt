package com.safar.app.domain.model

/**
 * Calculates elapsed working time from server timestamps and pause accounting.
 * It never increments time by counting UI ticks, so backgrounding/reopening the
 * app does not reset or accelerate the timer.
 */
object WorkSessionTimer {
    fun elapsedWorkSeconds(session: WorkSession, nowEpochMs: Long): Long {
        if (session.status == WorkSessionStatus.COMPLETED) {
            return session.durationSeconds.coerceAtLeast(0L)
        }

        val started = session.startTimeEpochMs
            ?: return session.durationSeconds.coerceAtLeast(0L)
        val totalElapsed = ((nowEpochMs - started) / 1000L).coerceAtLeast(0L)
        val completedPauses = session.totalPausedSeconds.coerceAtLeast(0L)
        val currentPause = if (session.status == WorkSessionStatus.PAUSED) {
            val pausedAt = session.pauseTimeEpochMs ?: nowEpochMs
            ((nowEpochMs - pausedAt) / 1000L).coerceAtLeast(0L)
        } else {
            0L
        }

        return (totalElapsed - completedPauses - currentPause).coerceAtLeast(0L)
    }
}
