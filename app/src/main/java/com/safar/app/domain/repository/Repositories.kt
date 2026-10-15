package com.safar.app.domain.repository

import com.safar.app.domain.model.Driver
import com.safar.app.domain.model.FavoriteVehicle
import com.safar.app.domain.model.Route
import com.safar.app.domain.model.User
import com.safar.app.domain.model.Vehicle
import com.safar.app.domain.model.VehicleLocation
import com.safar.app.domain.model.WorkSession

interface UserRepository {
    suspend fun getCurrentUser(): User?
    suspend fun updateCurrentUser(name: String, phone: String): User
    suspend fun signOut()
}

interface RouteRepository {
    suspend fun getRoutes(): List<Route>
    suspend fun getRoute(routeId: String): Route?
}

interface VehicleRepository {
    suspend fun getVehiclesForRoute(routeId: String): List<Vehicle>
    suspend fun getAllVehicles(): List<Vehicle>
    suspend fun getActiveVehiclesForRoute(routeId: String): List<Vehicle>
}

interface DriverRepository {
    suspend fun getDriver(): Driver?
    suspend fun registerDriver(name: String, phone: String, vehicleIdentifier: String): Driver
    suspend fun assignRoute(routeId: String): Driver
}

interface RegistrationRepository {
    suspend fun ensureAnonymousSession()
    suspend fun registerPassenger(name: String, phone: String): User
}

interface FavoriteRepository {
    suspend fun getFavorites(): List<FavoriteVehicle>
    suspend fun addFavorite(vehicleId: String): FavoriteVehicle
    suspend fun removeFavorite(vehicleId: String)
}

interface WorkSessionRepository {
    suspend fun getCurrent(): WorkSession?
    suspend fun start(): WorkSession
    suspend fun pause(sessionId: String): WorkSession
    suspend fun resume(sessionId: String): WorkSession
    suspend fun finish(sessionId: String): WorkSession
    suspend fun getHistory(startEpochMs: Long? = null, endEpochMs: Long? = null): List<WorkSession>
}

interface DriverLocationRepository {
    suspend fun submitLocation(
        latitude: Double,
        longitude: Double,
        recordedAtIso: String? = null,
        headingDegrees: Float? = null,
    ): VehicleLocation
}
