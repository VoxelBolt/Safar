package com.safar.app.data.remote

import com.safar.app.domain.model.Driver
import com.safar.app.domain.model.DriverApplication
import com.safar.app.domain.model.DriverLocationFix
import com.safar.app.domain.model.FavoriteVehicle
import com.safar.app.domain.model.GeoPoint
import com.safar.app.domain.model.Route
import com.safar.app.domain.model.User
import com.safar.app.domain.model.UserRole
import com.safar.app.domain.model.Vehicle
import com.safar.app.domain.model.VehicleLocation
import com.safar.app.domain.model.WorkSession
import com.safar.app.domain.model.WorkSessionSummary
import com.safar.app.domain.model.WorkSessionStatus
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime
import io.github.jan.supabase.SupabaseClient

class SupabaseSafarApi(
    private val client: SupabaseClient = SupabaseClientProvider.client,
) : SafarApi {

    override suspend fun ensureAnonymousSession() {
        SupabaseClientProvider.ensureAnonymousSession(client)
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

    override suspend fun loginPassenger(name: String, phone: String): User {
        ensureAnonymousSession()
        val authUserId = requireNotNull(client.auth.currentUserOrNull()?.id)
        return client.postgrest.rpc(
            "login_passenger",
            buildJsonObject {
                put("p_name", name)
                put("p_phone", phone)
            },
        ).decodeAs<UserDto>().toDomain().also { user ->
            check(user.id == authUserId) { "Passenger login returned an unexpected user id." }
            check(user.role == UserRole.PASSENGER) { "Passenger login returned an unexpected role." }
        }
    }

    override suspend fun loginDriver(name: String, phone: String, vehicleIdentifier: String): User {
        ensureAnonymousSession()
        val authUserId = requireNotNull(client.auth.currentUserOrNull()?.id)
        return client.postgrest.rpc(
            "login_driver",
            buildJsonObject {
                put("p_name", name)
                put("p_phone", phone)
                put("p_vehicle_identifier", vehicleIdentifier)
            },
        ).decodeAs<UserDto>().toDomain().also { user ->
            check(user.id == authUserId) { "Driver login returned an unexpected user id." }
            check(user.role == UserRole.DRIVER) { "Driver login returned an unexpected role." }
        }
    }

    override suspend fun registerDriver(
        name: String,
        phone: String,
        vehicleIdentifier: String,
        routeId: String,
    ): DriverApplication {
        ensureAnonymousSession()
        val authUserId = requireNotNull(client.auth.currentUserOrNull()?.id)
        return client.postgrest.rpc(
            "register_driver",
            buildJsonObject {
                put("p_name", name)
                put("p_phone", phone)
                put("p_vehicle_identifier", vehicleIdentifier)
                put("p_route_id", routeId)
            },
        ).decodeList<DriverApplicationDto>().singleOrNull()
            ?.toDriverApplicationDomain()
            ?.also { application ->
            check(application.userId == authUserId) {
                "Driver registration returned an unexpected user id."
            }
        } ?: error("Driver registration returned no unique application.")
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
        return client.postgrest.rpc(
            "get_vehicles_for_route",
            buildJsonObject { put("p_route_id", routeId) },
        )
            .decodeList<VehicleWithLocationDto>()
            .map { it.toDomain() }
    }

    override suspend fun getAllVehicles(offset: Int, limit: Int): List<Vehicle> {
        require(offset >= 0) { "Vehicle offset cannot be negative." }
        require(limit in 1..MAX_VEHICLE_PAGE_SIZE) { "Vehicle page size must be between 1 and $MAX_VEHICLE_PAGE_SIZE." }
        ensureAnonymousSession()
        return client.from("vehicle_current_locations")
            .select(Columns.list("id", "identifier", "route_id", "active", "latitude", "longitude", "recorded_at", "heading_degrees")) {
                order("id", Order.ASCENDING)
                range(offset.toLong(), (offset + limit - 1).toLong())
            }
            .decodeList<VehicleWithLocationDto>()
            .map { it.toDomain() }
    }

    override suspend fun getVehiclesByIds(vehicleIds: List<String>): List<Vehicle> {
        if (vehicleIds.isEmpty()) return emptyList()
        require(vehicleIds.size <= MAX_VEHICLE_ID_PAGE_SIZE) { "Vehicle lookup page exceeds $MAX_VEHICLE_ID_PAGE_SIZE IDs." }
        ensureAnonymousSession()
        return client.from("vehicle_current_locations")
            .select(Columns.list("id", "identifier", "route_id", "active", "latitude", "longitude", "recorded_at", "heading_degrees")) {
                filter { isIn("id", vehicleIds.map { it as Any }) }
                order("id", Order.ASCENDING)
            }
            .decodeList<VehicleWithLocationDto>()
            .map { it.toDomain() }
    }

    override suspend fun getActiveVehiclesForRoute(routeId: String): List<Vehicle> {
        return getVehiclesForRoute(routeId).filter { it.active && it.currentLocation != null }
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

    override suspend fun getDriverApplication(): DriverApplication? {
        ensureAnonymousSession()
        val authUserId = requireNotNull(client.auth.currentUserOrNull()?.id)
        return client.postgrest.rpc("get_driver_application")
            .decodeList<DriverApplicationDto>()
            .singleOrNull()
            ?.toDriverApplicationDomain()
            ?.also { application ->
                check(application.userId == authUserId) {
                    "Driver application returned an unexpected user id."
                }
            }
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

    override suspend fun getWorkSessionHistory(
        startEpochMs: Long?,
        endEpochMs: Long?,
        offset: Int,
        limit: Int,
    ): List<WorkSession> {
        require(offset >= 0) { "History offset cannot be negative." }
        require(limit in 1..MAX_HISTORY_PAGE_SIZE) { "History page size must be between 1 and $MAX_HISTORY_PAGE_SIZE." }
        ensureAnonymousSession()
        val driverId = requireNotNull(client.auth.currentUserOrNull()?.id)
        return client.from("work_sessions")
            .select(Columns.list(
                "id", "driver_id", "started_at", "paused_at", "finished_at", "duration_seconds",
                "total_paused_seconds", "distance_meters", "status",
            )) {
                filter {
                    eq("driver_id", driverId)
                    startEpochMs?.let { gte("started_at", Instant.ofEpochMilli(it).toString()) }
                    endEpochMs?.let { lte("started_at", Instant.ofEpochMilli(it).toString()) }
                }
                order("started_at", Order.DESCENDING)
                order("id", Order.DESCENDING)
                range(offset.toLong(), (offset + limit - 1).toLong())
            }
            .decodeList<WorkSessionDto>()
            .map { it.toDomain() }
    }

    override suspend fun getWorkSessionSummary(startEpochMs: Long?, endEpochMs: Long?): WorkSessionSummary {
        ensureAnonymousSession()
        return client.postgrest.rpc(
            "get_work_session_summary",
            buildJsonObject {
                startEpochMs?.let { put("p_start_at", Instant.ofEpochMilli(it).toString()) }
                endEpochMs?.let { put("p_end_at", Instant.ofEpochMilli(it).toString()) }
            },
        ).decodeList<WorkSessionSummaryDto>().singleOrNull()?.let { summary ->
            WorkSessionSummary(
                sessionCount = summary.sessionCount.coerceAtLeast(0L),
                workSeconds = summary.workSeconds.coerceAtLeast(0L),
                distanceMeters = summary.distanceMeters.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0,
            )
        } ?: error("Statistics summary returned no unique result.")
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

    override suspend fun submitVehicleLocationBatch(locations: List<DriverLocationFix>) {
        require(locations.isNotEmpty() && locations.size <= MAX_BATCHED_LOCATION_FIXES) {
            "GPS batch must contain between 1 and $MAX_BATCHED_LOCATION_FIXES fixes."
        }
        require(locations.all { fix ->
            fix.latitude.isFinite() && fix.latitude in -90.0..90.0 &&
                fix.longitude.isFinite() && fix.longitude in -180.0..180.0 &&
                fix.timestampEpochMs > 0L &&
                (fix.headingDegrees == null || fix.headingDegrees.isFinite() && fix.headingDegrees >= 0f && fix.headingDegrees < 360f)
        }) { "GPS batch contains an invalid fix." }
        require(locations.zipWithNext().all { (first, second) -> second.timestampEpochMs > first.timestampEpochMs }) {
            "GPS batch must be in strictly increasing timestamp order."
        }

        ensureAnonymousSession()
        val payload = buildJsonArray {
            locations.forEach { fix ->
                add(
                    buildJsonObject {
                        put("latitude", fix.latitude)
                        put("longitude", fix.longitude)
                        put("recorded_at", Instant.ofEpochMilli(fix.timestampEpochMs).toString())
                        fix.headingDegrees?.let { put("heading_degrees", it.toDouble()) }
                    },
                )
            }
        }
        client.postgrest.rpc(
            "submit_vehicle_location_batch",
            buildJsonObject { put("p_locations", payload) },
        )
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

    private fun DriverApplicationDto.toDriverApplicationDomain(): DriverApplication = DriverApplication(
        userId = userId,
        routeId = routeId,
        vehicleIdentifier = vehicleIdentifier,
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

    private companion object {
        const val MAX_BATCHED_LOCATION_FIXES = 100
        const val MAX_HISTORY_PAGE_SIZE = 100
        const val MAX_VEHICLE_PAGE_SIZE = 100
        const val MAX_VEHICLE_ID_PAGE_SIZE = 50
    }
}
