package com.safar.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safar.app.di.AppContainer
import com.safar.app.domain.model.WorkSession
import com.safar.app.domain.model.WorkSessionTimer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

enum class StatisticsPeriod { DAYS_7, DAYS_30, ALL_TIME }

data class StatisticsDay(
    val dateLabel: String,
    val workSeconds: Long,
    val distanceMeters: Double,
)

data class StatisticsUiState(
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val period: StatisticsPeriod = StatisticsPeriod.DAYS_7,
    val sessions: List<WorkSession> = emptyList(),
    val sessionCount: Long = 0L,
    val totalWorkSeconds: Long = 0L,
    val totalDistanceMeters: Double = 0.0,
    val days: List<StatisticsDay> = emptyList(),
    val error: String? = null,
    val loadMoreError: String? = null,
)

class StatisticsViewModel : ViewModel() {
    private val repository = AppContainer.workSessionRepository
    private val _uiState = MutableStateFlow(StatisticsUiState())
    val uiState: StateFlow<StatisticsUiState> = _uiState.asStateFlow()
    private var historyJob: Job? = null
    private var pageJob: Job? = null
    private val requestGuard = StatisticsRequestGuard()
    private var nextOffset = 0
    private var activePeriodStart: Long? = null
    private var activePeriodEnd: Long = 0L

    fun selectPeriod(period: StatisticsPeriod) {
        if (_uiState.value.period == period && historyJob?.isActive == true) return
        _uiState.value = _uiState.value.copy(period = period)
        load(period)
    }

    fun load(period: StatisticsPeriod = _uiState.value.period) {
        val token = requestGuard.begin()
        historyJob?.cancel()
        pageJob?.cancel()
        nextOffset = 0
        _uiState.value = _uiState.value.copy(
            period = period,
            loading = true,
            loadingMore = false,
            hasMore = false,
            sessions = emptyList(),
            sessionCount = 0L,
            totalWorkSeconds = 0L,
            totalDistanceMeters = 0.0,
            days = emptyList(),
            error = null,
            loadMoreError = null,
        )
        historyJob = viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val start = periodStart(period, now)
                activePeriodStart = start
                activePeriodEnd = now
                val (summary, sessions) = withContext(Dispatchers.IO) {
                    withTimeout(REQUEST_TIMEOUT_MS) {
                        repository.getSummary(start, now) to repository.getHistory(
                            startEpochMs = start,
                            endEpochMs = now,
                            offset = 0,
                            limit = PAGE_SIZE,
                        )
                    }
                }
                if (!requestGuard.isCurrent(token, period, _uiState.value.period)) return@launch
                val calculated = calculate(sessions)
                nextOffset = sessions.size
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    sessions = sessions,
                    sessionCount = summary.sessionCount,
                    totalWorkSeconds = summary.workSeconds,
                    totalDistanceMeters = summary.distanceMeters,
                    days = calculated.third,
                    hasMore = sessions.size < summary.sessionCount,
                    error = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (requestGuard.isCurrent(token, period, _uiState.value.period)) {
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        error = error.message?.takeIf { it.isNotBlank() }
                            ?: "Не удалось загрузить статистику",
                    )
                }
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.loading || state.loadingMore || !state.hasMore) return
        val token = requestGuard.current()
        val period = state.period
        val offset = nextOffset
        val end = activePeriodEnd
        val start = activePeriodStart
        _uiState.value = state.copy(loadingMore = true, loadMoreError = null)
        pageJob = viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    withTimeout(REQUEST_TIMEOUT_MS) {
                        repository.getHistory(start, end, offset, PAGE_SIZE)
                    }
                }
                if (!requestGuard.isCurrent(token, period, _uiState.value.period)) return@launch
                val sessions = (_uiState.value.sessions + page).distinctBy { it.id }
                nextOffset = offset + page.size
                val calculated = calculate(sessions)
                _uiState.value = _uiState.value.copy(
                    loadingMore = false,
                    sessions = sessions,
                    days = calculated.third,
                    hasMore = sessions.size < _uiState.value.sessionCount,
                    loadMoreError = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (requestGuard.isCurrent(token, period, _uiState.value.period)) {
                    _uiState.value = _uiState.value.copy(
                        loadingMore = false,
                        loadMoreError = error.message?.takeIf { it.isNotBlank() }
                            ?: "Не удалось загрузить следующую страницу",
                    )
                }
            }
        }
    }

    private fun periodStart(period: StatisticsPeriod, now: Long): Long? = when (period) {
        StatisticsPeriod.DAYS_7 -> now - 6L * 24 * 60 * 60 * 1000
        StatisticsPeriod.DAYS_30 -> now - 29L * 24 * 60 * 60 * 1000
        StatisticsPeriod.ALL_TIME -> null
    }

    private fun calculate(sessions: List<WorkSession>): Triple<Long, Double, List<StatisticsDay>> {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val enriched = sessions.map { session ->
            val work = WorkSessionTimer.elapsedWorkSeconds(session, now)
            session to work
        }
        val totalWork = enriched.sumOf { it.second }
        val totalDistance = sessions.sumOf { it.distanceMeters.coerceAtLeast(0.0) }
        // Render one row per session, not one row per day. Grouping by date
        // made the session counter increase while the visible list stayed at one row.
        val sessionRows = enriched.map { (session, workSeconds) ->
            val startMs = session.startTimeEpochMs
            val dateLabel = if (startMs != null && startMs > 0L) {
                val start = Instant.ofEpochMilli(startMs).atZone(zone)
                "%s • %02d:%02d".format(
                    java.util.Locale.ROOT,
                    start.toLocalDate().toString(),
                    start.hour,
                    start.minute,
                )
            } else {
                "Время не указано"
            }
            StatisticsDay(
                dateLabel = dateLabel,
                workSeconds = workSeconds,
                distanceMeters = session.distanceMeters.coerceAtLeast(0.0),
            )
        }.sortedByDescending { it.dateLabel }
        return Triple(totalWork, totalDistance, sessionRows)
    }

    fun retry() = load()

    private companion object {
        const val PAGE_SIZE = 50
        const val REQUEST_TIMEOUT_MS = 20_000L
    }
}

internal class StatisticsRequestGuard {
    private var generation = 0L

    fun begin(): Long = ++generation

    fun current(): Long = generation

    fun isCurrent(token: Long, requestedPeriod: StatisticsPeriod, selectedPeriod: StatisticsPeriod): Boolean =
        token == generation && requestedPeriod == selectedPeriod
}
