package com.safar.app.ui.screens

import com.safar.app.domain.model.PassengerLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassengerLocationVisibilityTest {
    @Test
    fun markerIsVisibleOnlyOnTheSelectedRoute() {
        val now = 1_000_000L
        val sameRoute = passenger("p1", "route-1", now)
        val otherRoute = passenger("p2", "route-2", now)

        assertEquals(
            listOf(sameRoute),
            PassengerLocationVisibility.visibleOnRoute(listOf(sameRoute, otherRoute), "route-1", now),
        )
    }

    @Test
    fun markerExpiresAfterOneMinuteWithoutARealtimeUpdate() {
        val receivedAt = 1_000_000L

        assertTrue(PassengerLocationVisibility.isFresh(receivedAt, receivedAt + 59_999L))
        assertTrue(PassengerLocationVisibility.isFresh(receivedAt, receivedAt + 60_000L))
        assertFalse(PassengerLocationVisibility.isFresh(receivedAt, receivedAt + 60_001L))
    }

    @Test
    fun markerRejectsFutureReceiveTimesAndInvalidCoordinates() {
        val now = 1_000_000L
        val future = passenger("future", "route-1", now + 1L)
        val invalid = passenger("invalid", "route-1", now).copy(latitude = Double.NaN)

        assertEquals(
            emptyList<PassengerLocation>(),
            PassengerLocationVisibility.visibleOnRoute(listOf(future, invalid), "route-1", now),
        )
    }

    private fun passenger(id: String, routeId: String, receivedAt: Long) = PassengerLocation(
        passengerId = id,
        routeId = routeId,
        latitude = 40.28,
        longitude = 69.62,
        timestampEpochMs = receivedAt,
        receivedAtElapsedMs = receivedAt,
    )
}
