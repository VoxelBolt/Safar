package com.safar.app.ui.screens

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PassengerMapUiState(
    val routes: List<Route> = emptyList(),
    val recentRoutes: List<Route> = emptyList(),
    val selectedRoute: Route? = null,
    val activeVehicles: List<Vehicle> = emptyList(),
    val passengerLocation: GeoPoint? = null,
    val headingDegrees: Float? = null,
    val locateRequestId: Long = 0L,
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
    private var realtimeJob: Job? = null
    private var staleSweepJob: Job? = null
    private var headingJob: Job? = null

    init {
        refreshLocationPermissionState()
        startStaleSweep()
    }

    fun loadRoutes() {
        if (_uiState.value.loadingRoutes) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loadingRoutes = true, error = null)
            runCatching {
                routeRepository.getRoutes().sortedBy { it.number }
            }.onSuccess { routes ->
                val selected = _uiState.value.selectedRoute
                    ?.let { old -> routes.firstOrNull { it.id == old.id } }
                val effective = selected ?: routes.firstOrNull()
                _uiState.value = _uiState.value.copy(
                    routes = routes,
                    selectedRoute = effective,
                    loadingRoutes = false,
                )
                effective?.let {
                    loadVehicles(it)
                    switchRealtime(it.id)
                }
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    loadingRoutes = false,
                    error = error.message ?: "Не удалось загрузить маршруты",
                )
            }
        }
    }

    fun selectRoute(route: Route) {
        _uiState.value = _uiState.value.copy(
            selectedRoute = route,
            recentRoutes = buildList {
                add(route)
                _uiState.value.recentRoutes
                    .filter { it.id != route.id }
                    .take(2)
                    .forEach(::add)
            },
            activeVehicles = emptyList(),
            error = null,
        )
        loadVehicles(route)
        switchRealtime(route.id)
    }

    private fun loadVehicles(route: Route) {
        vehicleRefreshJob?.cancel()
        vehicleRefreshJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loadingVehicles = true)
            runCatching { vehicleRepository.getActiveVehiclesForRoute(route.id) }
                .onSuccess { vehicles ->
                    _uiState.value = _uiState.value.copy(
                        activeVehicles = freshVehicles(vehicles),
                        loadingVehicles = false,
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        loadingVehicles = false,
                        error = error.message ?: "Не удалось получить транспорт",
                    )
                }
        }
    }

    private fun switchRealtime(routeId: String) {
        realtimeJob?.cancel()
        realtimeTransport.disconnect()

        realtimeJob = viewModelScope.launch {
            try {
                realtimeTransport.connect(routeId)
                realtimeTransport.vehicleEvents.collect { event ->
                    applyRealtimeEvent(event.vehicleId, event.routeId, event.location)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (currentCoroutineContext().isActive) {
                    _uiState.value = _uiState.value.copy(
                        error = error.message ?: "Realtime недоступен",
                    )
                }
            }
        }
    }

    private fun applyRealtimeEvent(
        vehicleId: String,
        routeId: String,
        location: VehicleLocation?,
    ) {
        val selectedRoute = _uiState.value.selectedRoute ?: return
        if (selectedRoute.id != routeId) return

        if (location == null) {
            _uiState.value = _uiState.value.copy(
                activeVehicles = _uiState.value.activeVehicles.filter { it.id != vehicleId },
            )
            return
        }

        if (!isFresh(location.timestampEpochMs)) {
            _uiState.value = _uiState.value.copy(
                activeVehicles = _uiState.value.activeVehicles.filter { it.id != vehicleId },
            )
            return
        }

        val currentVehicles = _uiState.value.activeVehicles
        val currentVehicle = currentVehicles.firstOrNull { it.id == vehicleId }

        if (currentVehicle != null) {
            val updated = currentVehicle.copy(
                currentLocation = location,
                active = true,
            )
            _uiState.value = _uiState.value.copy(
                activeVehicles = currentVehicles.map {
                    if (it.id == vehicleId) updated else it
                }.filter { isFresh(it.currentLocation?.timestampEpochMs) },
                error = null,
            )
            return
        }

        vehicleRefreshJob?.cancel()
        vehicleRefreshJob = viewModelScope.launch {
            runCatching { vehicleRepository.getActiveVehiclesForRoute(selectedRoute.id) }
                .onSuccess { vehicles ->
                    _uiState.value = _uiState.value.copy(
                        activeVehicles = freshVehicles(vehicles),
                        error = null,
                    )
                }
        }
    }

    private fun startStaleSweep() {
        if (staleSweepJob?.isActive == true) return
        staleSweepJob = viewModelScope.launch {
            while (isActive) {
                delay(STALE_SWEEP_INTERVAL_MS)
                val fresh = freshVehicles(_uiState.value.activeVehicles)
                if (fresh.size != _uiState.value.activeVehicles.size) {
                    _uiState.value = _uiState.value.copy(activeVehicles = fresh)
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
        if (granted) startLocationUpdates() else stopLocationUpdates()
    }

    fun refreshLocationPermissionState() {
        val granted = ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        onLocationPermissionResult(granted)
    }

    private fun startLocationUpdates() {
        if (locationJob != null) return
        locationProvider.lastKnownLocation { point ->
            if (point != null) _uiState.value = _uiState.value.copy(passengerLocation = point)
        }
        locationProvider.currentLocation { point ->
            if (point != null) _uiState.value = _uiState.value.copy(passengerLocation = point)
        }
        locationJob = viewModelScope.launch {
            locationProvider.locations().collect { point ->
                _uiState.value = _uiState.value.copy(passengerLocation = point)
            }
        }
        headingJob?.cancel()
        headingJob = viewModelScope.launch {
            locationProvider.headingDegrees().collect { heading ->
                _uiState.value = _uiState.value.copy(headingDegrees = heading)
            }
        }
    }

    private fun stopLocationUpdates() {
        locationJob?.cancel()
        locationJob = null
        headingJob?.cancel()
        headingJob = null
    }

    fun refreshPassengerLocation() {
        refreshLocationPermissionState()
        if (_uiState.value.locationPermissionRequired) return

        locationProvider.currentLocation { point ->
            if (point != null) {
                _uiState.value = _uiState.value.copy(
                    passengerLocation = point,
                    locateRequestId = _uiState.value.locateRequestId + 1L,
                )
            }
        }
    }

    override fun onCleared() {
        stopLocationUpdates()
        realtimeJob?.cancel()
        staleSweepJob?.cancel()
        vehicleRefreshJob?.cancel()
        realtimeTransport.disconnect()
        super.onCleared()
    }

    private companion object {
        const val LOCATION_FRESHNESS_MS = 25_000L
        const val STALE_SWEEP_INTERVAL_MS = 5_000L
    }
}