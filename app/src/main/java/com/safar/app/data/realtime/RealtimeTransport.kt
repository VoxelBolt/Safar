package com.safar.app.data.realtime

import android.os.SystemClock
import android.util.Log
import com.safar.app.data.remote.SupabaseClientProvider
import com.safar.app.domain.model.PassengerLocation
import com.safar.app.domain.model.VehicleLocation
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime

data class RealtimeVehicleEvent(val vehicleId: String, val routeId: String, val location: VehicleLocation?)
data class RealtimePassengerEvent(val passengerId: String, val routeId: String, val location: PassengerLocation?)
data class RealtimeConnectionState(val routeId: String? = null, val subscribed: Boolean = false, val epoch: Long = 0L)

@Serializable
private data class VehicleLocationBroadcast(
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("route_id") val routeId: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("recorded_at") val recordedAt: String,
    @SerialName("heading_degrees") val headingDegrees: Float? = null,
)

@Serializable
private data class VehicleLocationRemovedBroadcast(
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("route_id") val routeId: String,
)

@Serializable
private data class PassengerLocationBroadcast(
    @SerialName("passenger_id") val passengerId: String,
    @SerialName("route_id") val routeId: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("recorded_at") val recordedAt: String,
)

@Serializable
private data class PassengerLocationRemovedBroadcast(
    @SerialName("passenger_id") val passengerId: String,
    @SerialName("route_id") val routeId: String,
)

@Serializable
private data class PassengerLocationSnapshotDto(
    @SerialName("passenger_id") val passengerId: String,
    @SerialName("route_id") val routeId: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("recorded_at") val recordedAt: String,
)

class SupabaseRealtimeTransport(
    private val client: SupabaseClient = SupabaseClientProvider.client,
) : RealtimeTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectMutex = Mutex()
    private val _vehicleEvents = MutableSharedFlow<RealtimeVehicleEvent>(replay = 0, extraBufferCapacity = 64)
    private val _passengerEvents = MutableSharedFlow<RealtimePassengerEvent>(replay = 0, extraBufferCapacity = 128)
    private val _connectionState = MutableStateFlow(RealtimeConnectionState())
    override val vehicleEvents: Flow<RealtimeVehicleEvent> = _vehicleEvents.asSharedFlow()
    override val passengerEvents: Flow<RealtimePassengerEvent> = _passengerEvents.asSharedFlow()
    override val connectionState: StateFlow<RealtimeConnectionState> = _connectionState.asStateFlow()

    private var channelJob: Job? = null
    private var vehicleChannel: io.github.jan.supabase.realtime.RealtimeChannel? = null
    private var passengerChannel: io.github.jan.supabase.realtime.RealtimeChannel? = null
    private var connectedRouteId: String? = null
    private var connectedPassengerFeed = false
    @Volatile private var connectionLifecycleGeneration = 0L

    override suspend fun connect(routeId: String, receivePassengerLocations: Boolean) = connectMutex.withLock {
        synchronized(this) { connectionLifecycleGeneration += 1L }
        if (connectedRouteId == routeId && connectedPassengerFeed == receivePassengerLocations &&
            channelJob?.isActive == true && _connectionState.value.subscribed
        ) {
            return@withLock
        }

        closeChannels()
        SupabaseClientProvider.ensureAnonymousSession(client)
        val accessToken = client.auth.currentAccessTokenOrNull()
            ?: error("Supabase access token is missing for realtime authorization.")
        client.realtime.setAuth(accessToken)
        client.realtime.connect()

        val vehicleTopic = "safar:vehicle-location:route:$routeId"
        val newVehicleChannel = client.channel(vehicleTopic) { isPrivate = true }
        val newPassengerChannel = if (receivePassengerLocations) {
            client.channel("safar:passenger-location:route:$routeId") { isPrivate = true }
        } else null

        val updates = newVehicleChannel.broadcastFlow<VehicleLocationBroadcast>(event = "vehicle_location")
        val removals = newVehicleChannel.broadcastFlow<VehicleLocationRemovedBroadcast>(event = "vehicle_location_removed")
        val passengerUpdates = newPassengerChannel?.broadcastFlow<PassengerLocationBroadcast>(event = "passenger_location")
        val passengerRemovals = newPassengerChannel?.broadcastFlow<PassengerLocationRemovedBroadcast>(event = "passenger_location_removed")

        _connectionState.value = RealtimeConnectionState(routeId = routeId)
        val collectors = scope.launch {
            launch {
                val passengerStatus = newPassengerChannel?.status ?: flowOf(
                    io.github.jan.supabase.realtime.RealtimeChannel.Status.SUBSCRIBED,
                )
                combine(newVehicleChannel.status, passengerStatus) { vehicleStatus, passengerChannelStatus ->
                    vehicleStatus == io.github.jan.supabase.realtime.RealtimeChannel.Status.SUBSCRIBED &&
                        passengerChannelStatus == io.github.jan.supabase.realtime.RealtimeChannel.Status.SUBSCRIBED
                }.distinctUntilChanged().collect { subscribed ->
                    val previous = _connectionState.value
                    if (previous.routeId != routeId) return@collect
                    _connectionState.value = previous.copy(
                        subscribed = subscribed,
                        epoch = if (subscribed && !previous.subscribed) previous.epoch + 1L else previous.epoch,
                    )
                }
            }
            launch {
                collectSafely("vehicle_location", updates) { message ->
                    if (message.routeId != routeId || message.vehicleId.isBlank() ||
                        !validCoordinates(message.latitude, message.longitude)
                    ) return@collectSafely
                    val timestamp = parseFreshTimestamp(message.recordedAt) ?: return@collectSafely
                    _vehicleEvents.emit(
                        RealtimeVehicleEvent(
                            message.vehicleId,
                            message.routeId,
                            VehicleLocation(message.vehicleId, message.latitude, message.longitude, timestamp, message.headingDegrees),
                        ),
                    )
                }
            }
            launch {
                collectSafely("vehicle_location_removed", removals) { message ->
                    if (message.routeId == routeId && message.vehicleId.isNotBlank()) {
                        _vehicleEvents.emit(RealtimeVehicleEvent(message.vehicleId, message.routeId, null))
                    }
                }
            }
            if (passengerUpdates != null) launch {
                collectSafely("passenger_location", passengerUpdates) { message ->
                    if (message.routeId != routeId || message.passengerId.isBlank() ||
                        !validCoordinates(message.latitude, message.longitude)
                    ) return@collectSafely
                    val timestamp = parseFreshTimestamp(message.recordedAt) ?: return@collectSafely
                    _passengerEvents.emit(
                        RealtimePassengerEvent(
                            message.passengerId,
                            message.routeId,
                            PassengerLocation(
                                message.passengerId,
                                message.routeId,
                                message.latitude,
                                message.longitude,
                                timestamp,
                                receivedAtElapsedMs = SystemClock.elapsedRealtime(),
                            ),
                        ),
                    )
                }
            }
            if (passengerRemovals != null) launch {
                collectSafely("passenger_location_removed", passengerRemovals) { message ->
                    if (message.routeId == routeId && message.passengerId.isNotBlank()) {
                        _passengerEvents.emit(RealtimePassengerEvent(message.passengerId, message.routeId, null))
                    }
                }
            }
        }

        try {
            withTimeout(CONNECT_TIMEOUT_MS) {
                newVehicleChannel.subscribe(blockUntilSubscribed = true)
                newPassengerChannel?.subscribe(blockUntilSubscribed = true)
            }
            channelJob = collectors
            vehicleChannel = newVehicleChannel
            passengerChannel = newPassengerChannel
            connectedRouteId = routeId
            connectedPassengerFeed = receivePassengerLocations
        } catch (error: Throwable) {
            collectors.cancelAndJoin()
            runCatching { client.realtime.removeChannel(newVehicleChannel) }
            newPassengerChannel?.let { runCatching { client.realtime.removeChannel(it) } }
            throw error
        }
    }

    override suspend fun sendPassengerLocation(
        routeId: String,
        latitude: Double,
        longitude: Double,
        recordedAtIso: String,
    ) {
        require(validCoordinates(latitude, longitude)) { "Invalid passenger coordinates" }
        SupabaseClientProvider.ensureAnonymousSession(client)
        client.postgrest.rpc(
            "publish_passenger_location",
            buildJsonObject {
                put("p_route_id", routeId)
                put("p_latitude", latitude)
                put("p_longitude", longitude)
                put("p_recorded_at", recordedAtIso)
            },
        )
    }

    override suspend fun removePassengerLocation(routeId: String) {
        SupabaseClientProvider.ensureAnonymousSession(client)
        client.postgrest.rpc(
            "remove_passenger_location",
            buildJsonObject { put("p_route_id", routeId) },
        )
    }

    override suspend fun getActivePassengerLocations(routeId: String): List<PassengerLocation> {
        SupabaseClientProvider.ensureAnonymousSession(client)
        val nowElapsed = SystemClock.elapsedRealtime()
        return client.postgrest.rpc(
            "get_active_passenger_locations",
            buildJsonObject { put("p_route_id", routeId) },
        ).decodeList<PassengerLocationSnapshotDto>()
            .mapNotNull { row ->
                if (row.routeId != routeId || row.passengerId.isBlank() ||
                    !validCoordinates(row.latitude, row.longitude)
                ) return@mapNotNull null
                val timestamp = parseFreshTimestamp(row.recordedAt) ?: return@mapNotNull null
                PassengerLocation(
                    passengerId = row.passengerId,
                    routeId = row.routeId,
                    latitude = row.latitude,
                    longitude = row.longitude,
                    timestampEpochMs = timestamp,
                    receivedAtElapsedMs = nowElapsed,
                )
            }
    }

    private suspend fun <T> collectSafely(
        eventName: String,
        flow: Flow<T>,
        transform: suspend (T) -> Unit,
    ) {
        try {
            flow.collect { message ->
                try {
                    transform(message)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Ignoring invalid $eventName Realtime event", error)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "$eventName Realtime collector stopped; reconnecting the channel", error)
            _connectionState.value = _connectionState.value.copy(subscribed = false)
        }
    }

    private fun parseFreshTimestamp(value: String): Long? {
        val timestamp = runCatching { Instant.parse(value).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .getOrNull() ?: return null
        val ageMs = System.currentTimeMillis() - timestamp
        return timestamp.takeIf { ageMs in -MAX_FUTURE_TIMESTAMP_MS..MAX_EVENT_AGE_MS }
    }

    private fun validCoordinates(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0

    private suspend fun closeChannels() {
        channelJob?.cancelAndJoin()
        channelJob = null
        val oldVehicleChannel = vehicleChannel
        val oldPassengerChannel = passengerChannel
        vehicleChannel = null
        passengerChannel = null
        connectedRouteId = null
        connectedPassengerFeed = false
        _connectionState.value = _connectionState.value.copy(routeId = null, subscribed = false)
        oldVehicleChannel?.let { runCatching { client.realtime.removeChannel(it) } }
        oldPassengerChannel?.let { runCatching { client.realtime.removeChannel(it) } }
    }

    override fun disconnect() {
        val requestedGeneration = synchronized(this) {
            connectionLifecycleGeneration += 1L
            connectionLifecycleGeneration
        }
        scope.launch {
            connectMutex.withLock {
                if (connectionLifecycleGeneration == requestedGeneration) closeChannels()
            }
        }
    }

    private companion object {
        const val TAG = "SafarRealtime"
        const val MAX_EVENT_AGE_MS = 60_000L
        const val MAX_FUTURE_TIMESTAMP_MS = 30_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
    }
}

interface RealtimeTransport {
    val vehicleEvents: Flow<RealtimeVehicleEvent>
    val passengerEvents: Flow<RealtimePassengerEvent>
    val connectionState: StateFlow<RealtimeConnectionState>
    suspend fun connect(routeId: String, receivePassengerLocations: Boolean = false)
    suspend fun sendPassengerLocation(routeId: String, latitude: Double, longitude: Double, recordedAtIso: String)
    suspend fun removePassengerLocation(routeId: String)
    suspend fun getActivePassengerLocations(routeId: String): List<PassengerLocation>
    fun disconnect()
}
