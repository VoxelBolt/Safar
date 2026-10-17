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
import com.google.android.gms.location.LocationServices
import com.safar.app.data.location.AndroidLocationProvider
import com.safar.app.data.realtime.RealtimeTransport
import com.safar.app.di.AppContainer
import com.safar.app.domain.model.GeoPoint
import com.safar.app.domain.model.Route
import com.safar.app.domain.model.Vehicle
import com.safar.app.domain.model.VehicleLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.time.Instant

data class PassengerMapUiState(
    val routes: List<Route> = emptyList(),
    val recentRoutes: List<Route> = emptyList(),
    val selectedRoute: Route? = null,
    val activeVehicles: List<Vehicle> = emptyList(),
    val passengerLocation: GeoPoint? = null,
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
            runCatching { routeRepository.getRoutes().sortedBy { it.number } }
                .onSuccess { routes ->
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
                }
                .onFailure { error ->
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
                        vehicles = runCatching { vehicleRepository.getActiveVehiclesForRoute(routeId) }
                            .getOrElse { emptyList() }
                            .filter { it.active && it.currentLocation != null }
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
            locationProvider.currentLocationSample { sample ->
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
            runCatching { vehicleRepository.getActiveVehiclesForRoute(route.id) }
                .onSuccess { vehicles ->
                    val fresh = freshVehicles(vehicles)
                    _uiState.value = _uiState.value.copy(
                        activeVehicles = fresh,
                        loadingVehicles = false,
                    )
                    if (_uiState.value.nearestVehicleTrackingEnabled) {
                        (_uiState.value.passengerLocation ?: _uiState.value.mapCenterLocation)?.let { updateNearestVehicle(it, fresh) }
                    }
                }
                .onFailure { error ->
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
                runCatching { vehicleRepository.getActiveVehiclesForRoute(routeId) }
                    .onSuccess { vehicles ->
                        val active = freshVehicles(vehicles)
                        _uiState.value = _uiState.value.copy(activeVehicles = active, error = null)
                        if (_uiState.value.nearestVehicleTrackingEnabled) {
                            (_uiState.value.passengerLocation ?: _uiState.value.mapCenterLocation)?.let { updateNearestVehicle(it, active) }
                        }
                    }
                    .onFailure { error -> android.util.Log.w("SafarVehicle", "poll failed", error) }
                delay(10_000L)
            }
        }
    }
    private fun switchRealtime(routeId: String) {
        realtimeJob?.cancel()
        passengerPublishJob?.cancel()
        realtimeTransport.disconnect()
        realtimeJob = viewModelScope.launch {
            try {
                realtimeTransport.connect(routeId)
                // Passenger position is transient route-scoped Broadcast data, never persisted.
                passengerPublishJob?.cancel()
                passengerPublishJob = viewModelScope.launch {
                    while (isActive && _uiState.value.selectedRoute?.id == routeId) {
                        val now = System.currentTimeMillis()
                        val state = _uiState.value
                        val point = state.passengerLocation
                        if (!state.locationPermissionRequired && point != null && lastPassengerFixAtMs > 0L &&
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
                realtimeTransport.vehicleEvents.collectLatest { event ->
                    applyRealtimeEvent(event.vehicleId, event.routeId, event.location)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (currentCoroutineContext().isActive) {
                    _uiState.value = _uiState.value.copy(error = error.message ?: "Realtime недоступен")
                }
            }
        }
    }

    private fun applyRealtimeEvent(vehicleId: String, routeId: String, location: VehicleLocation?) {
        val selectedRoute = _uiState.value.selectedRoute ?: return
        if (selectedRoute.id != routeId) return

        // A realtime event is emitted by the database after a successful GPS write;
        // trust the server timestamp rather than comparing clocks on two devices.
        if (location == null) {
            val active = _uiState.value.activeVehicles.filter { it.id != vehicleId }
            _uiState.value = _uiState.value.copy(activeVehicles = active)
            if (_uiState.value.nearestVehicleTrackingEnabled) {
                (_uiState.value.passengerLocation ?: _uiState.value.mapCenterLocation)?.let { updateNearestVehicle(it, active) }
            }
            return
        }

        val currentVehicle = _uiState.value.activeVehicles.firstOrNull { it.id == vehicleId }
        if (currentVehicle != null) {
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
                val active = freshVehicles(current.activeVehicles)
                // Do not age server-confirmed vehicles using the passenger phone's
                // clock. The 10-second server poll removes vehicles when the
                // database's one-minute freshness window expires.
                if (active.size != current.activeVehicles.size) {
                    val activeIds = active.mapTo(mutableSetOf()) { it.id }
                    val nearest = current.nearestVehicle?.takeIf { it.id in activeIds }
                    _uiState.value = current.copy(
                        activeVehicles = active,
                        nearestVehicle = nearest,
                        nearestVehicleDistanceMeters = if (nearest != null) current.nearestVehicleDistanceMeters else null,
                        nearestVehicleEtaMinutes = if (nearest != null) current.nearestVehicleEtaMinutes else null,
                    )
                }
            }
        }
    }

    // Freshness is calculated by PostgreSQL in vehicle_current_locations using
    // server time. Filtering these rows again with the phone clock caused valid
    // drivers to disappear when a device clock was ahead of the server.
    //
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
                val previousHeading = previousById[vehicle.id]?.currentLocation?.headingDegrees
                if (location.headingDegrees == null && previousHeading != null) {
                    vehicle.copy(currentLocation = location.copy(headingDegrees = previousHeading))
                } else {
                    vehicle
                }
            }
    }

    fun onLocationPermissionResult(granted: Boolean) {
        _uiState.value = _uiState.value.copy(locationPermissionRequired = !granted)
        if (granted) {
            startLocationUpdates()
            requestCurrentLocation(centerCamera = false)
        } else {
            stopLocationUpdates()
            lastPassengerFixAtMs = 0L
            _uiState.value = _uiState.value.copy(passengerLocation = null)
            _uiState.value.selectedRoute?.id?.let { routeId ->
                viewModelScope.launch { runCatching { realtimeTransport.removePassengerLocation(routeId) } }
            }
        }
    }

    fun refreshLocationPermissionState() {
        val granted = hasLocationPermission()
        _uiState.value = _uiState.value.copy(locationPermissionRequired = !granted)
        if (granted) startLocationUpdates()
    }

    private fun startLocationUpdates() {
        if (locationJob?.isActive == true) return
        locationProvider.lastKnownLocation { point ->
            if (point != null) {
                lastPassengerFixAtMs = System.currentTimeMillis()
                _uiState.value = _uiState.value.copy(passengerLocation = point)
                if (_uiState.value.nearestVehicleTrackingEnabled) updateNearestVehicle(point, _uiState.value.activeVehicles)
            }
        }
        requestCurrentLocation(centerCamera = false)
        locationJob = viewModelScope.launch {
            try {
                locationProvider.locations().collectLatest { point ->
                    lastPassengerFixAtMs = System.currentTimeMillis()
                    _uiState.value = _uiState.value.copy(passengerLocation = point)
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
        locationProvider.currentLocationSample { sample ->
            if (sample != null) {
                lastPassengerFixAtMs = System.currentTimeMillis()
                _uiState.value = _uiState.value.copy(
                    passengerLocation = sample.point,
                    locateRequestId = if (centerCamera) _uiState.value.locateRequestId + 1L else _uiState.value.locateRequestId,
                )
            } else {
                LocationServices.getFusedLocationProviderClient(getApplication<Application>())
                    .lastLocation
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            lastPassengerFixAtMs = System.currentTimeMillis()
                            _uiState.value = _uiState.value.copy(
                                passengerLocation = GeoPoint(location.latitude, location.longitude),
                                locateRequestId = if (centerCamera) _uiState.value.locateRequestId + 1L else _uiState.value.locateRequestId,
                            )
                        }
                    }
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
            locationProvider.lastKnownLocation { point ->
                if (point != null) {
                    lastPassengerFixAtMs = System.currentTimeMillis()
                    _uiState.value = _uiState.value.copy(
                        passengerLocation = point,
                        locateRequestId = _uiState.value.locateRequestId + 1L,
                    )
                }
            }
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
        super.onCleared()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val STALE_SWEEP_INTERVAL_MS = 5_000L
        const val PASSENGER_BROADCAST_INTERVAL_MS = 5_000L
        const val PASSENGER_FIX_MAX_AGE_MS = 15_000L
    }
}
