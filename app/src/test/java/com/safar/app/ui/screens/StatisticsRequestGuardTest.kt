package com.safar.app.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatisticsRequestGuardTest {
    @Test
    fun olderRequestCannotApplyAfterPeriodChanges() {
        val guard = StatisticsRequestGuard()
        val first = guard.begin()
        val second = guard.begin()

        assertFalse(guard.isCurrent(first, StatisticsPeriod.DAYS_7, StatisticsPeriod.DAYS_30))
        assertTrue(guard.isCurrent(second, StatisticsPeriod.DAYS_30, StatisticsPeriod.DAYS_30))
    }

    @Test
    fun requestCannotApplyToDifferentSelectedPeriodEvenIfItsTokenMatches() {
        val guard = StatisticsRequestGuard()
        val token = guard.begin()

        assertFalse(guard.isCurrent(token, StatisticsPeriod.DAYS_7, StatisticsPeriod.ALL_TIME))
    }
}
