package com.safar.app.ui.screens

import com.safar.app.domain.model.GeoPoint
import com.safar.app.domain.model.PassengerLocation
import com.safar.app.domain.model.Vehicle
import com.safar.app.domain.model.VehicleLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaitingPassengersTest {

    private val driver = GeoPoint(38.560, 68.770)

    @Test
    fun waitingFor_countsOnlyPassengersForWhomThisDriverIsNearest() {
        val other = vehicle("other", 38.600, 68.770)
        val nearMe = passenger("near-me", 38.561, 68.770)
        val nearOther = passenger("near-other", 38.599, 68.770)

        val result = WaitingPassengers.waitingFor(driver, "mine", listOf(other), listOf(nearOther, nearMe))

        assertEquals(listOf("near-me"), result.map { it.passengerId })
    }

    @Test
    fun waitingFor_sortsPassengersFromNearestToFarthest() {
        val far = passenger("far", 38.570, 68.770)
        val near = passenger("near", 38.561, 68.770)

        val result = WaitingPassengers.waitingFor(driver, "mine", emptyList(), listOf(far, near))

        assertEquals(listOf("near", "far"), result.map { it.passengerId })
    }

    @Test
    fun waitingFor_ignoresTheDriversOwnVehicleMarker() {
        // The server copy of this driver's own vehicle lags behind the phone GPS
        // and must not take passengers away from the driver.
        val ownServerCopy = vehicle("mine", 38.5655, 68.770)
        val waiting = passenger("waiting", 38.566, 68.770)

        val result = WaitingPassengers.waitingFor(driver, "mine", listOf(ownServerCopy), listOf(waiting))

        assertEquals(listOf("waiting"), result.map { it.passengerId })
    }

    @Test
    fun waitingFor_returnsNothingWithoutDriverPosition() {
        val result = WaitingPassengers.waitingFor(
            driverPoint = null,
            ownVehicleId = "mine",
            vehicles = emptyList(),
            passengers = listOf(passenger("waiting", 38.561, 68.770)),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun waitingFor_skipsVehiclesWithoutLocation() {
        val noLocation = Vehicle(
            id = "other",
            identifier = "B2",
            routeId = "route",
            currentLocation = null,
            active = true,
        )
        val waiting = passenger("waiting", 38.561, 68.770)

        val result = WaitingPassengers.waitingFor(driver, "mine", listOf(noLocation), listOf(waiting))

        assertEquals(listOf("waiting"), result.map { it.passengerId })
    }

    private fun vehicle(id: String, latitude: Double, longitude: Double) = Vehicle(
        id = id,
        identifier = id.uppercase(),
        routeId = "route",
        currentLocation = VehicleLocation(id, latitude, longitude, 1L),
        active = true,
    )

    private fun passenger(id: String, latitude: Double, longitude: Double) = PassengerLocation(
        passengerId = id,
        routeId = "route",
        latitude = latitude,
        longitude = longitude,
        timestampEpochMs = 1L,
    )
}
