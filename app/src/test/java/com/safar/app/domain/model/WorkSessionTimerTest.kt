package com.safar.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkSessionTimerTest {
    @Test
    fun activeSessionUsesRealElapsedTime() {
        val session = WorkSession(
            id = "s1",
            driverId = "d1",
            startTimeEpochMs = 1_000L,
            pauseTimeEpochMs = null,
            endTimeEpochMs = null,
            durationSeconds = 0L,
            totalPausedSeconds = 0L,
            distanceMeters = 0.0,
            status = WorkSessionStatus.ACTIVE,
        )

        assertEquals(9L, WorkSessionTimer.elapsedWorkSeconds(session, 10_000L))
    }

    @Test
    fun pausedSessionDoesNotCountCurrentPause() {
        val session = WorkSession(
            id = "s1",
            driverId = "d1",
            startTimeEpochMs = 1_000L,
            pauseTimeEpochMs = 5_000L,
            endTimeEpochMs = null,
            durationSeconds = 0L,
            totalPausedSeconds = 0L,
            distanceMeters = 0.0,
            status = WorkSessionStatus.PAUSED,
        )

        assertEquals(4L, WorkSessionTimer.elapsedWorkSeconds(session, 10_000L))
    }

    @Test
    fun resumedSessionSubtractsCompletedPause() {
        val session = WorkSession(
            id = "s1",
            driverId = "d1",
            startTimeEpochMs = 1_000L,
            pauseTimeEpochMs = null,
            endTimeEpochMs = null,
            durationSeconds = 0L,
            totalPausedSeconds = 4L,
            distanceMeters = 0.0,
            status = WorkSessionStatus.ACTIVE,
        )

        assertEquals(5L, WorkSessionTimer.elapsedWorkSeconds(session, 10_000L))
    }

    @Test
    fun completedSessionUsesPersistedDuration() {
        val session = WorkSession(
            id = "s1",
            driverId = "d1",
            startTimeEpochMs = 1_000L,
            pauseTimeEpochMs = null,
            endTimeEpochMs = 20_000L,
            durationSeconds = 12L,
            totalPausedSeconds = 5L,
            distanceMeters = 0.0,
            status = WorkSessionStatus.COMPLETED,
        )

        assertEquals(12L, WorkSessionTimer.elapsedWorkSeconds(session, 100_000L))
    }
}
