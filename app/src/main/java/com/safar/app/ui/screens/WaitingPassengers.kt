package com.safar.app.ui.screens

import com.safar.app.domain.model.GeoPoint
import com.safar.app.domain.model.PassengerLocation
import com.safar.app.domain.model.Vehicle

/**
 * Decides which waiting passengers belong to this driver.
 *
 * A passenger waits for the nearest active minibus on the route - the same
 * straight-line rule the passenger app uses for its "nearest minibus" card
 * ([NearestVehicleCalculator]). A driver therefore counts only passengers for
 * whom no other active vehicle is closer. The result is sorted from the
 * nearest passenger to the farthest one, so the first item is the passenger
 * the "waiting" chip focuses on.
 */
internal object WaitingPassengers {

    fun waitingFor(
        driverPoint: GeoPoint?,
        ownVehicleId: String?,
        vehicles: List<Vehicle>,
        passengers: List<PassengerLocation>,
    ): List<PassengerLocation> {
        val origin = driverPoint?.takeIf { it.isValid() } ?: return emptyList()
        // The server copy of this driver's own vehicle lags behind the phone GPS
        // and must never compete with the driver for passengers.
        val competitors = vehicles.mapNotNull { vehicle ->
            if (ownVehicleId != null && vehicle.id == ownVehicleId) return@mapNotNull null
            val location = vehicle.currentLocation ?: return@mapNotNull null
            GeoPoint(location.latitude, location.longitude).takeIf { it.isValid() }
        }
        return passengers
            .mapNotNull { passenger ->
                val point = GeoPoint(passenger.latitude, passenger.longitude)
                if (!point.isValid()) return@mapNotNull null
                val ownDistance = NearestVehicleCalculator.distanceMeters(origin, point)
                val closerVehicleExists = competitors.any { other ->
                    NearestVehicleCalculator.distanceMeters(other, point) < ownDistance
                }
                if (closerVehicleExists) null else passenger to ownDistance
            }
            .sortedBy { it.second }
            .map { it.first }
    }

    private fun GeoPoint.isValid(): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
}
