package com.safar.app.ui.screens

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PassengerMapUiState(
    val routes: List<Route> = emptyList(),
    val selectedRoute: Route? = null,
    val selectedVehicle: Vehicle? = null,
    val vehicleLocations: List<Pair<Vehicle, VehicleLocation>> = emptyList(),
    val passengerLocation: GeoPoint? = null,
    val headingDegrees: Float? = null,
    val locationPermissionGranted: Boolean = false,
    val realtimeConnected: Boolean = false,
    val locateRequestId: Long = 0L,
    val loading: Boolean = false,
    val error: String? = null,
)

class PassengerMapViewModel(application: Application) : AndroidViewModel(application) {
    private val routeRepository = AppContainer.routeRepository
    private val vehicleRepository = AppContainer.vehicleRepository
    private val driverLocationRepository = AppContainer.driverLocationRepository
    private val locationProvider = AndroidLocationProvider(application)
    private val realtimeTransport: RealtimeTransport = AppContainer.realtimeTransport

    private val _uiState = MutableStateFlow(PassengerMapUiState())
    val uiState: StateFlow<PassengerMapUiState> = _uiState.asStateFlow()

    private var realtimeJob: Job? = null
    private var locationJob: Job? = null
    private var headingJob: Job? = null
    private var locationRetryJob: Job? = null

    init {
        _uiState.value = _uiState.value.copy(
            locationPermissionGranted = hasLocationPermission(),
        )
        loadRoutes()
        startHeading()
        startLocationUpdates()
    }

    fun loadRoutes() {
        if (_uiState.value.loading) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            runCatching {
                val routes = routeRepository.getRoutes().sortedBy { it.number }
                routes
            }.onSuccess { routes ->
                _uiState.value = _uiState.value.copy(routes = routes, loading = false)
                val saved = _uiState.value.selectedRoute
                if (saved != null) {
                    connectRoute(saved)
                }
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = error.userMessage(),
                )
            }
        }
    }

    fun onLocationPermissionChanged(granted: Boolean) {
        _uiState.value = _uiState.value.copy(locationPermissionGranted = granted)
        if (granted) {
            restartLocationUpdates()
            requestCurrentLocation()
        } else {
            locationJob?.cancel()
            locationRetryJob?.cancel()
            _uiState.value = _uiState.value.copy(passengerLocation = null)
        }
    }

    fun selectRoute(route: Route) {
        if (_uiState.value.selectedRoute?.id == route.id) {
            _uiState.value = _uiState.value.copy(selectedRoute = null, selectedVehicle = null, vehicleLocations = emptyList())
            realtimeJob?.cancel()
            realtimeTransport.disconnect()
            return
        }
        _uiState.value = _uiState.value.copy(
            selectedRoute = route,
            selectedVehicle = null,
            vehicleLocations = emptyList(),
            realtimeConnected = false,
        )
        connectRoute(route)
    }

    fun selectVehicle(vehicle: Vehicle?) {
        _uiState.value = _uiState.value.copy(selectedVehicle = vehicle)
    }

    fun locateUser() {
        _uiState.value = _uiState.value.copy(locateRequestId = _uiState.value.locateRequestId + 1)
        requestCurrentLocation()
    }

    private fun connectRoute(route: Route) {
        realtimeJob?.cancel()
        realtimeTransport.disconnect()
        _uiState.value = _uiState.value.copy(vehicleLocations = emptyList(), realtimeConnected = false)
        realtimeJob = viewModelScope.launch {
            try {
                realtimeTransport.connect(route.id)
                _uiState.value = _uiState.value.copy(realtimeConnected = true)
                refreshCurrentVehicleLocations(route)
                realtimeTransport.vehicleEvents.collectLatest { event ->
                    if (_uiState.value.selectedRoute?.id != event.routeId) return@collectLatest
                    applyVehicleEvent(event)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (currentCoroutineContext().isActive) {
                    _uiState.value = _uiState.value.copy(
                        realtimeConnected = false,
                        error = error.userMessage(),
                    )
                }
            }
        }
    }

    private suspend fun refreshCurrentVehicleLocations(route: Route) {
        runCatching {
            driverLocationRepository.getCurrentLocations(routeId = route.id)
        }.onSuccess { current ->
            val vehicles = current.mapNotNull { location ->
                vehicleRepository.findById(location.vehicleId)?.let { vehicle ->
                    vehicle to location
                }
            }.filter { (_, location) -> isFresh(location.timestampEpochMs) }
            if (_uiState.value.selectedRoute?.id == route.id) {
                _uiState.value = _uiState.value.copy(vehicleLocations = vehicles)
            }
        }.onFailure { error ->
            if (_uiState.value.selectedRoute?.id == route.id) {
                _uiState.value = _uiState.value.copy(error = error.userMessage())
            }
        }
    }

    private fun applyVehicleEvent(event: com.safar.app.data.realtime.RealtimeVehicleEvent) {
        val route = _uiState.value.selectedRoute ?: return
        if (route.id != event.routeId) return

        if (event.location == null) {
            _uiState.value = _uiState.value.copy(
                vehicleLocations = _uiState.value.vehicleLocations.filterNot { it.first.id == event.vehicleId }
            )
            return
        }

        if (!isFresh(event.location.timestampEpochMs)) {
            _uiState.value = _uiState.value.copy(
                vehicleLocations = _uiState.value.vehicleLocations.filterNot { it.first.id == event.vehicleId }
            )
            return
        }

        viewModelScope.launch {
            val vehicle = vehicleRepository.findById(event.vehicleId) ?: return@launch
            val updated = _uiState.value.vehicleLocations.filterNot { it.first.id == vehicle.id } + (vehicle to event.location)
            _uiState.value = _uiState.value.copy(vehicleLocations = updated)
        }
    }

    private fun startLocationUpdates() {
        locationJob?.cancel()
        if (!hasLocationPermission()) return
        locationJob = viewModelScope.launch {
            try {
                locationProvider.locationSamples().collectLatest { sample ->
                    _uiState.value = _uiState.value.copy(passengerLocation = sample.point)
                }
            } catch (_: CancellationException) {
                throw
            } catch (_: Exception) {
                scheduleLocationRetry()
            }
        }
    }

    private fun restartLocationUpdates() {
        startLocationUpdates()
    }

    private fun scheduleLocationRetry() {
        locationRetryJob?.cancel()
        locationRetryJob = viewModelScope.launch {
            while (isActive && hasLocationPermission()) {
                delay(2_000L)
                startLocationUpdates()
                requestCurrentLocation()
                if (_uiState.value.passengerLocation != null) break
            }
        }
    }

    private fun requestCurrentLocation() {
        if (!hasLocationPermission()) return
        locationProvider.currentLocation { point ->
            if (point != null) {
                _uiState.value = _uiState.value.copy(passengerLocation = point)
            } else {
                LocationServices.getFusedLocationProviderClient(getApplication<Application>())
                    .lastLocation
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            _uiState.value = _uiState.value.copy(
                                passengerLocation = GeoPoint(location.latitude, location.longitude)
                            )
                        }
                    }
            }
        }
    }

    private fun startHeading() {
        headingJob?.cancel()
        headingJob = viewModelScope.launch {
            try {
                locationProvider.headingDegrees().collectLatest { heading ->
                    _uiState.value = _uiState.value.copy(headingDegrees = heading)
                }
            } catch (_: CancellationException) {
                throw
            } catch (_: Exception) {
            }
        }
    }

    override fun onCleared() {
        realtimeJob?.cancel()
        locationJob?.cancel()
        headingJob?.cancel()
        locationRetryJob?.cancel()
        realtimeTransport.disconnect()
        super.onCleared()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isFresh(timestampEpochMs: Long): Boolean =
        timestampEpochMs > 0L && System.currentTimeMillis() - timestampEpochMs <= LOCATION_FRESHNESS_MS

    private fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() } ?: "Не удалось загрузить данные"

    private companion object {
        const val LOCATION_FRESHNESS_MS = 60_000L
    }
}
