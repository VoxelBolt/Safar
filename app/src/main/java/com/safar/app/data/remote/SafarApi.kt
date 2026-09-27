package com.safar.app.data.remote

import com.safar.app.domain.model.Driver
import com.safar.app.domain.model.FavoriteVehicle
import com.safar.app.domain.model.Route
import com.safar.app.domain.model.User
import com.safar.app.domain.model.Vehicle
import com.safar.app.domain.model.VehicleLocation
import com.safar.app.domain.model.WorkSession

/**
 * Backend boundary used by repositories.
 *
 * Read operations use Supabase Data API. Mutating operations that need
 * server-side business rules use Postgres RPC functions created by the Stage 03
 * migration. No privileged/service-role key is used by the Android client.
 */
interface SafarApi {
    suspend fun ensureAnonymousSession()
    suspend fun getCurrentUser(): User?
    suspend fun updateCurrentUser(name: String, phone: String): User

    suspend fun registerPassenger(name: String, phone: String): User
    suspend fun registerDriver(name: String, phone: String, vehicleIdentifier: String): Driver
    suspend fun assignDriverRoute(routeId: String): Driver

    suspend fun getRoutes(): List<Route>
    suspend fun getRoute(routeId: String): Route?
    suspend fun getVehiclesForRoute(routeId: String): List<Vehicle>
    suspend fun getAllVehicles(): List<Vehicle>
    suspend fun getActiveVehiclesForRoute(routeId: String): List<Vehicle>

    suspend fun getFavoriteVehicles(): List<FavoriteVehicle>
    suspend fun addFavoriteVehicle(vehicleId: String): FavoriteVehicle
    suspend fun removeFavoriteVehicle(vehicleId: String)

    suspend fun getDriver(): Driver?
    suspend fun getCurrentWorkSession(): WorkSession?
    suspend fun startWorkSession(): WorkSession
    suspend fun pauseWorkSession(sessionId: String): WorkSession
    suspend fun resumeWorkSession(sessionId: String): WorkSession
    suspend fun finishWorkSession(sessionId: String): WorkSession

    suspend fun submitVehicleLocation(
        latitude: Double,
        longitude: Double,
        recordedAtIso: String? = null,
    ): VehicleLocation
}
