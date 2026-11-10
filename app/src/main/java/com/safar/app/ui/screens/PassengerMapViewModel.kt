package com.safar.app.ui.screens

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.os.SystemClock
import com.safar.app.data.location.AndroidLocationProvider
import com.safar.app.data.realtime.RealtimeRetryPolicy
import com.safar.app.data.realtime.RealtimeTransport
import com.safar.app.di.AppContainer
import com.safar.app.domain.model.GeoPoint
import com.safar.app.domain.model.Route
import com.safar.app.domain.model.Vehicle
import com.safar.app.domain.model.VehicleLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.time.Instant
import kotlin.random.Random

data class PassengerMapUiState(
    val routes: List<Route> = emptyList(),
    val recentRoutes: List<Route> = emptyList(),
    val selectedRoute: Route? = null,
    val activeVehicles: List<Vehicle> = emptyList(),
    val passengerLocation: GeoPoint? = null,
    val passengerLocationAccuracyMeters: Double? = null,
    // Last map camera position. Used only as a fallback when Android has no GPS fix (for example on an emulator).
    val mapCenterLocation: GeoPoint? = GeoPoint(40.2829, 69.6228),
    val headingDegrees: Float? = null,
    val locateRequestId: Long = 0L,
    val nearestVehicle: Vehicle? = null,
    val nearestVehicleDistanceMeters: Double? = null,
    val nearestVehicleEtaMinutes: Int? = null,
    val nearestVehicleRequestId: Long = 0L,
    val nearestVehicleTrackingEnabled: Boolean = true,
    val findingNearestVehicle: Boolean = false,
    val loadingRoutes: Boolean = false,
    val loadingVehicles: Boolean = false,
    val error: String? = null,
    val locationPermissionRequired: Boolean = true,
)

class PassengerMapViewModel(application: Application) : AndroidViewModel(application) {
    private val routeRepository = AppContainer.routeRepository
    private val vehicleRepository = AppContainer.vehicleRepository
    private val realtimeTransport: RealtimeTransport = AppContainer.realtimeTransport
    private val locationProvider = AndroidLocationProvider(application)
    private val vehicleMarkerFreshness = VehicleMarkerFreshness()

    private val _uiState = MutableStateFlow(PassengerMapUiState())
    val uiState: StateFlow<PassengerMapUiState> = _uiState.asStateFlow()

    private var locationJob: Job? = null
    private var vehicleRefreshJob: Job? = null
    private var vehicleLocationPollingJob: Job? = null
    private var realtimeJob: Job? = null
    private var passengerPublishJob: Job? = null
    @Volatile private var lastPassengerFixAtMs: Long = 0L
    private var staleSweepJob: Job? = null
    private var headingJob: Job? = null
    private var locateRequestJob: Job? = null
    private var nearestVehicleRequestJob: Job? = null

    init {
        refreshLocationPermissionState()
        startStaleSweep()
        startEtaRefresh()
    }

    fun loadRoutes() {
        if (_uiState.value.loadingRoutes) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loadingRoutes = true, error = null)
            try {
                val routes = withTimeout(BACKEND_REQUEST_TIMEOUT_MS) {
                    routeRepository.getRoutes().sortedBy { it.number }
                }
                // Keep route selection explicit: loading the route list must not
                // silently select the first route (previously route 15 could be
                // selected automatically when it happened to be first).
                val selected = _uiState.value.selectedRoute?.let { old -> routes.firstOrNull { it.id == old.id } }
                _uiState.value = _uiState.value.copy(
                    routes = routes,
                    selectedRoute = selected,
                    loadingRoutes = false,
                )
                selected?.let {
                    loadVehicles(it)
                    startVehicleLocationPolling(it.id)
                    switchRealtime(it.id)
                }
            } catch (timeout: TimeoutCancellationException) {
                _uiState.value = _uiState.value.copy(
                    loadingRoutes = false,
                    error = "Сервер отвечает слишком долго. Попробуйте загрузить маршруты ещё раз.",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    loadingRoutes = false,
                    error = error.message ?: "Не удалось загрузить маршруты",
                )
            }
        }
    }

    fun showNearestVehicleOnMap() {
        nearestVehicleRequestJob?.cancel()
        nearestVehicleRequestJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(findingNearestVehicle = true, error = null)
            try {
                val state = _uiState.value

                // Do not wait for a fresh GPS fix here. The button must respond immediately.
                // Use the latest passenger fix when available; otherwise use the exact map
                // camera position reported by PassengerMapView.
                val passenger = state.passengerLocation ?: state.mapCenterLocation
                android.util.Log.d(
                    "SafarNearest",
                    "CLICK source=" + if (state.passengerLocation != null) "gps" else "map_center" +
                        " passenger=" + passenger?.latitude + "," + passenger?.longitude,
                )

                if (passenger == null) {
                    _uiState.value = _uiState.value.copy(error = "Не удалось определить точку карты")
                    return@launch
                }

                var vehicles = state.activeVehicles
                    .filter { it.currentLocation != null }
                if (vehicles.isEmpty()) {
                    val routeId = state.selectedRoute?.id
                    if (routeId != null) {
                        vehicles = try {
                            withTimeout(BACKEND_REQUEST_TIMEOUT_MS) {
                                vehicleRepository.getActiveVehiclesForRoute(routeId)
                            }.filter { it.active && it.currentLocation != null }
                        } catch (timeout: TimeoutCancellationException) {
                            _uiState.value = _uiState.value.copy(
                                error = "Сервер отвечает слишком долго. Не удалось найти ближайшую маршрутку.",
                            )
                            return@launch
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            android.util.Log.w("SafarNearest", "Could not refresh vehicles for nearest search", error)
                            _uiState.value = _uiState.value.copy(
                                error = "Не удалось обновить данные маршруток. Попробуйте ещё раз.",
                            )
                            return@launch
                        }
                    }
                }

                android.util.Log.d(
                    "SafarNearest",
                    "candidates=" + vehicles.size +
                        " locations=" + vehicles.joinToString {
                            it.identifier + ":" + it.currentLocation?.latitude + "," + it.currentLocation?.longitude
                        },
                )

                val result = NearestVehicleCalculator.findNearest(passenger, vehicles)
                if (result == null) {
                    _uiState.value = _uiState.value.copy(
                        nearestVehicle = null,
                        nearestVehicleDistanceMeters = null,
                        nearestVehicleEtaMinutes = null,
                        error = "Сейчас нет маршруток с геопозицией",
                    )
                    return@launch
                }

                android.util.Log.d(
                    "SafarNearest",
                    "RESULT vehicle=" + result.vehicle.identifier +
                        " distance=" + result.distanceMeters + "m eta=" + result.etaMinutes + "min",
                )

                _uiState.value = _uiState.value.copy(
                    activeVehicles = if (_uiState.value.activeVehicles.isEmpty()) vehicles else _uiState.value.activeVehicles,
                    nearestVehicle = result.vehicle,
                    nearestVehicleDistanceMeters = result.distanceMeters,
                    nearestVehicleEtaMinutes = result.etaMinutes,
                    nearestVehicleRequestId = _uiState.value.nearestVehicleRequestId + 1L,
                    nearestVehicleTrackingEnabled = true,
                    error = null,
                )
            } finally {
                _uiState.value = _uiState.value.copy(findingNearestVehicle = false)
            }
        }
    }

    private suspend fun awaitFreshPassengerLocation(): GeoPoint? =
        suspendCancellableCoroutine { continuation ->
            locationProvider.currentDisplayLocationSample { sample ->
                val point = sample?.point
                if (continuation.isActive && point != null) {
                    continuation.resume(point)
                }
            }
        }

    fun onMapCenterChanged(point: GeoPoint) {
        if (!point.latitude.isFinite() || !point.longitude.isFinite()) return
        val state = _uiState.value
        _uiState.value = state.copy(mapCenterLocation = point)
        // The map center is the fallback passenger reference whenever Android has
        // no usable GPS fix. Recalculate immediately so ETA is always available.
        if (state.passengerLocation == null && state.nearestVehicleTrackingEnabled) {
            updateNearestVehicle(point, state.activeVehicles)
        }
    }

    fun selectRoute(route: Route) {
        val oldRouteId = _uiState.value.selectedRoute?.id
        _uiState.value = _uiState.value.copy(
            selectedRoute = route,
            recentRoutes = buildList {
                add(route)
                _uiState.value.recentRoutes.filter { it.id != route.id }.take(2).forEach(::add)
            },
            activeVehicles = emptyList(),
            nearestVehicle = null,
            nearestVehicleDistanceMeters = null,
            nearestVehicleEtaMinutes = null,
            nearestVehicleRequestId = 0L,
            nearestVehicleTrackingEnabled = true,
            error = null,
        )
        vehicleMarkerFreshness.clear()
        loadVehicles(route)
        startVehicleLocationPolling(route.id)
        if (oldRouteId != null && oldRouteId != route.id) {
            // Remove the old route-scoped marker before switching the single realtime channel.
            viewModelScope.launch {
                runCatching { realtimeTransport.removePassengerLocation(oldRouteId) }
                switchRealtime(route.id)
            }
        } else {
            switchRealtime(route.id)
        }
    }

    private fun loadVehicles(route: Route) {
        vehicleRefreshJob?.cancel()
        vehicleRefreshJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loadingVehicles = true)
            try {
                val vehicles = withTimeout(BACKEND_REQUEST_TIMEOUT_MS) {
                    vehicleRepository.getActiveVehiclesForRoute(route.id)
                }
                val fresh = freshVehicles(vehicles)
                vehicleMarkerFreshness.observeSnapshot(fresh, SystemClock.elapsedRealtime())
                _uiState.value = _uiState.value.copy(
                    activeVehicles = fresh,
                    loadingVehicles = false,
                )
                if (_uiState.value.nearestVehicleTrackingEnabled) {
                    (_uiState.value.passengerLocation ?: _uiState.value.mapCenterLocation)?.let { updateNearestVehicle(it, fresh) }
                }
            } catch (timeout: TimeoutCancellationException) {
                _uiState.value = _uiState.value.copy(
                    loadingVehicles = false,
                    error = "Сервер отвечает слишком долго. Не удалось обновить маршрутки.",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    loadingVehicles = false,
                    error = error.message ?: "Не удалось получить транспорт",
                )
            }
        }
    }

    private fun startVehicleLocationPolling(routeId: String) {
        vehicleLocationPollingJob?.cancel()
        vehicleLocationPollingJob = viewModelScope.launch {
            while (isActive && _uiState.value.selectedRoute?.id == routeId) {
                // Route selection already performs an immediate initial fetch.
                // Let Realtime carry live movement and use polling as recovery.
                delay(VEHICLE_POLL_INTERVAL_MS)
                if (!isActive || _uiState.value.selectedRoute?.id != routeId) break
                try {
                    val vehicles = withTimeout(BACKEND_REQUEST_TIMEOUT_MS) {
                        vehicleRepository.getActiveVehiclesForRoute(routeId)
                    }
                    val active = freshVehicles(vehicles)
                    vehicleMarkerFreshness.observeSnapshot(active, SystemClock.elapsedRealtime())
                    _uiState.value = _uiState.value.copy(activeVehicles = active, error = null)
                    if (_uiState.value.nearestVehicleTrackingEnabled) {
                        (_uiState.value.passengerLocation ?: _uiState.value.mapCenterLocation)?.let { updateNearestVehicle(it, active) }
                    }
                } catch (timeout: TimeoutCancellationException) {
                    android.util.Log.w("SafarVehicle", "poll timed out; retrying after the polling interval", timeout)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    android.util.Log.w("SafarVehicle", "poll failed; next retry will run after the polling interval", error)
                }
            }
        }
    }
    private fun switchRealtime(routeId: String) {
        realtimeJob?.cancel()
        passengerPublishJob?.cancel()
        // Publishing goes through a REST RPC, not the Realtime socket. Start it
        // right away so a waiting passenger stays visible to drivers even while
        // the vehicle channel is still connecting or reconnecting.
        startPassengerLocationPublishing(routeId)
        realtimeJob = viewModelScope.launch {
            var attempt = 0
            while (isActive && _uiState.value.selectedRoute?.id == routeId) {
                try {
                    withTimeout(BACKEND_REQUEST_TIMEOUT_MS) {
                        realtimeTransport.connect(routeId, receivePassengerLocations = false)
                    }
                    val connected = withTimeoutOrNull(BACKEND_REQUEST_TIMEOUT_MS) {
                        realtimeTransport.connectionState.first { it.routeId == routeId && it.subscribed }
                    } ?: error("Realtime подписка не подтвердилась вовремя")
                    startPassengerLocationPublishing(routeId)
                    refreshVehiclesAfterReconnect(routeId)

                    coroutineScope {
                        launch {
                            realtimeTransport.vehicleEvents.collectLatest { event ->
                                applyRealtimeEvent(event.vehicleId, event.routeId, event.location)
                            }
                        }
                        var lastEpoch = connected.epoch
                        var stableSinceElapsedMs = SystemClock.elapsedRealtime()
                        while (isActive && _uiState.value.selectedRoute?.id == routeId) {
                            val disconnected = realtimeTransport.connectionState.first {
                                it.routeId != routeId || !it.subscribed
                            }
                            val recovered = withTimeoutOrNull(REALTIME_SDK_RECOVERY_GRACE_MS) {
                                realtimeTransport.connectionState.first {
                                    it.routeId == routeId && it.subscribed && it.epoch > lastEpoch
                                }
                            }
                            if (recovered == null) {
                                android.util.Log.w("SafarRealtime", "Channel did not recover for route $routeId")
                                break
                            }
                            lastEpoch = recovered.epoch
                            if (SystemClock.elapsedRealtime() - stableSinceElapsedMs >= REALTIME_STABLE_RESET_MS) {
                                attempt = 0
                            }
                            stableSinceElapsedMs = SystemClock.elapsedRealtime()
                            refreshVehiclesAfterReconnect(routeId)
                            if (disconnected.routeId == null) break
                        }
                    }
                    attempt += 1
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    android.util.Log.w("SafarRealtime", "Passenger map Realtime connection failed", error)
                    _uiState.value = _uiState.value.copy(error = error.message ?: "Realtime недоступен")
                    attempt += 1
                }
                if (isActive && _uiState.value.selectedRoute?.id == routeId) {
                    delay(RealtimeRetryPolicy.delayMs(attempt.coerceAtLeast(1), Random.nextDouble(0.75, 1.25)))
                }
            }
        }
    }

    private fun startPassengerLocationPublishing(routeId: String) {
        if (passengerPublishJob?.isActive == true) return
        passengerPublishJob = viewModelScope.launch {
            while (isActive && _uiState.value.selectedRoute?.id == routeId) {
                val now = System.currentTimeMillis()
                val state = _uiState.value
                val point = state.passengerLocation
                if (!state.locationPermissionRequired && locationJob?.isActive == true &&
                    lastPassengerFixAtMs > 0L && now - lastPassengerFixAtMs >= PASSENGER_FIX_REFRESH_MS
                ) {
                    // The live stream is silent while the passenger stands still (2 m
                    // minimum update distance plus the stabilizer deadband). A one-shot
                    // fix keeps a passenger who waits at a stop broadcasting instead of
                    // disappearing from drivers' maps 15 seconds after the last step.
                    requestCurrentLocation(centerCamera = false)
                }
                if (!state.locationPermissionRequired && point != null && lastPassengerFixAtMs > 0L &&
                    state.passengerLocationAccuracyMeters?.let { it <= LOCATION_BROADCAST_MAX_ACCURACY_METERS } == true &&
                    now - lastPassengerFixAtMs in 0L..PASSENGER_FIX_MAX_AGE_MS) {
                    runCatching {
                        realtimeTransport.sendPassengerLocation(
                            routeId = routeId,
                            latitude = point.latitude,
                            longitude = point.longitude,
                            recordedAtIso = Instant.ofEpochMilli(now).toString(),
                        )
                    }.onFailure { error ->
                        android.util.Log.w("SafarPassenger", "Location broadcast failed", error)
                    }
                }
                delay(PASSENGER_BROADCAST_INTERVAL_MS)
            }
        }
    }

    private suspend fun refreshVehiclesAfterReconnect(routeId: String) {
        try {
            val vehicles = withTimeout(BACKEND_REQUEST_TIMEOUT_MS) {
                vehicleRepository.getActiveVehiclesForRoute(routeId)
            }
            if (_uiState.value.selectedRoute?.id != routeId) return
            val active = freshVehicles(vehicles)
            vehicleMarkerFreshness.observeSnapshot(active, SystemClock.elapsedRealtime())
            _uiState.value = _uiState.value.copy(activeVehicles = active, error = null)
            if (_uiState.value.nearestVehicleTrackingEnabled) {
                (_uiState.value.passengerLocation ?: _uiState.value.mapCenterLocation)?.let { updateNearestVehicle(it, active) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w("SafarRealtime", "Vehicle snapshot refresh after reconnect failed", error)
        }
    }

    private fun applyRealtimeEvent(vehicleId: String, routeId: String, location: VehicleLocation?) {
        val selectedRoute = _uiState.value.selectedRoute ?: return
        if (selectedRoute.id != routeId) return

        // A realtime event is emitted by the database after a successful GPS write;
        // trust the server timestamp rather than comparing clocks on two devices.
        if (location == null) {
            vehicleMarkerFreshness.remove(vehicleId)
            val active = _uiState.value.activeVehicles.filterNot { it.id == vehicleId }
            val nearest = _uiState.value.nearestVehicle?.takeIf { it.id != vehicleId }
            _uiState.value = _uiState.value.copy(
                activeVehicles = active,
                nearestVehicle = nearest,
                nearestVehicleDistanceMeters = if (nearest == null) null else _uiState.value.nearestVehicleDistanceMeters,
                nearestVehicleEtaMinutes = if (nearest == null) null else _uiState.value.nearestVehicleEtaMinutes,
            )
            if (nearest == null && _uiState.value.nearestVehicleTrackingEnabled) {
                (_uiState.value.passengerLocation ?: _uiState.value.mapCenterLocation)?.let {
                    updateNearestVehicle(it, active)
                }
            }
            return
        }

        val currentVehicle = _uiState.value.activeVehicles.firstOrNull { it.id == vehicleId }
        if (currentVehicle != null) {
            if ((currentVehicle.currentLocation?.timestampEpochMs ?: Long.MIN_VALUE) > location.timestampEpochMs) return
            vehicleMarkerFreshness.observeRealtime(vehicleId, SystemClock.elapsedRealtime())
            // Some GPS-only broadcasts or cached database rows may omit heading.
            // Do not let such a packet reset a previously measured live rotation to north.
            val effectiveLocation = if (location.headingDegrees == null) {
                location.copy(headingDegrees = currentVehicle.currentLocation?.headingDegrees)
            } else {
                location
            }
            val updated = currentVehicle.copy(currentLocation = effectiveLocation, active = true)
            val active = _uiState.value.activeVehicles.map { if (it.id == vehicleId) updated else it }
            _uiState.value = _uiState.value.copy(activeVehicles = active, error = null)
            if (_uiState.value.nearestVehicleTrackingEnabled) {
                (_uiState.value.passengerLocation ?: _uiState.value.mapCenterLocation)?.let { updateNearestVehicle(it, active) }
            }
            return
        }

        // A burst of location events for a vehicle not in the local snapshot must
        // not cancel/restart the same backend query repeatedly under load.
        if (vehicleRefreshJob?.isActive == true) return
        vehicleRefreshJob = viewModelScope.launch {
            runCatching { vehicleRepository.getActiveVehiclesForRoute(selectedRoute.id) }
                .onSuccess { vehicles ->
                    val active = freshVehicles(vehicles)
                    vehicleMarkerFreshness.observeSnapshot(active, SystemClock.elapsedRealtime())
                    _uiState.value = _uiState.value.copy(activeVehicles = active, error = null)
                    if (_uiState.value.nearestVehicleTrackingEnabled) {
                        (_uiState.value.passengerLocation ?: _uiState.value.mapCenterLocation)?.let { updateNearestVehicle(it, active) }
                    }
                }
        }
    }

    private fun updateNearestVehicle(
        passenger: GeoPoint,
        vehicles: List<Vehicle>,
        forceFocus: Boolean = false,
    ) {
        // The repository snapshot is already the source of truth for the
        // currently visible route. Do not discard a vehicle here just because
        // its recorded_at is a little old; that was making the button silently
        // produce no nearest vehicle even though the marker was on the map.
        val candidates = (vehicles + listOfNotNull(_uiState.value.nearestVehicle))
            .distinctBy { it.id }
            .filter { vehicle ->
                val location = vehicle.currentLocation
                location != null &&
                    location.latitude.isFinite() &&
                    location.longitude.isFinite()
            }

        val result = NearestVehicleCalculator.findNearest(passenger, candidates)
        _uiState.value = _uiState.value.copy(
            nearestVehicle = result?.vehicle,
            nearestVehicleDistanceMeters = result?.distanceMeters,
            nearestVehicleEtaMinutes = result?.etaMinutes,
            nearestVehicleRequestId = if (forceFocus && result != null) {
                _uiState.value.nearestVehicleRequestId + 1L
            } else {
                _uiState.value.nearestVehicleRequestId
            },
        )
    }

    private fun startEtaRefresh() {
        viewModelScope.launch {
            while (isActive) {
                delay(60_000L)
                val state = _uiState.value
                val passenger = state.passengerLocation ?: state.mapCenterLocation ?: continue
                val result = NearestVehicleCalculator.findNearest(passenger, state.activeVehicles)
                _uiState.value = state.copy(
                    nearestVehicle = result?.vehicle,
                    nearestVehicleDistanceMeters = result?.distanceMeters,
                    nearestVehicleEtaMinutes = result?.etaMinutes,
                )
            }
        }
    }

    private fun startStaleSweep() {
        if (staleSweepJob?.isActive == true) return
        staleSweepJob = viewModelScope.launch {
            while (isActive) {
                delay(STALE_SWEEP_INTERVAL_MS)
                val current = _uiState.value
                val active = vehicleMarkerFreshness.retainFresh(
                    current.activeVehicles,
                    SystemClock.elapsedRealtime(),
                )
                if (active.size != current.activeVehicles.size) {
                    val activeIds = active.mapTo(mutableSetOf()) { it.id }
                    val nearest = current.nearestVehicle?.takeIf { it.id in activeIds }
                    _uiState.value = current.copy(
                        activeVehicles = active,
                        nearestVehicle = nearest,
                        nearestVehicleDistanceMeters = if (nearest != null) current.nearestVehicleDistanceMeters else null,
                        nearestVehicleEtaMinutes = if (nearest != null) current.nearestVehicleEtaMinutes else null,
                    )
                    if (nearest == null && current.nearestVehicleTrackingEnabled) {
                        (current.passengerLocation ?: current.mapCenterLocation)?.let {
                            updateNearestVehicle(it, active)
                        }
                    }
                }
            }
        }
    }

    // Realtime heading events can arrive between polling snapshots. If a backend
    // response omits heading_degrees (for example during a schema/cache transition),
    // do not let that null erase the last measured compass angle and freeze the
    // passenger's vehicle marker at north.
    private fun freshVehicles(vehicles: List<Vehicle>): List<Vehicle> {
        val previousById = _uiState.value.activeVehicles.associateBy { it.id }
        return vehicles
            .filter { it.active && it.currentLocation != null }
            .map { vehicle ->
                val location = vehicle.currentLocation ?: return@map vehicle
                val previousLocation = previousById[vehicle.id]?.currentLocation
                if (previousLocation != null && previousLocation.timestampEpochMs > location.timestampEpochMs) {
                    vehicle.copy(currentLocation = previousLocation)
                } else if (location.headingDegrees == null && previousLocation?.headingDegrees != null) {
                    vehicle.copy(currentLocation = location.copy(headingDegrees = previousLocation.headingDegrees))
                } else {
                    vehicle
                }
            }
    }

    fun onLocationPermissionResult(granted: Boolean) {
        _uiState.value = _uiState.value.copy(locationPermissionRequired = !granted)
        if (granted) {
            startLocationUpdates()
        } else {
            stopLocationUpdates()
            lastPassengerFixAtMs = 0L
            _uiState.value = _uiState.value.copy(
                passengerLocation = null,
                passengerLocationAccuracyMeters = null,
            )
            _uiState.value.selectedRoute?.id?.let { routeId ->
                viewModelScope.launch { runCatching { realtimeTransport.removePassengerLocation(routeId) } }
            }
        }
    }

    fun refreshLocationPermissionState() {
        val granted = hasLocationPermission()
        _uiState.value = _uiState.value.copy(locationPermissionRequired = !granted)
        if (granted) {
            startLocationUpdates()
        } else if (locationJob?.isActive == true || _uiState.value.passengerLocation != null) {
            stopLocationUpdates()
            lastPassengerFixAtMs = 0L
            _uiState.value = _uiState.value.copy(
                passengerLocation = null,
                passengerLocationAccuracyMeters = null,
            )
            _uiState.value.selectedRoute?.id?.let { routeId ->
                viewModelScope.launch { runCatching { realtimeTransport.removePassengerLocation(routeId) } }
            }
        }
    }

    private fun startLocationUpdates() {
        if (locationJob?.isActive == true) return
        requestCurrentLocation(centerCamera = false)
        locationJob = viewModelScope.launch {
            try {
                locationProvider.displayLocationSamples().collectLatest { sample ->
                    val point = sample.point
                    lastPassengerFixAtMs = System.currentTimeMillis()
                    _uiState.value = _uiState.value.copy(
                        passengerLocation = point,
                        passengerLocationAccuracyMeters = sample.accuracyMeters,
                    )
                    if (_uiState.value.nearestVehicleTrackingEnabled) updateNearestVehicle(point, _uiState.value.activeVehicles)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                locationJob = null
                delay(1_000L)
                if (hasLocationPermission()) startLocationUpdates()
            }
        }
        headingJob?.cancel()
        headingJob = viewModelScope.launch {
            try {
                locationProvider.headingDegrees().collectLatest { heading ->
                    _uiState.value = _uiState.value.copy(headingDegrees = heading)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }

    private fun stopLocationUpdates() {
        locationJob?.cancel()
        locationJob = null
        headingJob?.cancel()
        headingJob = null
        locateRequestJob?.cancel()
    }

    private fun requestCurrentLocation(centerCamera: Boolean) {
        if (!hasLocationPermission()) return
        locationProvider.currentDisplayLocationSample { sample ->
            if (sample != null) {
                lastPassengerFixAtMs = System.currentTimeMillis()
                _uiState.value = _uiState.value.copy(
                    passengerLocation = sample.point,
                    passengerLocationAccuracyMeters = sample.accuracyMeters,
                    locateRequestId = if (centerCamera) _uiState.value.locateRequestId + 1L else _uiState.value.locateRequestId,
                )
            }
        }
    }

    fun refreshPassengerLocation() {
        refreshLocationPermissionState()
        if (!hasLocationPermission()) return

        val locationManager = getApplication<Application>()
            .getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val locationEnabled = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            runCatching { locationManager.isLocationEnabled }.getOrDefault(false)
        } else {
            runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        }

        if (!locationEnabled) {
            _uiState.value = _uiState.value.copy(error = "Включите геолокацию на телефоне")
            getApplication<Application>().startActivity(
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }

        locateRequestJob?.cancel()
        locateRequestJob = viewModelScope.launch {
            requestCurrentLocation(centerCamera = true)
            delay(750L)
            requestCurrentLocation(centerCamera = true)
        }
    }

    override fun onCleared() {
        passengerPublishJob?.cancel()
        nearestVehicleRequestJob?.cancel()
        stopLocationUpdates()
        vehicleLocationPollingJob?.cancel()
        realtimeJob?.cancel()
        staleSweepJob?.cancel()
        vehicleRefreshJob?.cancel()
        realtimeTransport.disconnect()
        nearestVehicleRequestJob?.cancel()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private companion object {
        const val BACKEND_REQUEST_TIMEOUT_MS = 15_000L
        const val VEHICLE_POLL_INTERVAL_MS = 10_000L
        const val STALE_SWEEP_INTERVAL_MS = 5_000L
        const val PASSENGER_BROADCAST_INTERVAL_MS = 5_000L
        const val PASSENGER_FIX_MAX_AGE_MS = 15_000L
        const val PASSENGER_FIX_REFRESH_MS = 8_000L
        // Typical city and indoor fixes are 20-40 m; the old 20 m limit silently
        // hid many waiting passengers from drivers.
        const val LOCATION_BROADCAST_MAX_ACCURACY_METERS = 50.0
        const val REALTIME_SDK_RECOVERY_GRACE_MS = 20_000L
        const val REALTIME_STABLE_RESET_MS = 120_000L
    }
}
