package com.safar.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safar.app.di.AppContainer
import com.safar.app.domain.model.Vehicle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FavoriteUiState(
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val vehicles: List<Vehicle> = emptyList(),
    val favoriteIds: Set<String> = emptySet(),
    val routeNumbers: Map<String, String> = emptyMap(),
    val favoriteToggleInProgress: Set<String> = emptySet(),
    val error: String? = null,
)

class FavoriteViewModel : ViewModel() {
    private val favoriteRepository = AppContainer.favoriteRepository
    private val vehicleRepository = AppContainer.vehicleRepository
    private val routeRepository = AppContainer.routeRepository

    private val _uiState = MutableStateFlow(FavoriteUiState())
    val uiState: StateFlow<FavoriteUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private var pageJob: Job? = null
    private var generation = 0L
    private var favoriteVehicleIds: List<String> = emptyList()
    private var nextOffset = 0

    fun load() {
        val requestGeneration = ++generation
        loadJob?.cancel()
        pageJob?.cancel()
        nextOffset = 0
        favoriteVehicleIds = emptyList()
        _uiState.value = _uiState.value.copy(
            loading = true,
            loadingMore = false,
            hasMore = false,
            vehicles = emptyList(),
            error = null,
        )
        loadJob = viewModelScope.launch {
            try {
                val favorites = favoriteRepository.getFavorites()
                if (requestGeneration != generation) return@launch
                val favoriteIds = favorites.map { it.vehicleId }.distinct().sorted()
                val routes = routeRepository.getRoutes().associate { it.id to it.number }
                if (requestGeneration != generation) return@launch
                val firstPageIds = favoriteIds.take(PAGE_SIZE)
                val vehicles = loadVehiclesInFavoriteOrder(firstPageIds)
                if (requestGeneration != generation) return@launch
                favoriteVehicleIds = favoriteIds
                nextOffset = firstPageIds.size
                _uiState.value = FavoriteUiState(
                    loading = false,
                    vehicles = vehicles,
                    favoriteIds = favoriteIds.toSet(),
                    routeNumbers = routes,
                    hasMore = nextOffset < favoriteIds.size,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (requestGeneration == generation) {
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        error = error.userMessage(),
                    )
                }
            }
        }
    }

    fun loadNextPage() {
        if (_uiState.value.loading || _uiState.value.loadingMore || !_uiState.value.hasMore) return
        val requestGeneration = generation
        val pageIds = favoriteVehicleIds.drop(nextOffset).take(PAGE_SIZE)
        if (pageIds.isEmpty()) {
            _uiState.value = _uiState.value.copy(hasMore = false)
            return
        }
        _uiState.value = _uiState.value.copy(loadingMore = true, error = null)
        pageJob = viewModelScope.launch {
            try {
                val vehicles = loadVehiclesInFavoriteOrder(pageIds)
                if (requestGeneration != generation) return@launch
                nextOffset += pageIds.size
                _uiState.value = _uiState.value.copy(
                    loadingMore = false,
                    vehicles = (_uiState.value.vehicles + vehicles).distinctBy { it.id },
                    hasMore = nextOffset < favoriteVehicleIds.size,
                    error = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (requestGeneration == generation) {
                    _uiState.value = _uiState.value.copy(loadingMore = false, error = error.userMessage())
                }
            }
        }
    }

    private suspend fun loadVehiclesInFavoriteOrder(ids: List<String>): List<Vehicle> {
        if (ids.isEmpty()) return emptyList()
        val byId = vehicleRepository.getVehiclesByIds(ids).associateBy { it.id }
        return ids.mapNotNull(byId::get)
    }

    fun isFavorite(vehicleId: String): Boolean = vehicleId in _uiState.value.favoriteIds

    fun toggle(vehicle: Vehicle) {
        val vehicleId = vehicle.id
        if (vehicleId in _uiState.value.favoriteToggleInProgress) return

        val favorite = isFavorite(vehicleId)
        _uiState.value = _uiState.value.copy(
            favoriteToggleInProgress = _uiState.value.favoriteToggleInProgress + vehicleId,
            error = null,
        )

        viewModelScope.launch {
            try {
                if (favorite) {
                    favoriteRepository.removeFavorite(vehicleId)
                } else {
                    favoriteRepository.addFavorite(vehicleId)
                }
                val ids = _uiState.value.favoriteIds.toMutableSet()
                if (favorite) ids.remove(vehicleId) else ids.add(vehicleId)

                _uiState.value = _uiState.value.copy(
                    favoriteIds = ids,
                    vehicles = if (favorite) _uiState.value.vehicles.filterNot { it.id == vehicleId }
                    else (_uiState.value.vehicles + vehicle).distinctBy { it.id },
                    favoriteToggleInProgress = _uiState.value.favoriteToggleInProgress - vehicleId,
                    error = null,
                )
                // Reset the cursor after mutations so offset/page boundaries remain aligned.
                load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    favoriteToggleInProgress = _uiState.value.favoriteToggleInProgress - vehicleId,
                    error = error.userMessage(),
                )
            }
        }
    }

    fun retry() = load()

    private companion object {
        const val PAGE_SIZE = 50
    }

    private fun Throwable.userMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: "Не удалось сохранить избранное"
}
