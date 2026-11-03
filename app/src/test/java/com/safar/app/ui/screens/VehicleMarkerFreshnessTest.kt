package com.safar.app.ui.screens

import com.safar.app.domain.model.Vehicle
import com.safar.app.domain.model.VehicleLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleMarkerFreshnessTest {
    @Test
    fun expiresMarkerAfterTtlWithoutPollOrRealtimeUpdate() {
        val tracker = VehicleMarkerFreshness(maxAgeMs = 65_000L)
        val bus = vehicle("bus-1", 1_000L)
        tracker.observeSnapshot(listOf(bus), nowElapsedMs = 10_000L)

        assertEquals(listOf(bus), tracker.retainFresh(listOf(bus), nowElapsedMs = 75_000L))
        assertTrue(tracker.retainFresh(listOf(bus), nowElapsedMs = 75_001L).isEmpty())
    }

    @Test
    fun successfulSnapshotRefreshesTtlAndRemovesMissingVehicles() {
        val tracker = VehicleMarkerFreshness(maxAgeMs = 100L)
        val bus1 = vehicle("bus-1", 1_000L)
        val bus2 = vehicle("bus-2", 1_000L)
        tracker.observeSnapshot(listOf(bus1, bus2), nowElapsedMs = 10L)
        tracker.observeSnapshot(listOf(bus1), nowElapsedMs = 100L)

        assertEquals(listOf(bus1), tracker.retainFresh(listOf(bus1), nowElapsedMs = 200L))
        assertTrue(tracker.retainFresh(listOf(bus2), nowElapsedMs = 200L).isEmpty())
    }

    @Test
    fun realtimeUpdateExtendsOnlyKnownMarkerTtl() {
        val tracker = VehicleMarkerFreshness(maxAgeMs = 100L)
        val bus = vehicle("bus-1", 1_000L)
        tracker.observeSnapshot(listOf(bus), nowElapsedMs = 10L)
        tracker.observeRealtime("unknown", nowElapsedMs = 90L)
        tracker.observeRealtime("bus-1", nowElapsedMs = 90L)

        assertEquals(listOf(bus), tracker.retainFresh(listOf(bus), nowElapsedMs = 190L))
        assertTrue(tracker.retainFresh(listOf(bus), nowElapsedMs = 191L).isEmpty())
    }

    private fun vehicle(id: String, timestamp: Long) = Vehicle(
        id = id,
        identifier = id,
        routeId = "route-1",
        currentLocation = VehicleLocation(id, 40.28, 69.62, timestamp),
        active = true,
    )
}
