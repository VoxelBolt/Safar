package com.safar.app.data.realtime

import com.safar.app.data.remote.SupabaseClientProvider
import com.safar.app.domain.model.PassengerLocation
import com.safar.app.domain.model.VehicleLocation
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.realtime.broadcast.BroadcastPayload
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

data class RealtimeVehicleEvent(
    val vehicleId: String,
    val routeId: String,
    val location: VehicleLocation?,
)

data class RealtimePassengerEvent(
    val passengerId: String,
    val routeId: String,
    val location: PassengerLocation?,
)

@Serializable
private data class VehicleLocationBroadcast(
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("route_id") val routeId: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("recorded_at") val recordedAt: String,
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

/**
 * Route-scoped Supabase Broadcast transport shared by the passenger and driver
 * maps. Driver vehicle locations are emitted by the database trigger; passenger
 * locations are lightweight client broadcasts and are never stored as history.
 */
class SupabaseRealtimeTransport(
    private val client: SupabaseClient = SupabaseClientProvider.client,
) : RealtimeTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _vehicleEvents = MutableSharedFlow<RealtimeVehicleEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    private val _passengerEvents = MutableSharedFlow<RealtimePassengerEvent>(
        replay = 0,
        extraBufferCapacity = 128,
    )

    override val vehicleEvents: Flow<RealtimeVehicleEvent> = _vehicleEvents.asSharedFlow()
    override val passengerEvents: Flow<RealtimePassengerEvent> = _passengerEvents.asSharedFlow()

    private var channelJob: Job? = null
    private var channel: io.github.jan.supabase.realtime.RealtimeChannel? = null
    private var connectedRouteId: String? = null

    override suspend fun connect(routeId: String) {
        if (connectedRouteId == routeId && channelJob?.isActive == true) return

        channelJob?.cancel()
        channelJob = null

        channel?.let { existing ->
            runCatching { client.realtime.removeChannel(existing) }
        }
        channel = null
        connectedRouteId = null

        client.auth.awaitInitialization()
        if (client.auth.currentSessionOrNull() == null) {
            client.auth.signInAnonymously()
        }
        check(client.auth.currentSessionOrNull() != null) {
            "Supabase authentication is required for realtime."
        }

        client.realtime.connect()
        client.realtime.setAuth()

        val topic = "safar:vehicle-location:route:$routeId"
        val realtimeChannel = client.channel(topic) {
            isPrivate = true
        }
        // Do not expose the channel as connected until subscribe() has actually
        // completed. Broadcast() falls back to HTTP when the channel is not
        // subscribed; private channels require the authenticated realtime
        // authorization path, so publishing during that window can fail.

        val updates = realtimeChannel
            .broadcastFlow<VehicleLocationBroadcast>(event = "vehicle_location")
            .map { message ->
                RealtimeVehicleEvent(
                    vehicleId = message.vehicleId,
                    routeId = message.routeId,
                    location = VehicleLocation(
                        vehicleId = message.vehicleId,
                        latitude = message.latitude,
                        longitude = message.longitude,
                        timestampEpochMs = Instant.parse(message.recordedAt).toEpochMilli(),
                    ),
                )
            }

        val removals = realtimeChannel
            .broadcastFlow<VehicleLocationRemovedBroadcast>(event = "vehicle_location_removed")
            .map { message ->
                RealtimeVehicleEvent(
                    vehicleId = message.vehicleId,
                    routeId = message.routeId,
                    location = null,
                )
            }

        val passengerUpdates = realtimeChannel
            .broadcastFlow<PassengerLocationBroadcast>(event = "passenger_location")
            .map { message ->
                RealtimePassengerEvent(
                    passengerId = message.passengerId,
                    routeId = message.routeId,
                    location = PassengerLocation(
                        passengerId = message.passengerId,
                        routeId = message.routeId,
                        latitude = message.latitude,
                        longitude = message.longitude,
                        timestampEpochMs = Instant.parse(message.recordedAt).toEpochMilli(),
                    ),
                )
            }

        val passengerRemovals = realtimeChannel
            .broadcastFlow<PassengerLocationRemovedBroadcast>(event = "passenger_location_removed")
            .map { message ->
                RealtimePassengerEvent(
                    passengerId = message.passengerId,
                    routeId = message.routeId,
                    location = null,
                )
            }

        channelJob = scope.launch {
            launch { updates.collect { _vehicleEvents.emit(it) } }
            launch { removals.collect { _vehicleEvents.emit(it) } }
            launch { passengerUpdates.collect { _passengerEvents.emit(it) } }
            launch { passengerRemovals.collect { _passengerEvents.emit(it) } }
        }

        try {
            realtimeChannel.subscribe(blockUntilSubscribed = true)
            channel = realtimeChannel
            connectedRouteId = routeId
        } catch (error: Throwable) {
            channelJob?.cancel()
            channelJob = null
            channel = null
            connectedRouteId = null
            runCatching { client.realtime.removeChannel(realtimeChannel) }
            throw error
        }
    }

    override suspend fun sendDriverLocation(
        routeId: String,
        vehicleId: String,
        latitude: Double,
        longitude: Double,
        recordedAtIso: String,
    ) {
        ensureConnected(routeId)
        val realtimeChannel = channel ?: return
        realtimeChannel.broadcast(
            event = "vehicle_location",
            payload = BroadcastPayload.Json(buildJsonObject {
                put("vehicle_id", vehicleId)
                put("route_id", routeId)
                put("latitude", latitude)
                put("longitude", longitude)
                put("recorded_at", recordedAtIso)
            }),
        )
    }

    override suspend fun sendPassengerLocation(
        routeId: String,
        latitude: Double,
        longitude: Double,
        recordedAtIso: String,
    ) {
        // Location updates are continuous. A temporary realtime connection gap
        // must never crash the application; wait briefly for the subscription
        // to become ready and let the next GPS sample retry if it does not.
        ensureConnected(routeId)
        val realtimeChannel = channel
        if (connectedRouteId != routeId || realtimeChannel == null) return

        val passengerId = client.auth.currentUserOrNull()?.id ?: return
        realtimeChannel.broadcast(
            event = "passenger_location",
            payload = BroadcastPayload.Json(buildJsonObject {
                put("passenger_id", passengerId)
                put("route_id", routeId)
                put("latitude", latitude)
                put("longitude", longitude)
                put("recorded_at", recordedAtIso)
            }),
        )
    }

    override suspend fun removePassengerLocation(routeId: String) {
        if (connectedRouteId != routeId) return
        val passengerId = client.auth.currentUserOrNull()?.id ?: return
        channel?.broadcast(
            event = "passenger_location_removed",
            payload = BroadcastPayload.Json(buildJsonObject {
                put("passenger_id", passengerId)
                put("route_id", routeId)
            }),
        )
    }

    private suspend fun ensureConnected(routeId: String) {
        if (connectedRouteId == routeId && channel != null && channelJob?.isActive == true) return
        connect(routeId)
    }

    override fun disconnect() {
        channelJob?.cancel()
        channelJob = null
        connectedRouteId = null
        val existingChannel = channel
        channel = null
        if (existingChannel != null) {
            scope.launch {
                runCatching { client.realtime.removeChannel(existingChannel) }
            }
        }
    }
}

interface RealtimeTransport {
    val vehicleEvents: Flow<RealtimeVehicleEvent>
    val passengerEvents: Flow<RealtimePassengerEvent>
    suspend fun connect(routeId: String)
    suspend fun sendDriverLocation(
        routeId: String,
        vehicleId: String,
        latitude: Double,
        longitude: Double,
        recordedAtIso: String,
    )
    suspend fun sendPassengerLocation(
        routeId: String,
        latitude: Double,
        longitude: Double,
        recordedAtIso: String,
    )
    suspend fun removePassengerLocation(routeId: String)
    fun disconnect()
}
