package com.safar.app.ui.screens

import com.safar.app.domain.model.Vehicle
import com.safar.app.domain.model.VehicleLocation
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleLocationStabilizerTest {
    private val vehicle = Vehicle(
        id = "vehicle-1",
        identifier = "TEST",
        routeId = "route-1",
        currentLocation = null,
        active = true,
    )

    @Test
    fun keepsNormalMovement() {
        val stabilizer = VehicleLocationStabilizer()
        val first = VehicleLocation(vehicle.id, 40.0000, 69.0000, 1_000L)
        val second = VehicleLocation(vehicle.id, 40.0001, 69.0000, 2_000L)

        stabilizer.stabilize(listOf(vehicle to first))
        val result = stabilizer.stabilize(listOf(vehicle to second)).single().second

        assertTrue(result.latitude > first.latitude)
    }

    @Test
    fun limitsLargeGpsJump() {
        val stabilizer = VehicleLocationStabilizer()
        val first = VehicleLocation(vehicle.id, 40.0002, 69.0010, 1_000L)
        val second = VehicleLocation(vehicle.id, 40.0002, 69.0025, 2_000L)

        stabilizer.stabilize(listOf(vehicle to first))
        val result = stabilizer.stabilize(listOf(vehicle to second)).single().second

        assertTrue(result.longitude > first.longitude)
        assertTrue(result.longitude < second.longitude)
    }
}
