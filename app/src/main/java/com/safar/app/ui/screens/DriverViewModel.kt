package com.safar.app.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.safar.app.data.realtime.RealtimeTransport
import com.safar.app.di.AppContainer
import com.safar.app.domain.model.PassengerLocation
import com.safar.app.domain.model.Driver
import com.safar.app.domain.model.Route
import com.safar.app.domain.model.WorkSession
import com.safar.app.domain.model.WorkSessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class DriverUiState(
    val driver: Driver? = null,
    val routes: List<Route> = emptyList(),
    val selectedRoute: Route? = null,
    val workSession: WorkSession? = null,
    val passengerLocations: List<PassengerLocation> = emptyList(),
    val loading: Boolean = false,
    val actionInProgress: Boolean = false,
    val error: String? = null,
)

class DriverViewModel(application: Application) : AndroidViewModel(application) {
    private val driverRepository = AppContainer.driverRepository
    private val routeRepository = AppContainer.routeRepository
    private val workSessionRepository = AppContainer.workSessionRepository
    private val realtimeTransport: RealtimeTransport = AppContainer.realtimeTransport

    private val _uiState = MutableStateFlow(DriverUiState())
    val uiState: StateFlow<DriverUiState> = _uiState.asStateFlow()

    private var realtimeJob: Job? = null
    private var staleSweepJob: Job? = null

    init {
        startPassengerStaleSweep()
        load()
    }

    fun load() {
        if (_uiState.value.loading) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            runCatching {
                val driver = driverRepository.getDriver()
                val routes = routeRepository.getRoutes().sortedBy { it.number }
                val session = workSessionRepository.getCurrent()
                Triple(driver, routes, session)
            }.onSuccess { (driver, routes, session) ->
                val selected = driver?.routeId
                    ?.let { id -> routes.firstOrNull { it.id == id } }
                _uiState.value = _uiState.value.copy(
                    driver = driver,
                    routes = routes,
                    selectedRoute = selected,
                    workSession = session,
                    loading = false,
                    error = null,
                )
                selected?.let { switchPassengerRealtime(it.id) }
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = error.userMessage(),
                )
            }
        }
    }

    fun selectRoute(route: Route) {
        val ui = _uiState.value
        if (ui.actionInProgress || ui.workSession?.status == WorkSessionStatus.ACTIVE || ui.workSession?.status == WorkSessionStatus.PAUSED) return

        viewModelScope.launch {
            _uiState.value = ui.copy(actionInProgress = true, error = null)
            runCatching { driverRepository.assignRoute(route.id) }
                .onSuccess { driver ->
                    _uiState.value = _uiState.value.copy(
                        driver = driver,
                        selectedRoute = route,
                        passengerLocations = emptyList(),
                        actionInProgress = false,
                        error = null,
                    )
                    switchPassengerRealtime(route.id)
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        actionInProgress = false,
                        error = error.userMessage(),
                    )
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

    private fun applyPassengerEvent(
        passengerId: String,
        routeId: String,
        location: PassengerLocation?,
    ) {
        if (_uiState.value.selectedRoute?.id != routeId) return
        val current = _uiState.value.passengerLocations
        if (location == null || !isFresh(location.timestampEpochMs)) {
            _uiState.value = _uiState.value.copy(
                passengerLocations = current.filter { it.passengerId != passengerId },
            )
            return
        }

        val updated = current.firstOrNull { it.passengerId == passengerId }
        _uiState.value = _uiState.value.copy(
            passengerLocations = if (updated == null) {
                current + location
            } else {
                current.map { if (it.passengerId == passengerId) location else it }
            },
            error = null,
        )
    }

    private fun startPassengerStaleSweep() {
        staleSweepJob = viewModelScope.launch {
            while (isActive) {
                delay(PASSENGER_STALE_SWEEP_INTERVAL_MS)
                val fresh = _uiState.value.passengerLocations.filter { isFresh(it.timestampEpochMs) }
                if (fresh.size != _uiState.value.passengerLocations.size) {
                    _uiState.value = _uiState.value.copy(passengerLocations = fresh)
                }
            }
        }
    }

    private fun isFresh(timestampEpochMs: Long): Boolean =
        timestampEpochMs > 0L && System.currentTimeMillis() - timestampEpochMs <= LOCATION_FRESHNESS_MS

    fun startWork() {
        val ui = _uiState.value
        if (ui.actionInProgress) return
        if (ui.selectedRoute == null) {
            _uiState.value = ui.copy(error = "Сначала выберите маршрут")
            return
        }
        if (ui.workSession?.status == WorkSessionStatus.ACTIVE || ui.workSession?.status == WorkSessionStatus.PAUSED) {
            _uiState.value = ui.copy(error = "Текущая рабочая сессия уже существует")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(actionInProgress = true, error = null)
            runCatching { workSessionRepository.start() }
                .onSuccess { session ->
                    _uiState.value = _uiState.value.copy(
                        workSession = session,
                        actionInProgress = false,
                        error = null,
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        actionInProgress = false,
                        error = error.userMessage(),
                    )
                }
        }
    }

    fun pauseWork() = changeSession { id -> workSessionRepository.pause(id) }

    fun resumeWork() {
        val session = _uiState.value.workSession
        if (session?.status != WorkSessionStatus.PAUSED) {
            _uiState.value = _uiState.value.copy(error = "Нет приостановленной рабочей сессии")
            return
        }

        val pausedAt = session.pauseTimeEpochMs
        if (pausedAt != null && System.currentTimeMillis() - pausedAt > SIX_HOURS_MS) {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(actionInProgress = true, error = null)
                runCatching { workSessionRepository.finish(session.id) }
                    .onSuccess {
                        _uiState.value = _uiState.value.copy(
                            workSession = null,
                            actionInProgress = false,
                            error = "Предыдущая сессия завершена после 6 часов паузы. Начните новую сессию.",
                        )
                    }
                    .onFailure { error ->
                        _uiState.value = _uiState.value.copy(
                            actionInProgress = false,
                            error = error.userMessage(),
                        )
                    }
            }
        } else {
            changeSession { id -> workSessionRepository.resume(id) }
        }
    }

    fun finishWork() = changeSession { id -> workSessionRepository.finish(id) }

    private fun changeSession(action: suspend (String) -> WorkSession) {
        val session = _uiState.value.workSession
        if (_uiState.value.actionInProgress) return
        if (session == null) {
            _uiState.value = _uiState.value.copy(error = "Текущей рабочей сессии нет")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(actionInProgress = true, error = null)
            runCatching { action(session.id) }
                .onSuccess { updatedSession ->
                    _uiState.value = _uiState.value.copy(
                        workSession = updatedSession,
                        actionInProgress = false,
                        error = null,
                    )
                }
                .onFailure { error ->
                    // Re-read the backend state. This is important when a paused
                    // session expires after six hours while the app is open.
                    val refreshed = runCatching { workSessionRepository.getCurrent() }.getOrNull()
                    _uiState.value = _uiState.value.copy(
                        workSession = refreshed,
                        actionInProgress = false,
                        error = error.userMessage(),
                    )
                }
        }
    }

    private fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() } ?: "Не удалось выполнить действие"

    override fun onCleared() {
        realtimeJob?.cancel()
        staleSweepJob?.cancel()
        realtimeTransport.disconnect()
        super.onCleared()
    }

    private companion object {
        const val SIX_HOURS_MS = 6 * 60 * 60 * 1000L
        const val LOCATION_FRESHNESS_MS = 60_000L
        const val PASSENGER_STALE_SWEEP_INTERVAL_MS = 5_000L
    }
}
