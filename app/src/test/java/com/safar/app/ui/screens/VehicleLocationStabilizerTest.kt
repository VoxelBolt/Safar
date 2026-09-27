package com.safar.app.ui.screens

import com.safar.app.domain.model.GeoPoint
import com.safar.app.domain.model.Vehicle
import com.safar.app.domain.model.VehicleLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleLocationStabilizerTest {
    private val route = listOf(
        GeoPoint(40.0000, 69.0000),
        GeoPoint(40.0010, 69.0000),
    )

    private val vehicle = Vehicle(
        id = "vehicle-1",
        identifier = "TEST",
        routeId = "route-1",
        currentLocation = null,
        active = true,
    )

    @Test
    fun snapsLocationWithinTwentyMetersToRoute() {
        val stabilizer = VehicleLocationStabilizer()
        val raw = VehicleLocation(
            vehicleId = vehicle.id,
            latitude = 40.0005,
            longitude = 69.00012,
            timestampEpochMs = 1_000L,
        )

        val result = stabilizer.stabilize(listOf(vehicle to raw), route).single().second

        assertEquals(69.0000, result.longitude, 0.00001)
        assertEquals(40.0005, result.latitude, 0.00001)
    }

    @Test
    fun ignoresLargeUnsappedJump() {
        val stabilizer = VehicleLocationStabilizer()
        val first = VehicleLocation(vehicle.id, 40.0002, 69.0010, 1_000L)
        val second = VehicleLocation(vehicle.id, 40.0002, 69.0025, 2_000L)

        stabilizer.stabilize(listOf(vehicle to first), emptyList())
        val result = stabilizer.stabilize(listOf(vehicle to second), emptyList()).single().second

        assertTrue(kotlin.math.abs(result.longitude - first.longitude) < 0.00001)
    }
}
