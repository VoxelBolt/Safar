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
import java.time.OffsetDateTime

data class RealtimeVehicleEvent(val vehicleId: String,val routeId: String,val location: VehicleLocation?)
data class RealtimePassengerEvent(val passengerId: String,val routeId: String,val location: PassengerLocation?)

@Serializable private data class VehicleLocationBroadcast(@SerialName("vehicle_id") val vehicleId:String,@SerialName("route_id") val routeId:String,val latitude:Double,val longitude:Double,@SerialName("recorded_at") val recordedAt:String)
@Serializable private data class VehicleLocationRemovedBroadcast(@SerialName("vehicle_id") val vehicleId:String,@SerialName("route_id") val routeId:String)
@Serializable private data class PassengerLocationBroadcast(@SerialName("passenger_id") val passengerId:String,@SerialName("route_id") val routeId:String,val latitude:Double,val longitude:Double,@SerialName("recorded_at") val recordedAt:String)
@Serializable private data class PassengerLocationRemovedBroadcast(@SerialName("passenger_id") val passengerId:String,@SerialName("route_id") val routeId:String)

class SupabaseRealtimeTransport(private val client: SupabaseClient = SupabaseClientProvider.client): RealtimeTransport {
    private fun parseTimestamp(value: String): Long =
        runCatching { Instant.parse(value).toEpochMilli() }
            .getOrElse { OffsetDateTime.parse(value).toInstant().toEpochMilli() }

    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val _vehicleEvents = MutableSharedFlow<RealtimeVehicleEvent>(replay=0,extraBufferCapacity=64)
    private val _passengerEvents = MutableSharedFlow<RealtimePassengerEvent>(replay=0,extraBufferCapacity=128)
    override val vehicleEvents: Flow<RealtimeVehicleEvent> = _vehicleEvents.asSharedFlow()
    override val passengerEvents: Flow<RealtimePassengerEvent> = _passengerEvents.asSharedFlow()
    private var channelJob: Job? = null
    private var channel: io.github.jan.supabase.realtime.RealtimeChannel? = null
    private var connectedRouteId: String? = null

    override suspend fun connect(routeId:String) {
        if (connectedRouteId==routeId && channelJob?.isActive==true) return
        channelJob?.cancel(); channelJob=null
        channel?.let { runCatching { client.realtime.removeChannel(it) } }
        channel=null; connectedRouteId=null
        client.auth.awaitInitialization()
        if (client.auth.currentSessionOrNull()==null) client.auth.signInAnonymously()
        check(client.auth.currentSessionOrNull()!=null) { "Supabase authentication is required for realtime." }
        client.realtime.connect(); client.realtime.setAuth()
        val topic="safar:vehicle-location:route:$routeId"
        val realtimeChannel=client.channel(topic){ isPrivate=true }

        val updates=realtimeChannel.broadcastFlow<VehicleLocationBroadcast>(event="vehicle_location").map { m ->
            RealtimeVehicleEvent(m.vehicleId,m.routeId,VehicleLocation(m.vehicleId,m.latitude,m.longitude,parseTimestamp(m.recordedAt)))
        }
        val removals=realtimeChannel.broadcastFlow<VehicleLocationRemovedBroadcast>(event="vehicle_location_removed").map { m ->
            RealtimeVehicleEvent(m.vehicleId,m.routeId,null)
        }
        val passengerUpdates=realtimeChannel.broadcastFlow<PassengerLocationBroadcast>(event="passenger_location").map { m ->
            RealtimePassengerEvent(m.passengerId,m.routeId,PassengerLocation(m.passengerId,m.routeId,m.latitude,m.longitude,parseTimestamp(m.recordedAt)))
        }
        val passengerRemovals=realtimeChannel.broadcastFlow<PassengerLocationRemovedBroadcast>(event="passenger_location_removed").map { m ->
            RealtimePassengerEvent(m.passengerId,m.routeId,null)
        }

        channelJob=scope.launch {
            launch { updates.collect { _vehicleEvents.emit(it) } }
            launch { removals.collect { _vehicleEvents.emit(it) } }
            launch { passengerUpdates.collect { _passengerEvents.emit(it) } }
            launch { passengerRemovals.collect { _passengerEvents.emit(it) } }
        }
        try {
            realtimeChannel.subscribe(blockUntilSubscribed=true)
            channel=realtimeChannel
            connectedRouteId=routeId
        } catch(error:Throwable) {
            channelJob?.cancel(); channelJob=null; channel=null; connectedRouteId=null
            runCatching { client.realtime.removeChannel(realtimeChannel) }
            throw error
        }
    }

    override suspend fun sendDriverLocation(routeId:String,vehicleId:String,latitude:Double,longitude:Double,recordedAtIso:String) {
        ensureConnected(routeId)
        val realtimeChannel=channel ?: return
        realtimeChannel.broadcast(event="vehicle_location",payload=BroadcastPayload.Json(buildJsonObject {
            put("vehicle_id",vehicleId); put("route_id",routeId); put("latitude",latitude); put("longitude",longitude); put("recorded_at",recordedAtIso)
        }))
    }
    override suspend fun sendPassengerLocation(routeId:String,latitude:Double,longitude:Double,recordedAtIso:String) {
        ensureConnected(routeId)
        val realtimeChannel=channel ?: return
        if (connectedRouteId!=routeId) return
        val passengerId=client.auth.currentUserOrNull()?.id ?: return
        realtimeChannel.broadcast(event="passenger_location",payload=BroadcastPayload.Json(buildJsonObject {
            put("passenger_id",passengerId); put("route_id",routeId); put("latitude",latitude); put("longitude",longitude); put("recorded_at",recordedAtIso)
        }))
    }
    override suspend fun removePassengerLocation(routeId:String) {
        if (connectedRouteId!=routeId) return
        val passengerId=client.auth.currentUserOrNull()?.id ?: return
        channel?.broadcast(event="passenger_location_removed",payload=BroadcastPayload.Json(buildJsonObject {
            put("passenger_id",passengerId); put("route_id",routeId)
        }))
    }
    private suspend fun ensureConnected(routeId:String) {
        if (connectedRouteId==routeId && channel!=null && channelJob?.isActive==true) return
        connect(routeId)
    }
    override fun disconnect() {
        channelJob?.cancel(); channelJob=null; connectedRouteId=null
        val existing=channel; channel=null
        if(existing!=null) scope.launch { runCatching { client.realtime.removeChannel(existing) } }
    }
}
interface RealtimeTransport {
    val vehicleEvents: Flow<RealtimeVehicleEvent>
    val passengerEvents: Flow<RealtimePassengerEvent>
    suspend fun connect(routeId:String)
    suspend fun sendDriverLocation(routeId:String,vehicleId:String,latitude:Double,longitude:Double,recordedAtIso:String)
    suspend fun sendPassengerLocation(routeId:String,latitude:Double,longitude:Double,recordedAtIso:String)
    suspend fun removePassengerLocation(routeId:String)
    fun disconnect()
}
