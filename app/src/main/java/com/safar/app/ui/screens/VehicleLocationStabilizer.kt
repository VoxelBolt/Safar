package com.safar.app.ui.screens

import com.safar.app.domain.model.GeoPoint
import com.safar.app.domain.model.Vehicle
import com.safar.app.domain.model.VehicleLocation
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Stabilizes live vehicle GPS positions for passenger display.
 *
 * This class only limits physically implausible jumps between fresh GPS
 * samples. PassengerMapView renders the resulting live GPS position directly;
 * no route-snap or fixed 20-meter correction is applied.
 */
internal class VehicleLocationStabilizer(
    private val maxVehicleSpeedMetersPerSecond: Double = 32.0,
) {
    private data class State(
        val point: GeoPoint,
        val timestampEpochMs: Long,
    )

    private val states = mutableMapOf<String, State>()

    fun stabilize(
        locations: List<Pair<Vehicle, VehicleLocation>>,
    ): List<Pair<Vehicle, VehicleLocation>> {
        val activeIds = locations.mapTo(mutableSetOf()) { it.first.id }

        states.keys.toList()
            .filter { it !in activeIds }
            .forEach(states::remove)

        return locations.map { (vehicle, rawLocation) ->
            vehicle to stabilizeOne(vehicle, rawLocation)
        }
    }

    private fun stabilizeOne(
        vehicle: Vehicle,
        rawLocation: VehicleLocation,
    ): VehicleLocation {
        val rawPoint = GeoPoint(
            latitude = rawLocation.latitude,
            longitude = rawLocation.longitude,
        )

        val previous = states[vehicle.id]

        val displayPoint = if (previous == null) {
            rawPoint
        } else {
            val elapsedSeconds = (
                (rawLocation.timestampEpochMs - previous.timestampEpochMs) / 1000.0
            ).coerceIn(0.5, 10.0)

            // Allow normal movement, but prevent one bad GPS update from
            // teleporting the marker an implausibly large distance.
            val maxMoveMeters = max(
                25.0,
                maxVehicleSpeedMetersPerSecond * elapsedSeconds + 8.0,
            )

            val distanceToTarget = distanceMeters(previous.point, rawPoint)

            if (distanceToTarget <= maxMoveMeters) {
                rawPoint
            } else {
                moveTowards(
                    from = previous.point,
                    to = rawPoint,
                    maxDistanceMeters = maxMoveMeters,
                )
            }
        }

        states[vehicle.id] = State(
            point = displayPoint,
            timestampEpochMs = rawLocation.timestampEpochMs,
        )

        return rawLocation.copy(
            latitude = displayPoint.latitude,
            longitude = displayPoint.longitude,
        )
    }

    private fun moveTowards(
        from: GeoPoint,
        to: GeoPoint,
        maxDistanceMeters: Double,
    ): GeoPoint {
        val totalDistance = distanceMeters(from, to)

        if (totalDistance <= maxDistanceMeters || totalDistance <= 0.01) {
            return to
        }

        val ratio = maxDistanceMeters / totalDistance

        return GeoPoint(
            latitude = from.latitude +
                (to.latitude - from.latitude) * ratio,
            longitude = from.longitude +
                (to.longitude - from.longitude) * ratio,
        )
    }

    private fun distanceMeters(
        a: GeoPoint,
        b: GeoPoint,
    ): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)

        val h =
            kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(lat1) * kotlin.math.cos(lat2) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)

        return 2.0 * earthRadius * kotlin.math.asin(
            sqrt(h.coerceIn(0.0, 1.0))
        )
    }
}
