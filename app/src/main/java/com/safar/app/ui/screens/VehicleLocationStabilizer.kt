package com.safar.app.ui.screens

import com.safar.app.domain.model.GeoPoint
import com.safar.app.domain.model.Vehicle
import com.safar.app.domain.model.VehicleLocation
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Stabilizes a live vehicle marker for passenger display.
 *
 * Smooths implausible live GPS jumps. Road snapping is intentionally handled
 * by PassengerMapView against the actual rendered road network, because the
 * database route path can be approximate.
 */
internal class VehicleLocationStabilizer(
    private val snapDistanceMeters: Double = 20.0,
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
            vehicle to stabilizeOne(vehicle, rawLocation, routePath)
        }
    }

    private fun stabilizeOne(
        vehicle: Vehicle,
        rawLocation: VehicleLocation,
    ): VehicleLocation {
        val rawPoint = GeoPoint(rawLocation.latitude, rawLocation.longitude)
        val previous = states[vehicle.id]

        val displayPoint = if (previous == null) {
            rawPoint
        } else {
            val elapsedSeconds = (
                (rawLocation.timestampEpochMs - previous.timestampEpochMs) / 1000.0
            ).coerceIn(0.5, 10.0)

            val maxMoveMeters = max(
                25.0,
                maxVehicleSpeedMetersPerSecond * elapsedSeconds + 8.0,
            )
            val distanceToTarget = distanceMeters(previous.point, rawPoint)

            if (distanceToTarget <= maxMoveMeters) {
                rawPoint
            } else {
                moveTowards(previous.point, rawPoint, maxMoveMeters)
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

    private data class Projection(
        val point: GeoPoint,
        val distanceMeters: Double,
    )

    private fun nearestPointOnPolyline(
        point: GeoPoint,
        path: List<GeoPoint>,
    ): Projection? {
        if (path.isEmpty()) return null
        if (path.size == 1) {
            return Projection(path.first(), distanceMeters(point, path.first()))
        }

        var best: Projection?     private fun moveTowards(
        from: GeoPoint,
        to: GeoPoint,
        maxDistanceMeters: Double,
    ): GeoPoint {
        val total = distanceMeters(from, to)
        if (total <= maxDistanceMeters || total <= 0.01) return to

        val ratio = maxDistanceMeters / total
        return GeoPoint(
            latitude = from.latitude + (to.latitude - from.latitude) * ratio,
            longitude = from.longitude + (to.longitude - from.longitude) * ratio,
        )
    }

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)

        val h = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(lat1) * kotlin.math.cos(lat2) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)

        return 2.0 * earthRadius * kotlin.math.asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
