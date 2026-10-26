package com.safar.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safar.app.di.AppContainer
import com.safar.app.domain.model.WorkSession
import com.safar.app.domain.model.WorkSessionTimer
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
    val period: StatisticsPeriod = StatisticsPeriod.DAYS_7,
    val sessions: List<WorkSession> = emptyList(),
    val totalWorkSeconds: Long = 0L,
    val totalDistanceMeters: Double = 0.0,
    val days: List<StatisticsDay> = emptyList(),
    val error: String? = null,
)

class StatisticsViewModel : ViewModel() {
    private val repository = AppContainer.workSessionRepository
    private val _uiState = MutableStateFlow(StatisticsUiState())
    val uiState: StateFlow<StatisticsUiState> = _uiState.asStateFlow()

    fun selectPeriod(period: StatisticsPeriod) {
        _uiState.value = _uiState.value.copy(period = period)
        load(period)
    }

    fun load(period: StatisticsPeriod = _uiState.value.period) {
        if (_uiState.value.loading) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            runCatching {
                val now = System.currentTimeMillis()
                val start = when (period) {
                    StatisticsPeriod.DAYS_7 -> now - 6L * 24 * 60 * 60 * 1000
                    StatisticsPeriod.DAYS_30 -> now - 29L * 24 * 60 * 60 * 1000
                    StatisticsPeriod.ALL_TIME -> null
                }
                repository.getHistory(start, now)
            }.onSuccess { sessions ->
                val calculated = calculate(sessions)
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    sessions = sessions,
                    totalWorkSeconds = calculated.first,
                    totalDistanceMeters = calculated.second,
                    days = calculated.third,
                    error = null,
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = error.message?.takeIf { it.isNotBlank() }
                        ?: "Не удалось загрузить статистику",
                )
            }
        }
    }

    private fun calculate(sessions: List<WorkSession>): Triple<Long, Double, List<StatisticsDay>> {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val enriched = sessions.map { session ->
            val work = if (session.status == com.safar.app.domain.model.WorkSessionStatus.COMPLETED) {
                session.durationSeconds.coerceAtLeast(0L)
            } else {
                WorkSessionTimer.elapsedWorkSeconds(session, now)
            }
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
}
