package com.safar.app.ui.screens

import com.safar.app.domain.model.GeoPoint
import com.safar.app.domain.model.Vehicle
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class NearestVehicleResult(
    val vehicle: Vehicle,
    val distanceMeters: Double,
    val etaMinutes: Int,
)

object NearestVehicleCalculator {
    const val AVERAGE_VEHICLE_SPEED_KMH = 25.0
    fun findNearest(
        passenger: GeoPoint,
        vehicles: List<Vehicle>,
    ): NearestVehicleResult? {
        val nearest = vehicles.mapNotNull { vehicle ->
            val location = vehicle.currentLocation ?: return@mapNotNull null
            if (!passenger.isValid() || !location.latitude.isFinite() || !location.longitude.isFinite()) return@mapNotNull null
            if (location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return@mapNotNull null
            val distance = distanceMeters(passenger, GeoPoint(location.latitude, location.longitude))
            // A vehicle must not disappear from the "nearest" action just because
            // the passenger and driver test devices currently have different GPS
            // locations. The nearest active vehicle is valid at any distance.
            vehicle to distance
        }.minByOrNull { it.second } ?: return null

        return NearestVehicleResult(
            vehicle = nearest.first,
            distanceMeters = nearest.second,
            etaMinutes = etaMinutes(nearest.second),
        )
    }

    private fun GeoPoint.isValid(): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0

    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)

        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)

        return 2.0 * earthRadius * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun etaMinutes(distanceMeters: Double): Int {
        val metersPerMinute = AVERAGE_VEHICLE_SPEED_KMH * 1000.0 / 60.0
        return ceil(distanceMeters.coerceAtLeast(0.0) / metersPerMinute)
            .toInt()
            .coerceAtLeast(1)
    }
}
