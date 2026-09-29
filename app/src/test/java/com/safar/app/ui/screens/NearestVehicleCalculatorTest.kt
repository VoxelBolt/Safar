package com.safar.app.ui.screens

import com.safar.app.domain.model.GeoPoint
import com.safar.app.domain.model.Vehicle
import com.safar.app.domain.model.VehicleLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NearestVehicleCalculatorTest {

    @Test
    fun findNearest_selectsTheClosestVehicle() {
        val passenger = GeoPoint(40.0, 69.0)
        val near = Vehicle(
            id = "near",
            identifier = "A1",
            routeId = "route-1",
            currentLocation = VehicleLocation("near", 40.001, 69.0, 1L),
            active = true,
        )
        val far = Vehicle(
            id = "far",
            identifier = "A2",
            routeId = "route-2",
            currentLocation = VehicleLocation("far", 40.01, 69.0, 1L),
            active = true,
        )

        val result = NearestVehicleCalculator.findNearest(passenger, listOf(far, near))

        assertNotNull(result)
        assertEquals("near", result?.vehicle?.id)
    }

    @Test
    fun findNearest_doesNotDropTheOnlyVehicleWhenItIsFarAway() {
        val passenger = GeoPoint(32.7767, -79.9311)
        val vehicle = Vehicle(
            id = "far",
            identifier = "hshus",
            routeId = "route-1",
            currentLocation = VehicleLocation("far", 40.2732408, 69.6420203, 1L),
            active = true,
        )

        val result = NearestVehicleCalculator.findNearest(passenger, listOf(vehicle))

        assertNotNull(result)
        assertEquals("far", result?.vehicle?.id)
    }

    @Test
    fun etaMinutes_roundsUpAndHasMinimumOfOne() {
        assertEquals(3, NearestVehicleCalculator.etaMinutes(1_000.0))
        assertEquals(1, NearestVehicleCalculator.etaMinutes(0.0))
    }
}
