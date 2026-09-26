package com.safar.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsTest {
    @Test
    fun passengerLocation_preservesRouteAndCoordinates() {
        val location = PassengerLocation(
            passengerId = "passenger-1",
            routeId = "route-15",
            latitude = 40.2801,
            longitude = 69.6201,
            timestampEpochMs = 10L,
        )

        assertEquals("passenger-1", location.passengerId)
        assertEquals("route-15", location.routeId)
        assertEquals(40.2801, location.latitude, 0.0)
        assertEquals(69.6201, location.longitude, 0.0)
    }

    @Test
    fun vehicleLocation_preservesCoordinates() {
        val location = VehicleLocation(
            vehicleId = "vehicle-1",
            latitude = 38.5598,
            longitude = 68.7870,
            timestampEpochMs = 1L
        )

        assertEquals(38.5598, location.latitude, 0.0)
        assertEquals(68.7870, location.longitude, 0.0)
    }
}
