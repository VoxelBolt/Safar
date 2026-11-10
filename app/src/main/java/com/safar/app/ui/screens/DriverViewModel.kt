package com.safar.app.ui.screens

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.safar.app.data.realtime.RealtimeTransport
import com.safar.app.data.realtime.RealtimeRetryPolicy
import com.safar.app.data.location.DriverLocationService
import com.safar.app.di.AppContainer
import com.safar.app.domain.model.PassengerLocation
import com.safar.app.domain.model.Driver
import com.safar.app.domain.model.DriverApplication
import com.safar.app.domain.model.Route
import com.safar.app.domain.model.WorkSession
import com.safar.app.domain.model.WorkSessionStatus
import com.safar.app.domain.model.Vehicle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import kotlin.random.Random

data class DriverUiState(
    val driver: Driver? = null,
    val driverApplication: DriverApplication? = null,
    val routes: List<Route> = emptyList(),
    val selectedRoute: Route? = null,
    val workSession: WorkSession? = null,
    val passengerLocations: List<PassengerLocation> = emptyList(),
    val activeVehicles: List<Vehicle> = emptyList(),
    val loading: Boolean = false,
    val actionInProgress: Boolean = false,
    val error: String? = null,
)

private data class DriverLoadSnapshot(
    val driver: Driver?,
    val application: DriverApplication?,
    val routes: List<Route>,
    val session: WorkSession?,
)

class DriverViewModel(application: Application) : AndroidViewModel(application) {
    private val driverRepository = AppContainer.driverRepository
    private val routeRepository = AppContainer.routeRepository
    private val workSessionRepository = AppContainer.workSessionRepository
    private val vehicleRepository = AppContainer.vehicleRepository
    private val realtimeTransport: RealtimeTransport = AppContainer.realtimeTransport
    private val vehicleMarkerFreshness = VehicleMarkerFreshness()

    private val _uiState = MutableStateFlow(DriverUiState())
    val uiState: StateFlow<DriverUiState> = _uiState.asStateFlow()

    private var realtimeJob: Job? = null
    private var staleSweepJob: Job? = null
    private var vehiclePollingJob: Job? = null
    private var passengerPollingJob: Job? = null
    private var sessionDistancePollingJob: Job? = null

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
                        val application = if (driver == null) driverRepository.getDriverApplication() else null
                        val routes = routeRepository.getRoutes().sortedBy { it.number }
                        val session = workSessionRepository.getCurrent()
                        DriverLoadSnapshot(driver, application, routes, session)
                    }
                }
            }.onSuccess { snapshot ->
                val driver = snapshot.driver
                val application = snapshot.application
                val routes = snapshot.routes
                val session = snapshot.session
                val routeId = driver?.routeId ?: application?.routeId
                val selected = routeId?.let { id -> routes.firstOrNull { it.id == id } }
                _uiState.value = _uiState.value.copy(
                    driver = driver,
                    driverApplication = application,
                    routes = routes,
                    selectedRoute = selected,
                    workSession = session,
                    loading = false,
                    error = null,
                )
                startSessionDistancePolling(session)
                if (driver?.verified == true) selected?.let {
                    switchPassengerRealtime(it.id)
                    startVehiclePolling(it.id)
                }
                if (driver?.routeId != null && driver.verified) {
                    // Work Session and GPS tracking are independent systems. Restore
                    // the location foreground service whenever a routed driver is loaded.
                    runCatching { DriverLocationService.start(getApplication(), driver.userId, driver.routeId) }
                } else {
                    runCatching { DriverLocationService.stop(getApplication()) }
                }
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(loading = false, error = error.userMessage())
            }
        }
    }

    fun selectRoute(route: Route) {
        val ui = _uiState.value
        if (ui.driver?.verified != true) {
            _uiState.value = ui.copy(error = "Профиль водителя ожидает подтверждения")
            return
        }
        if (ui.actionInProgress || ui.workSession?.status == WorkSessionStatus.ACTIVE || ui.workSession?.status == WorkSessionStatus.PAUSED) return
        viewModelScope.launch {
            _uiState.value = ui.copy(actionInProgress = true, error = null)
            runCatching {
                withContext(Dispatchers.IO) {
                    withTimeout(BACKEND_TIMEOUT_MS) { driverRepository.assignRoute(route.id) }
                }
            }
                .onSuccess { driver ->
                    _uiState.value = _uiState.value.copy(driver = driver, driverApplication = null, selectedRoute = route, passengerLocations = emptyList(), activeVehicles = emptyList(), actionInProgress = false, error = null)
                    startVehiclePolling(route.id)
                    // A route assignment enables GPS tracking; Work Session state does not.
                    runCatching { DriverLocationService.start(getApplication(), driver.userId, route.id) }
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
                            .filter { it.active && it.currentLocation != null }
                            .map { it.copy(active = true) }
                        vehicleMarkerFreshness.observeSnapshot(active, SystemClock.elapsedRealtime())
                        _uiState.value = _uiState.value.copy(activeVehicles = active, error = null)
                    }
                    .onFailure { error ->
                        android.util.Log.w("SafarDriverVehicle", "poll failed", error)
                    }
                delay(VEHICLE_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Waiting passengers normally arrive through Realtime broadcasts. Poll the
     * server snapshot as well, so the counter stays correct when a broadcast is
     * missed or the Realtime socket is reconnecting.
     */
    private fun startPassengerPolling(routeId: String) {
        passengerPollingJob?.cancel()
        passengerPollingJob = viewModelScope.launch {
            while (isActive && _uiState.value.selectedRoute?.id == routeId) {
                delay(PASSENGER_POLL_INTERVAL_MS)
                if (_uiState.value.selectedRoute?.id != routeId) break
                refreshPassengerSnapshot(routeId)
            }
        }
    }

    private fun switchPassengerRealtime(routeId: String) {
        realtimeJob?.cancel()
        // RealtimeTransport is a shared singleton also used by DriverLocationService
        // to publish vehicle locations. Do not disconnect it here: doing so tears down
        // the service's live channel and races with its next GPS broadcast. connect()
        // switches route channels itself when the route actually changes.
        vehicleMarkerFreshness.clear()
        _uiState.value = _uiState.value.copy(passengerLocations = emptyList(), activeVehicles = emptyList())
        startPassengerPolling(routeId)
        realtimeJob = viewModelScope.launch {
            var attempt = 0
            while (isActive && _uiState.value.selectedRoute?.id == routeId) {
                try {
                    withTimeout(BACKEND_TIMEOUT_MS) {
                        realtimeTransport.connect(routeId, receivePassengerLocations = true)
                    }
                    val connected = withTimeoutOrNull(BACKEND_TIMEOUT_MS) {
                        realtimeTransport.connectionState.first { it.routeId == routeId && it.subscribed }
                    } ?: error("Realtime подписка не подтвердилась вовремя")
                    refreshDriverSnapshots(routeId)

                    coroutineScope {
                        launch {
                            realtimeTransport.passengerEvents.collect { event ->
                                if (_uiState.value.selectedRoute?.id == event.routeId) {
                                    applyPassengerEvent(event.passengerId, event.routeId, event.location)
                                }
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
                                android.util.Log.w("SafarRealtime", "Driver channel did not recover for route $routeId")
                                break
                            }
                            lastEpoch = recovered.epoch
                            if (SystemClock.elapsedRealtime() - stableSinceElapsedMs >= REALTIME_STABLE_RESET_MS) {
                                attempt = 0
                            }
                            stableSinceElapsedMs = SystemClock.elapsedRealtime()
                            refreshDriverSnapshots(routeId)
                            if (disconnected.routeId == null) break
                        }
                    }
                    attempt += 1
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    android.util.Log.w("SafarRealtime", "Driver Realtime connection failed", error)
                    _uiState.value = _uiState.value.copy(error = error.message ?: "Realtime недоступен")
                    attempt += 1
                }
                if (isActive && _uiState.value.selectedRoute?.id == routeId) {
                    delay(RealtimeRetryPolicy.delayMs(attempt.coerceAtLeast(1), Random.nextDouble(0.75, 1.25)))
                }
            }
        }
    }

    private suspend fun refreshDriverSnapshots(routeId: String) {
        refreshPassengerSnapshot(routeId)
        refreshVehicleSnapshot(routeId)
    }

    private suspend fun refreshPassengerSnapshot(routeId: String) {
        try {
            val passengers = withContext(Dispatchers.IO) {
                withTimeout(BACKEND_TIMEOUT_MS) {
                    realtimeTransport.getActivePassengerLocations(routeId)
                }
            }
            if (_uiState.value.selectedRoute?.id == routeId) {
                val current = _uiState.value.passengerLocations
                val previous = current.associateBy { it.passengerId }
                // Keep the local copy when the snapshot has nothing newer, so its
                // stale timer keeps counting from the last real update instead of
                // restarting on every poll.
                val merged = passengers.map { passenger ->
                    val old = previous[passenger.passengerId]
                    if (old != null && old.timestampEpochMs >= passenger.timestampEpochMs) old else passenger
                }
                // A broadcast can arrive right after the snapshot was read; keep such
                // very recent markers instead of making the counter blink.
                val snapshotIds = passengers.map { it.passengerId }.toSet()
                val nowElapsed = SystemClock.elapsedRealtime()
                val recentLocalOnly = current.filter { local ->
                    local.passengerId !in snapshotIds &&
                        nowElapsed - local.receivedAtElapsedMs in 0L..PASSENGER_SNAPSHOT_GRACE_MS
                }
                _uiState.value = _uiState.value.copy(passengerLocations = merged + recentLocalOnly, error = null)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w("SafarRealtime", "Passenger snapshot refresh failed", error)
        }
    }

    private suspend fun refreshVehicleSnapshot(routeId: String) {
        try {
            val vehicles = withContext(Dispatchers.IO) {
                withTimeout(BACKEND_TIMEOUT_MS) { vehicleRepository.getVehiclesForRoute(routeId) }
            }
            if (_uiState.value.selectedRoute?.id == routeId) {
                val active = vehicles.filter { it.active && it.currentLocation != null }.map { it.copy(active = true) }
                vehicleMarkerFreshness.observeSnapshot(active, SystemClock.elapsedRealtime())
                _uiState.value = _uiState.value.copy(activeVehicles = active)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w("SafarRealtime", "Vehicle snapshot refresh failed", error)
        }
    }

    private fun applyPassengerEvent(passengerId: String, routeId: String, location: PassengerLocation?) {
        if (_uiState.value.selectedRoute?.id != routeId) return
        val current = _uiState.value.passengerLocations
        if (location == null || !isPassengerFresh(location.receivedAtElapsedMs)) {
            _uiState.value = _uiState.value.copy(passengerLocations = current.filter { it.passengerId != passengerId })
            return
        }
        val updated = current.firstOrNull { it.passengerId == passengerId }
        if (updated != null && updated.timestampEpochMs > location.timestampEpochMs) return
        _uiState.value = _uiState.value.copy(
            passengerLocations = if (updated == null) current + location else current.map { if (it.passengerId == passengerId) location else it },
            error = null,
        )
    }

    private fun startPassengerStaleSweep() {
        staleSweepJob = viewModelScope.launch {
            while (isActive) {
                delay(PASSENGER_STALE_SWEEP_INTERVAL_MS)
                val current = _uiState.value
                val freshPassengers = current.passengerLocations.filter { isPassengerFresh(it.receivedAtElapsedMs) }
                val freshVehicles = vehicleMarkerFreshness.retainFresh(
                    current.activeVehicles,
                    SystemClock.elapsedRealtime(),
                )
                if (freshPassengers.size != current.passengerLocations.size || freshVehicles.size != current.activeVehicles.size) {
                    _uiState.value = current.copy(passengerLocations = freshPassengers, activeVehicles = freshVehicles)
                }
            }
        }
    }

    // Passenger broadcasts are transient and use local receive time for expiry,
    // so sender clock skew cannot keep an abandoned marker alive or hide a live one.
    private fun isPassengerFresh(receivedAtElapsedMs: Long): Boolean =
        PassengerLocationVisibility.isFresh(receivedAtElapsedMs, SystemClock.elapsedRealtime())

    fun startWork() {
        val ui = _uiState.value
        if (ui.actionInProgress) return
        if (ui.driver?.verified != true) {
            _uiState.value = ui.copy(error = "Профиль водителя ожидает подтверждения")
            return
        }
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
                startSessionDistancePolling(session)
                if (session.status == WorkSessionStatus.ACTIVE) {
                    runCatching {
                        DriverLocationService.start(
                            getApplication(),
                            ui.driver.userId,
                            ui.selectedRoute.id,
                        )
                    }
                }
            }.onFailure { error -> _uiState.value = _uiState.value.copy(actionInProgress = false, error = error.userMessage()) }
        }
    }

    fun pauseWork() = changeSession { id -> workSessionRepository.pause(id) }

    fun resumeWork() {
        if (_uiState.value.driver?.verified != true) {
            _uiState.value = _uiState.value.copy(error = "Профиль водителя ожидает подтверждения")
            return
        }
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
        if (_uiState.value.driver?.verified != true) {
            _uiState.value = _uiState.value.copy(error = "Профиль водителя ожидает подтверждения")
            return
        }
        if (session == null) { _uiState.value = _uiState.value.copy(error = "Текущей рабочей сессии нет"); return }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(actionInProgress = true, error = null)
            runCatching {
                withContext(Dispatchers.IO) {
                    withTimeout(BACKEND_TIMEOUT_MS) { action(session.id) }
                }
            }.onSuccess { updatedSession ->
                _uiState.value = _uiState.value.copy(workSession = updatedSession, actionInProgress = false, error = null)
                startSessionDistancePolling(updatedSession)
                // Never stop GPS when the Work Session changes. Tracking is owned
                // by DriverLocationService and continues independently.
                if (_uiState.value.driver?.routeId != null) {
                    val driver = _uiState.value.driver
                    runCatching { DriverLocationService.start(getApplication(), driver?.userId, driver?.routeId) }
                }
            }.onFailure { error ->
                val refreshed = runCatching {
                    withContext(Dispatchers.IO) {
                        withTimeout(BACKEND_TIMEOUT_MS) { workSessionRepository.getCurrent() }
                    }
                }.getOrNull()
                _uiState.value = _uiState.value.copy(workSession = refreshed, actionInProgress = false, error = error.userMessage())
                startSessionDistancePolling(refreshed)
                if (_uiState.value.driver?.routeId != null) {
                    val driver = _uiState.value.driver
                    runCatching { DriverLocationService.start(getApplication(), driver?.userId, driver?.routeId) }
                }
            }
        }
    }

    /** The foreground service records accepted GPS movement on the server. Refresh the
     * session row while it is active so the distance card follows that authoritative total. */
    private fun startSessionDistancePolling(session: WorkSession?) {
        sessionDistancePollingJob?.cancel()
        if (session?.status != WorkSessionStatus.ACTIVE || _uiState.value.driver?.verified != true) return
        val sessionId = session.id
        sessionDistancePollingJob = viewModelScope.launch {
            while (isActive && _uiState.value.workSession?.id == sessionId &&
                _uiState.value.workSession?.status == WorkSessionStatus.ACTIVE
            ) {
                delay(5_000L)
                runCatching {
                    withContext(Dispatchers.IO) {
                        withTimeout(BACKEND_TIMEOUT_MS) { workSessionRepository.getCurrent() }
                    }
                }.onSuccess { latest ->
                    if (latest?.id == sessionId) {
                        _uiState.value = _uiState.value.copy(workSession = latest)
                    }
                }.onFailure { error ->
                    android.util.Log.w("SafarDriverSession", "distance refresh failed", error)
                }
            }
        }
    }

    private fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() } ?: "Не удалось выполнить действие"

    override fun onCleared() {
        realtimeJob?.cancel()
        staleSweepJob?.cancel()
        vehiclePollingJob?.cancel()
        passengerPollingJob?.cancel()
        sessionDistancePollingJob?.cancel()
        // DriverLocationService writes through PostgREST and does not own this
        // route-scoped Realtime subscription. Release the channel with the screen VM.
        realtimeTransport.disconnect()
    }

    private companion object {
        const val SIX_HOURS_MS = 6 * 60 * 60 * 1000L
        const val REALTIME_SDK_RECOVERY_GRACE_MS = 20_000L
        const val REALTIME_STABLE_RESET_MS = 120_000L
        const val PASSENGER_STALE_SWEEP_INTERVAL_MS = 5_000L
        const val VEHICLE_POLL_INTERVAL_MS = 10_000L
        const val PASSENGER_POLL_INTERVAL_MS = 15_000L
        const val PASSENGER_SNAPSHOT_GRACE_MS = 10_000L
        const val BACKEND_TIMEOUT_MS = 20_000L
    }
}
