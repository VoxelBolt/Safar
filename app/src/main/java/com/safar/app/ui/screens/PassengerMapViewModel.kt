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
                    val selected = _uiState.value.selectedRoute?.let { old -> routes.firstOrNull { it.id == old.id } }
                    val effective = selected ?: routes.firstOrNull()
                    _uiState.value = _uiState.value.copy(
                        routes = routes,
                        selectedRoute = effective,
                        loadingRoutes = false,
                    )
                    effective?.let {
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

                // The nearest action must consider every currently visible
                // vehicle, not only the passenger's selected route.
                // getAllVehicles() reads the current-state view, so only vehicles
                // with a fresh server GPS position are eligible.
                val vehicles = runCatching { vehicleRepository.getAllVehicles() }
                    .getOrElse {
                        // Network/RLS fallback: at least keep the selected route usable.
                        val routeId = state.selectedRoute?.id
                        if (routeId == null) emptyList()
                        else runCatching { vehicleRepository.getVehiclesForRoute(routeId) }.getOrDefault(emptyList())
                    }
                    .filter { vehicle ->
                        val location = vehicle.currentLocation
                        location != null &&
                            location.latitude.isFinite() &&
                            location.longitude.isFinite() &&
                            isFresh(location.timestampEpochMs)
                    }
                    .map { it.copy(active = true) }

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
        switchRealtime(route.id)
        if (oldRouteId != null && oldRouteId != route.id) {
            viewModelScope.launch { runCatching { realtimeTransport.removePassengerLocation(oldRouteId) } }
        }
    }

    private fun loadVehicles(route: Route) {
        vehicleRefreshJob?.cancel()
        vehicleRefreshJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loadingVehicles = true)
            runCatching { vehicleRepository.getVehiclesForRoute(route.id) }
                .onSuccess { vehicles ->
                    val fresh = freshVehicles(vehicles).map { it.copy(active = true) }
                    android.util.Log.d(
                        "SafarVehicle",
                        "initial route=${route.id} vehicles=${fresh.size} locations=" +
                            fresh.joinToString { "${it.identifier}:${it.currentLocation?.latitude},${it.currentLocation?.longitude}" },
                    )
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
                runCatching { vehicleRepository.getVehiclesForRoute(routeId) }
                    .onSuccess { vehicles ->
                        val active = freshVehicles(vehicles).map { it.copy(active = true) }
                        _uiState.value = _uiState.value.copy(activeVehicles = active, error = null)
                        if (_uiState.value.nearestVehicleTrackingEnabled) {
                            (_uiState.value.passengerLocation ?: _uiState.value.mapCenterLocation)?.let { updateNearestVehicle(it, active) }
                        }
                        android.util.Log.d(
                            "SafarVehicle",
                            "poll route=$routeId vehicles=${active.size} locations=" +
                                active.joinToString { "${it.identifier}:${it.currentLocation?.latitude},${it.currentLocation?.longitude}" },
                        )
                    }
                    .onFailure { error -> android.util.Log.w("SafarVehicle", "poll failed", error) }
                delay(10_000L)
            }
        }
    }
    private fun switchRealtime(routeId: String) {
        realtimeJob?.cancel()
        realtimeTransport.disconnect()
        realtimeJob = viewModelScope.launch {
            try {
                realtimeTransport.connect(routeId)
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

        if (location == null || !isFresh(location.timestampEpochMs)) {
            val active = _uiState.value.activeVehicles.filter { it.id != vehicleId }
            _uiState.value = _uiState.value.copy(activeVehicles = active)
            if (_uiState.value.nearestVehicleTrackingEnabled) {
                (_uiState.value.passengerLocation ?: _uiState.value.mapCenterLocation)?.let { updateNearestVehicle(it, active) }
            }
            return
        }

        val currentVehicle = _uiState.value.activeVehicles.firstOrNull { it.id == vehicleId }
        if (currentVehicle != null) {
            val updated = currentVehicle.copy(currentLocation = location, active = true)
            val active = _uiState.value.activeVehicles.map { if (it.id == vehicleId) updated else it }
            _uiState.value = _uiState.value.copy(activeVehicles = active, error = null)
            if (_uiState.value.nearestVehicleTrackingEnabled) {
                (_uiState.value.passengerLocation ?: _uiState.value.mapCenterLocation)?.let { updateNearestVehicle(it, active) }
            }
            return
        }

        vehicleRefreshJob?.cancel()
        vehicleRefreshJob = viewModelScope.launch {
            runCatching { vehicleRepository.getVehiclesForRoute(selectedRoute.id) }
                .onSuccess { vehicles ->
                    val active = freshVehicles(vehicles).map { it.copy(active = true) }
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
                delay(10_000L)
                val state = _uiState.value
                if (!state.nearestVehicleTrackingEnabled) continue
                val passenger = state.passengerLocation ?: state.mapCenterLocation ?: continue

                // Re-read all current vehicle positions so the selected nearest
                // vehicle can change as vehicles move. This is intentionally
                // independent from the selected route.
                val allVehicles = runCatching { vehicleRepository.getAllVehicles() }
                    .getOrDefault(emptyList())
                    .filter { vehicle ->
                        val location = vehicle.currentLocation
                        location != null &&
                            location.latitude.isFinite() &&
                            location.longitude.isFinite() &&
                            isFresh(location.timestampEpochMs)
                    }
                    .map { it.copy(active = true) }

                if (allVehicles.isEmpty()) continue
                updateNearestVehicle(passenger, allVehicles)
            }
        }
    }

    private fun startStaleSweep() {
        if (staleSweepJob?.isActive == true) return
        staleSweepJob = viewModelScope.launch {
            while (isActive) {
                delay(STALE_SWEEP_INTERVAL_MS)
                val current = _uiState.value
                val fresh = freshVehicles(current.activeVehicles)
                val nearestStillFresh = current.nearestVehicle?.currentLocation?.timestampEpochMs
                    ?.let(::isFresh) == true
                if (fresh.size != current.activeVehicles.size || !nearestStillFresh && current.nearestVehicle != null) {
                    _uiState.value = current.copy(
                        activeVehicles = fresh,
                        nearestVehicle = if (nearestStillFresh) current.nearestVehicle else null,
                        nearestVehicleDistanceMeters = if (nearestStillFresh) current.nearestVehicleDistanceMeters else null,
                        nearestVehicleEtaMinutes = if (nearestStillFresh) current.nearestVehicleEtaMinutes else null,
                    )
                }
            }
        }
    }

    private fun freshVehicles(vehicles: List<Vehicle>): List<Vehicle> =
        vehicles.filter { isFresh(it.currentLocation?.timestampEpochMs) }

    private fun isFresh(timestampEpochMs: Long?): Boolean {
        if (timestampEpochMs == null || timestampEpochMs <= 0L) return false
        return System.currentTimeMillis() - timestampEpochMs <= LOCATION_FRESHNESS_MS
    }

    fun onLocationPermissionResult(granted: Boolean) {
        _uiState.value = _uiState.value.copy(locationPermissionRequired = !granted)
        if (granted) {
            startLocationUpdates()
            requestCurrentLocation(centerCamera = false)
        } else {
            stopLocationUpdates()
            _uiState.value = _uiState.value.copy(passengerLocation = null)
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
                _uiState.value = _uiState.value.copy(passengerLocation = point)
                if (_uiState.value.nearestVehicleTrackingEnabled) updateNearestVehicle(point, _uiState.value.activeVehicles)
            }
        }
        requestCurrentLocation(centerCamera = false)
        locationJob = viewModelScope.launch {
            try {
                locationProvider.locations().collectLatest { point ->
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
                _uiState.value = _uiState.value.copy(
                    passengerLocation = sample.point,
                    locateRequestId = if (centerCamera) _uiState.value.locateRequestId + 1L else _uiState.value.locateRequestId,
                )
            } else {
                LocationServices.getFusedLocationProviderClient(getApplication<Application>())
                    .lastLocation
                    .addOnSuccessListener { location ->
                        if (location != null) {
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
        // GPS records can arrive with small network/realtime delays. Keep a
        // few minutes of history usable so the nearest vehicle and ETA do not
        // disappear between two location updates.
        const val LOCATION_FRESHNESS_MS = 5 * 60_000L
        const val STALE_SWEEP_INTERVAL_MS = 15_000L
    }
}
