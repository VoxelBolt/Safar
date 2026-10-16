package com.safar.app.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.safar.app.data.realtime.RealtimeTransport
import com.safar.app.data.location.DriverLocationService
import com.safar.app.di.AppContainer
import com.safar.app.domain.model.PassengerLocation
import com.safar.app.domain.model.Driver
import com.safar.app.domain.model.Route
import com.safar.app.domain.model.WorkSession
import com.safar.app.domain.model.WorkSessionStatus
import com.safar.app.domain.model.Vehicle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.Instant

data class DriverUiState(
    val driver: Driver? = null,
    val routes: List<Route> = emptyList(),
    val selectedRoute: Route? = null,
    val workSession: WorkSession? = null,
    val passengerLocations: List<PassengerLocation> = emptyList(),
    val activeVehicles: List<Vehicle> = emptyList(),
    val loading: Boolean = false,
    val actionInProgress: Boolean = false,
    val error: String? = null,
)

class DriverViewModel(application: Application) : AndroidViewModel(application) {
    private val driverRepository = AppContainer.driverRepository
    private val routeRepository = AppContainer.routeRepository
    private val workSessionRepository = AppContainer.workSessionRepository
    private val vehicleRepository = AppContainer.vehicleRepository
    private val realtimeTransport: RealtimeTransport = AppContainer.realtimeTransport

    private val _uiState = MutableStateFlow(DriverUiState())
    val uiState: StateFlow<DriverUiState> = _uiState.asStateFlow()

    private var realtimeJob: Job? = null
    private var staleSweepJob: Job? = null
    private var vehiclePollingJob: Job? = null

    init {
        startPassengerStaleSweep()
        load()
    }

    fun load() {
        if (_uiState.value.loading) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            runCatching {
                withContext(Dispatchers.IO) {
                    withTimeout(BACKEND_TIMEOUT_MS) {
                        val driver = driverRepository.getDriver()
                        val routes = routeRepository.getRoutes().sortedBy { it.number }
                        val session = workSessionRepository.getCurrent()
                        Triple(driver, routes, session)
                    }
                }
            }.onSuccess { (driver, routes, session) ->
                val selected = driver?.routeId?.let { id -> routes.firstOrNull { it.id == id } }
                _uiState.value = _uiState.value.copy(driver = driver, routes = routes, selectedRoute = selected, workSession = session, loading = false, error = null)
                selected?.let {
                    switchPassengerRealtime(it.id)
                    startVehiclePolling(it.id)
                }
                if (driver?.routeId != null) {
                    // Work Session and GPS tracking are independent systems. Restore
                    // the location foreground service whenever a routed driver is loaded.
                    runCatching { DriverLocationService.start(getApplication()) }
                }
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(loading = false, error = error.userMessage())
            }
        }
    }

    fun selectRoute(route: Route) {
        val ui = _uiState.value
        if (ui.actionInProgress || ui.workSession?.status == WorkSessionStatus.ACTIVE || ui.workSession?.status == WorkSessionStatus.PAUSED) return
        viewModelScope.launch {
            _uiState.value = ui.copy(actionInProgress = true, error = null)
            runCatching {
                withContext(Dispatchers.IO) {
                    withTimeout(BACKEND_TIMEOUT_MS) { driverRepository.assignRoute(route.id) }
                }
            }
                .onSuccess { driver ->
                    _uiState.value = _uiState.value.copy(driver = driver, selectedRoute = route, passengerLocations = emptyList(), activeVehicles = emptyList(), actionInProgress = false, error = null)
                    startVehiclePolling(route.id)
                    // A route assignment enables GPS tracking; Work Session state does not.
                    runCatching { DriverLocationService.start(getApplication()) }
                    switchPassengerRealtime(route.id)
                }.onFailure { error ->
                    _uiState.value = _uiState.value.copy(actionInProgress = false, error = error.userMessage())
                }
        }
    }

    private fun startVehiclePolling(routeId: String) {
        vehiclePollingJob?.cancel()
        vehiclePollingJob = viewModelScope.launch {
            while (isActive && _uiState.value.selectedRoute?.id == routeId) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        withTimeout(BACKEND_TIMEOUT_MS) { vehicleRepository.getVehiclesForRoute(routeId) }
                    }
                }
                    .onSuccess { vehicles ->
                        val active = vehicles
                            .filter { it.active && isFresh(it.currentLocation?.timestampEpochMs) && it.currentLocation != null }
                            .map { it.copy(active = true) }
                        _uiState.value = _uiState.value.copy(activeVehicles = active, error = null)
                    }
                    .onFailure { error ->
                        android.util.Log.w("SafarDriverVehicle", "poll failed", error)
                    }
                delay(VEHICLE_POLL_INTERVAL_MS)
            }
        }
    }

    private fun switchPassengerRealtime(routeId: String) {
        realtimeJob?.cancel()
        realtimeTransport.disconnect()
        _uiState.value = _uiState.value.copy(passengerLocations = emptyList())
        realtimeJob = viewModelScope.launch {
            try {
                realtimeTransport.connect(routeId)
                realtimeTransport.passengerEvents.collect { event ->
                    if (_uiState.value.selectedRoute?.id != event.routeId) return@collect
                    applyPassengerEvent(event.passengerId, event.routeId, event.location)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (currentCoroutineContext().isActive) _uiState.value = _uiState.value.copy(error = error.message ?: "Realtime недоступен")
            }
        }
    }

    private fun applyPassengerEvent(passengerId: String, routeId: String, location: PassengerLocation?) {
        if (_uiState.value.selectedRoute?.id != routeId) return
        val current = _uiState.value.passengerLocations
        if (location == null || !isPassengerFresh(location.receivedAtEpochMs)) {
            _uiState.value = _uiState.value.copy(passengerLocations = current.filter { it.passengerId != passengerId })
            return
        }
        val updated = current.firstOrNull { it.passengerId == passengerId }
        _uiState.value = _uiState.value.copy(
            passengerLocations = if (updated == null) current + location else current.map { if (it.passengerId == passengerId) location else it },
            error = null,
        )
    }

    private fun startPassengerStaleSweep() {
        staleSweepJob = viewModelScope.launch {
            while (isActive) {
                delay(PASSENGER_STALE_SWEEP_INTERVAL_MS)
                val fresh = _uiState.value.passengerLocations.filter { isPassengerFresh(it.receivedAtEpochMs) }
                if (fresh.size != _uiState.value.passengerLocations.size) _uiState.value = _uiState.value.copy(passengerLocations = fresh)
            }
        }
    }

    private fun isFresh(timestampEpochMs: Long?): Boolean = timestampEpochMs != null && timestampEpochMs > 0L && System.currentTimeMillis() - timestampEpochMs in 0L..LOCATION_FRESHNESS_MS

    // Passenger broadcasts are transient and use local receive time for expiry,
    // so sender clock skew cannot keep an abandoned marker alive or hide a live one.
    private fun isPassengerFresh(receivedAtEpochMs: Long): Boolean =
        System.currentTimeMillis() - receivedAtEpochMs in 0L..LOCATION_FRESHNESS_MS

    /**
     * Foreground-screen fallback for driver GPS.
     *
     * The foreground service remains the primary/background publisher. While the
     * driver screen is visible, publish the same current position directly as a
     * second path. This makes an active trip resilient if Android has stopped or
     * delayed the foreground service.
     */
    fun publishDriverLocation(point: com.safar.app.domain.model.GeoPoint, headingDegrees: Float?, timestampEpochMs: Long) {
        val ui = _uiState.value
        if (ui.driver?.routeId == null) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    withTimeout(BACKEND_TIMEOUT_MS) {
                        AppContainer.driverLocationRepository.submitLocation(
                            latitude = point.latitude,
                            longitude = point.longitude,
                            recordedAtIso = Instant.ofEpochMilli(timestampEpochMs).toString(),
                            headingDegrees = headingDegrees,
                        )
                    }
                }
            }.onSuccess { location ->
                android.util.Log.d(
                    "SafarDriverGPS",
                    "Foreground GPS saved: vehicle=${location.vehicleId}, heading=${location.headingDegrees}",
                )
                // This foreground fallback used to write to the database only.
                // Broadcast the same measured heading too, so passengers can rotate
                // the vehicle marker immediately even if the foreground service is delayed.
                viewModelScope.launch {
                    runCatching {
                        val driver = ui.driver ?: return@launch
                        val routeId = driver.routeId ?: return@launch
                        val vehicleId = driver.vehicleId
                        realtimeTransport.connect(routeId)
                        realtimeTransport.sendDriverLocation(
                            routeId = routeId,
                            vehicleId = vehicleId,
                            latitude = point.latitude,
                            longitude = point.longitude,
                            recordedAtIso = Instant.ofEpochMilli(timestampEpochMs).toString(),
                            headingDegrees = location.headingDegrees ?: headingDegrees,
                        )
                    }.onFailure { error ->
                        android.util.Log.w("SafarDriverGPS", "Foreground realtime heading broadcast failed", error)
                    }
                }
            }.onFailure { error ->
                android.util.Log.e("SafarDriverGPS", "Foreground screen GPS publish failed", error)
            }
        }
    }

    fun startWork() {
        val ui = _uiState.value
        if (ui.actionInProgress) return
        if (ui.selectedRoute == null) { _uiState.value = ui.copy(error = "Сначала выберите маршрут"); return }
        if (ui.workSession?.status == WorkSessionStatus.ACTIVE || ui.workSession?.status == WorkSessionStatus.PAUSED) { _uiState.value = ui.copy(error = "Текущая рабочая сессия уже существует"); return }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(actionInProgress = true, error = null)
            runCatching {
                withContext(Dispatchers.IO) {
                    withTimeout(BACKEND_TIMEOUT_MS) { workSessionRepository.start() }
                }
            }.onSuccess { session ->
                _uiState.value = _uiState.value.copy(workSession = session, actionInProgress = false, error = null)
                if (session.status == WorkSessionStatus.ACTIVE) {
                    runCatching { DriverLocationService.start(getApplication()) }
                }
            }.onFailure { error -> _uiState.value = _uiState.value.copy(actionInProgress = false, error = error.userMessage()) }
        }
    }

    fun pauseWork() = changeSession { id -> workSessionRepository.pause(id) }

    fun resumeWork() {
        val session = _uiState.value.workSession
        if (session?.status != WorkSessionStatus.PAUSED) { _uiState.value = _uiState.value.copy(error = "Нет приостановленной рабочей сессии"); return }
        val pausedAt = session.pauseTimeEpochMs
        if (pausedAt != null && System.currentTimeMillis() - pausedAt > SIX_HOURS_MS) {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(actionInProgress = true, error = null)
                runCatching {
                    withContext(Dispatchers.IO) {
                        withTimeout(BACKEND_TIMEOUT_MS) { workSessionRepository.finish(session.id) }
                    }
                }.onSuccess {
                    _uiState.value = _uiState.value.copy(workSession = null, actionInProgress = false, error = "Предыдущая сессия завершена после 6 часов паузы. Начните новую сессию.")
                }.onFailure { error -> _uiState.value = _uiState.value.copy(actionInProgress = false, error = error.userMessage()) }
            }
        } else changeSession { id -> workSessionRepository.resume(id) }
    }

    fun finishWork() = changeSession { id -> workSessionRepository.finish(id) }

    private fun changeSession(action: suspend (String) -> WorkSession) {
        val session = _uiState.value.workSession
        if (_uiState.value.actionInProgress) return
        if (session == null) { _uiState.value = _uiState.value.copy(error = "Текущей рабочей сессии нет"); return }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(actionInProgress = true, error = null)
            runCatching {
                withContext(Dispatchers.IO) {
                    withTimeout(BACKEND_TIMEOUT_MS) { action(session.id) }
                }
            }.onSuccess { updatedSession ->
                _uiState.value = _uiState.value.copy(workSession = updatedSession, actionInProgress = false, error = null)
                // Never stop GPS when the Work Session changes. Tracking is owned
                // by DriverLocationService and continues independently.
                if (_uiState.value.driver?.routeId != null) {
                    runCatching { DriverLocationService.start(getApplication()) }
                }
            }.onFailure { error ->
                val refreshed = runCatching {
                    withContext(Dispatchers.IO) {
                        withTimeout(BACKEND_TIMEOUT_MS) { workSessionRepository.getCurrent() }
                    }
                }.getOrNull()
                _uiState.value = _uiState.value.copy(workSession = refreshed, actionInProgress = false, error = error.userMessage())
                if (_uiState.value.driver?.routeId != null) {
                    runCatching { DriverLocationService.start(getApplication()) }
                }
            }
        }
    }

    private fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() } ?: "Не удалось выполнить действие"

    override fun onCleared() {
        realtimeJob?.cancel()
        staleSweepJob?.cancel()
        vehiclePollingJob?.cancel()
        realtimeTransport.disconnect()
        super.onCleared()
    }

    private companion object {
        const val SIX_HOURS_MS = 6 * 60 * 60 * 1000L
        const val LOCATION_FRESHNESS_MS = 60_000L
        const val PASSENGER_STALE_SWEEP_INTERVAL_MS = 5_000L
        const val VEHICLE_POLL_INTERVAL_MS = 10_000L
        const val BACKEND_TIMEOUT_MS = 20_000L
    }
}
