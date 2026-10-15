package com.safar.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GeoPointDto(
    val latitude: Double,
    val longitude: Double,
)

@Serializable
data class UserDto(
    val id: String,
    val name: String,
    val phone: String,
    val role: String,
)

@Serializable
data class DriverDto(
    @SerialName("user_id") val userId: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("route_id") val routeId: String? = null,
    val verified: Boolean,
)

@Serializable
data class RouteDto(
    val id: String,
    val number: String,
    val path: List<GeoPointDto> = emptyList(),
)

@Serializable
data class VehicleWithLocationDto(
    val id: String,
    val identifier: String,
    @SerialName("route_id") val routeId: String? = null,
    val active: Boolean,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("recorded_at") val recordedAt: String? = null,
    @SerialName("heading_degrees") val headingDegrees: Float? = null,
)

@Serializable
data class WorkSessionDto(
    val id: String,
    @SerialName("driver_id") val driverId: String,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("paused_at") val pausedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Long = 0L,
    @SerialName("total_paused_seconds") val totalPausedSeconds: Long = 0L,
    @SerialName("distance_meters") val distanceMeters: Double = 0.0,
    val status: String,
)

@Serializable
data class FavoriteVehicleDto(
    @SerialName("user_id") val userId: String,
    @SerialName("vehicle_id") val vehicleId: String,
)

@Serializable
data class RegistrationResponseDto(
    val id: String,
    val name: String,
    val phone: String,
    val role: String,
    @SerialName("vehicle_id") val vehicleId: String? = null,
    @SerialName("route_id") val routeId: String? = null,
    val verified: Boolean? = null,
)

@Serializable
data class VehicleLocationParams(
    val latitude: Double,
    val longitude: Double,
    @SerialName("recorded_at") val recordedAt: String? = null,
)


@Serializable
data class VehicleLocationDto(
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("route_id") val routeId: String? = null,
    val latitude: Double,
    val longitude: Double,
    @SerialName("recorded_at") val recordedAt: String,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("heading_degrees") val headingDegrees: Float? = null,
)

@Serializable
data class EmptyParams(
    val placeholder: String = "",
)
