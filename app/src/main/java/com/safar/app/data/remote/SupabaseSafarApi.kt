package com.safar.app.data.remote

import com.safar.app.domain.model.Driver
import com.safar.app.domain.model.FavoriteVehicle
import com.safar.app.domain.model.GeoPoint
import com.safar.app.domain.model.Route
import com.safar.app.domain.model.User
import com.safar.app.domain.model.UserRole
import com.safar.app.domain.model.Vehicle
import com.safar.app.domain.model.VehicleLocation
import com.safar.app.domain.model.WorkSession
import com.safar.app.domain.model.WorkSessionStatus
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SupabaseSafarApi(
    private val client: SupabaseClient = SupabaseClientProvider.client,
) : SafarApi {

    // Multiple screens/services can call the API concurrently during startup.
    // Serialize anonymous-session creation so simultaneous requests cannot race
    // and create/switch between different anonymous users.
    private val sessionInitializationMutex = Mutex()

    override suspend fun ensureAnonymousSession() {
        sessionInitializationMutex.withLock {
            // Auth session loading is asynchronous in supabase-kt. Wait for it
            // before deciding whether a new anonymous user is required.
            client.auth.awaitInitialization()

            if (client.auth.currentSessionOrNull() == null) {
                client.auth.signInAnonymously()
            }

            check(client.auth.currentSessionOrNull() != null) {
                "Supabase authentication did not produce an active session."
            }
            check(client.auth.currentUserOrNull() != null) {
                "Supabase authentication did not produce an authenticated user."
            }
        }
    }

    override suspend fun getCurrentUser(): User? {
        ensureAnonymousSession()
        val authUser = client.auth.currentUserOrNull() ?: return null
        return client.from("users")
            .select {
                filter { eq("id", authUser.id) }
            }
            .decodeSingleOrNull<UserDto>()
            ?.toDomain()
    }

    override suspend fun signOut() {
        client.auth.signOut()
    }

    override suspend fun updateCurrentUser(name: String, phone: String): User {
        ensureAnonymousSession()
        val authUserId = requireNotNull(client.auth.currentUserOrNull()?.id)
        return client.postgrest.rpc(
            "update_current_user",
            buildJsonObject {
                put("p_name", name)
                put("p_phone", phone)
            },
        ).decodeAs<UserDto>().toDomain().also {
            check(it.id == authUserId) { "Backend returned an unexpected user id." }
        }
    }

    override suspend fun registerPassenger(name: String, phone: String): User {
        ensureAnonymousSession()
        val authUserId = requireNotNull(client.auth.currentUserOrNull()?.id)
        return client.postgrest.rpc(
            "register_passenger",
            buildJsonObject {
                put("p_name", name)
                put("p_phone", phone)
            },
        ).decodeAs<UserDto>().toDomain().also { user ->
            check(user.id == authUserId) {
                "Passenger registration returned an unexpected user id."
            }
            check(user.role == UserRole.PASSENGER) {
                "Passenger registration returned an unexpected role."
            }
        }
    }

    override suspend fun registerDriver(
        name: String,
        phone: String,
        vehicleIdentifier: String,
    ): Driver {
        ensureAnonymousSession()
        val authUserId = requireNotNull(client.auth.currentUserOrNull()?.id)
        return client.postgrest.rpc(
            "register_driver",
            buildJsonObject {
                put("p_name", name)
                put("p_phone", phone)
                put("p_vehicle_identifier", vehicleIdentifier)
            },
        ).decodeSingle<RegistrationResponseDto>().toDriverDomain().also { driver ->
            check(driver.userId == authUserId) {
                "Driver registration returned an unexpected user id."
            }
        }
    }

    override suspend fun assignDriverRoute(routeId: String): Driver {
        ensureAnonymousSession()
        val authUserId = requireNotNull(client.auth.currentUserOrNull()?.id)
        return client.postgrest.rpc(
            "assign_driver_route",
            buildJsonObject { put("p_route_id", routeId) },
        ).decodeAs<DriverDto>().toDomain().also { driver ->
            check(driver.userId == authUserId) {
                "Route assignment returned an unexpected user id."
            }
        }
    }

    override suspend fun getRoutes(): List<Route> {
        ensureAnonymousSession()
        return client.from("routes")
            .select()
            .decodeList<RouteDto>()
            .map { it.toDomain() }
    }

    override suspend fun getRoute(routeId: String): Route? {
        ensureAnonymousSession()
        return client.from("routes")
            .select {
                filter { eq("id", routeId) }
            }
            .decodeSingleOrNull<RouteDto>()
            ?.toDomain()
    }

    override suspend fun getVehiclesForRoute(routeId: String): List<Vehicle> {
        ensureAnonymousSession()
        return client.from("vehicle_current_locations")
            .select {
                filter { eq("route_id", routeId) }
            }
            .decodeList<VehicleWithLocationDto>()
            .map { it.toDomain() }
    }

    override suspend fun getAllVehicles(): List<Vehicle> {
        ensureAnonymousSession()
        return client.from("vehicle_current_locations")
            .select()
            .decodeList<VehicleWithLocationDto>()
            .map { it.toDomain() }
    }

    override suspend fun getActiveVehiclesForRoute(routeId: String): List<Vehicle> {
        ensureAnonymousSession()
        return client.from("vehicle_current_locations")
            .select {
                filter {
                    eq("route_id", routeId)
                    eq("active", true)
                }
            }
            .decodeList<VehicleWithLocationDto>()
            .map { it.toDomain() }
    }

    override suspend fun getFavoriteVehicles(): List<FavoriteVehicle> {
        ensureAnonymousSession()
        return client.from("favorite_vehicles")
            .select()
            .decodeList<FavoriteVehicleDto>()
            .map { it.toDomain() }
    }

    override suspend fun addFavoriteVehicle(vehicleId: String): FavoriteVehicle {
        ensureAnonymousSession()
        return client.postgrest.rpc(
            "add_favorite_vehicle",
            buildJsonObject {
                put("p_vehicle_id", vehicleId)
            },
        ).decodeAs<FavoriteVehicleDto>().toDomain()
    }

    override suspend fun removeFavoriteVehicle(vehicleId: String) {
        ensureAnonymousSession()
        client.postgrest.rpc(
            "remove_favorite_vehicle",
            buildJsonObject {
                put("p_vehicle_id", vehicleId)
            },
        )
    }

    override suspend fun getDriver(): Driver? {
        ensureAnonymousSession()
        return client.from("drivers")
            .select {
                filter { eq("user_id", requireNotNull(client.auth.currentUserOrNull()?.id)) }
            }
            .decodeSingleOrNull<DriverDto>()
            ?.toDomain()
    }

    override suspend fun getCurrentWorkSession(): WorkSession? {
        ensureAnonymousSession()
        val driverId = requireNotNull(client.auth.currentUserOrNull()?.id)

        // get_current_work_session() returns a single PostgreSQL composite row.
        // supabase-kt's decodeSingleOrNull expects a JSON array from a table
        // response, which caused the runtime "Expected start of the array"
        // error on Driver Home. Read the same RLS-protected current state
        // directly from work_sessions instead.
        val active = client.from("work_sessions")
            .select {
                filter {
                    eq("driver_id", driverId)
                    eq("status", "ACTIVE")
                }
            }
            .decodeList<WorkSessionDto>()
            .firstOrNull()

        if (active != null) return active.toDomain()

        return client.from("work_sessions")
            .select {
                filter {
                    eq("driver_id", driverId)
                    eq("status", "PAUSED")
                }
            }
            .decodeList<WorkSessionDto>()
            .firstOrNull()
            ?.toDomain()
    }

    override suspend fun getWorkSessionHistory(startEpochMs: Long?, endEpochMs: Long?): List<WorkSession> {
        ensureAnonymousSession()
        val driverId = requireNotNull(client.auth.currentUserOrNull()?.id)
        return client.from("work_sessions")
            .select {
                filter {
                    eq("driver_id", driverId)
                    startEpochMs?.let { gte("started_at", Instant.ofEpochMilli(it).toString()) }
                    endEpochMs?.let { lte("started_at", Instant.ofEpochMilli(it).toString()) }
                }
            }
            .decodeList<WorkSessionDto>()
            .sortedByDescending { it.startedAt }
            .map { it.toDomain() }
    }

    override suspend fun startWorkSession(): WorkSession {
        ensureAnonymousSession()
        return client.postgrest.rpc("start_work_session")
            .decodeAs<WorkSessionDto>()
            .toDomain()
    }

    override suspend fun pauseWorkSession(sessionId: String): WorkSession {
        ensureAnonymousSession()
        return client.postgrest.rpc(
            "pause_work_session",
            buildJsonObject {
                put("p_session_id", sessionId)
            },
        ).decodeAs<WorkSessionDto>()
            .toDomain()
    }

    override suspend fun resumeWorkSession(sessionId: String): WorkSession {
        ensureAnonymousSession()
        return client.postgrest.rpc(
            "resume_work_session",
            buildJsonObject {
                put("p_session_id", sessionId)
            },
        ).decodeAs<WorkSessionDto>()
            .toDomain()
    }

    override suspend fun finishWorkSession(sessionId: String): WorkSession {
        ensureAnonymousSession()
        return client.postgrest.rpc(
            "finish_work_session",
            buildJsonObject {
                put("p_session_id", sessionId)
            },
        ).decodeAs<WorkSessionDto>()
            .toDomain()
    }

    override suspend fun submitVehicleLocation(
        latitude: Double,
        longitude: Double,
        recordedAtIso: String?,
        headingDegrees: Float?,
    ): VehicleLocation {
        ensureAnonymousSession()
        return client.postgrest.rpc(
            "submit_vehicle_location",
            buildJsonObject {
                put("p_latitude", latitude)
                put("p_longitude", longitude)
                put("p_recorded_at", recordedAtIso ?: Instant.now().toString())
                headingDegrees?.takeIf { it.isFinite() }?.let { put("p_heading_degrees", it.toDouble()) }
            },
        ).decodeAs<VehicleLocationDto>()
            .toDomain()
    }

    private fun parseTimestamp(value: String): Long {
        // PostgREST can return timestamptz with a short UTC offset:
        // "2026-09-26 15:05:24.084+00". java.time's ISO parser expects
        // either Z or an offset such as +00:00, so normalize that form first.
        val normalized = value.trim()
            .replace(' ', 'T')
            .replace(Regex("([+-]\\d{2})$"), "$1:00")
        return runCatching { Instant.parse(normalized).toEpochMilli() }
            .recoverCatching {
                OffsetDateTime.parse(normalized).toInstant().toEpochMilli()
            }
            .getOrElse {
                throw IllegalArgumentException("Unsupported Supabase timestamp: $value", it)
            }
    }

    private fun UserDto.toDomain(): User = User(
        id = id,
        name = name,
        phone = phone,
        role = UserRole.valueOf(role),
    )

    private fun RegistrationResponseDto.toDriverDomain(): Driver = Driver(
        userId = id,
        vehicleId = requireNotNull(vehicleId),
        routeId = routeId,
        verified = verified == true,
    )

    private fun DriverDto.toDomain(): Driver = Driver(
        userId = userId,
        vehicleId = vehicleId,
        routeId = routeId,
        verified = verified,
    )

    private fun RouteDto.toDomain(): Route = Route(
        id = id,
        number = number,
        path = path.map { GeoPoint(it.latitude, it.longitude) },
    )

    private fun VehicleWithLocationDto.toDomain(): Vehicle {
        val location = if (latitude != null && longitude != null && recordedAt != null) {
            VehicleLocation(
                vehicleId = id,
                latitude = latitude,
                longitude = longitude,
                timestampEpochMs = parseTimestamp(recordedAt),
                headingDegrees = headingDegrees,
            )
        } else {
            null
        }

        return Vehicle(
            id = id,
            identifier = identifier,
            routeId = routeId,
            currentLocation = location,
            active = active,
        )
    }

    private fun FavoriteVehicleDto.toDomain(): FavoriteVehicle =
        FavoriteVehicle(userId = userId, vehicleId = vehicleId)

    private fun WorkSessionDto.toDomain(): WorkSession = WorkSession(
        id = id,
        driverId = driverId,
        startTimeEpochMs = startedAt?.let(::parseTimestamp),
        pauseTimeEpochMs = pausedAt?.let(::parseTimestamp),
        endTimeEpochMs = finishedAt?.let(::parseTimestamp),
        durationSeconds = durationSeconds,
        totalPausedSeconds = totalPausedSeconds,
        distanceMeters = distanceMeters,
        status = when (status) {
            "ACTIVE" -> WorkSessionStatus.ACTIVE
            "PAUSED" -> WorkSessionStatus.PAUSED
            "FINISHED" -> WorkSessionStatus.COMPLETED
            else -> WorkSessionStatus.NOT_STARTED
        },
    )

    private fun VehicleLocationDto.toDomain(): VehicleLocation = VehicleLocation(
        vehicleId = vehicleId,
        latitude = latitude,
        longitude = longitude,
        timestampEpochMs = parseTimestamp(recordedAt),
        headingDegrees = headingDegrees,
    )
}
