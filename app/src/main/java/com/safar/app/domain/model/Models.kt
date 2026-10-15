package com.safar.app.domain.model

enum class UserRole {
    PASSENGER,
    DRIVER
}

enum class WorkSessionStatus {
    NOT_STARTED,
    ACTIVE,
    PAUSED,
    COMPLETED
}

data class User(
    val id: String,
    val name: String,
    val phone: String,
    val role: UserRole
)

data class Driver(
    val userId: String,
    val vehicleId: String,
    val routeId: String?,
    val verified: Boolean
)

data class Vehicle(
    val id: String,
    val identifier: String,
    val routeId: String?,
    val currentLocation: VehicleLocation?,
    val active: Boolean
)

data class Route(
    val id: String,
    val number: String,
    val path: List<GeoPoint> = emptyList()
)

data class GeoPoint(
    val latitude: Double,
    val longitude: Double
)

/**
 * Named VehicleLocation instead of Android's Location class so the domain
 * layer stays platform-neutral and unambiguous.
 */
data class VehicleLocation(
    val vehicleId: String,
    val latitude: Double,
    val longitude: Double,
    val timestampEpochMs: Long,
    val headingDegrees: Float? = null
)

/** Current passenger location published only while a passenger has a route selected. */
data class PassengerLocation(
    val passengerId: String,
    val routeId: String,
    val latitude: Double,
    val longitude: Double,
    val timestampEpochMs: Long,
)

data class WorkSession(
    val id: String,
    val driverId: String,
    val startTimeEpochMs: Long?,
    val pauseTimeEpochMs: Long?,
    val endTimeEpochMs: Long?,
    val durationSeconds: Long,
    val totalPausedSeconds: Long,
    val distanceMeters: Double,
    val status: WorkSessionStatus
)

data class FavoriteVehicle(
    val userId: String,
    val vehicleId: String
)
