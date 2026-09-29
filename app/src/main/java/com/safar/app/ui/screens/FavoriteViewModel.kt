package com.safar.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safar.app.di.AppContainer
import com.safar.app.domain.model.Vehicle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FavoriteUiState(
    val loading: Boolean = false,
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

    fun load() {
        if (_uiState.value.loading) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            runCatching {
                val favorites = favoriteRepository.getFavorites()
                val vehicles = vehicleRepository.getAllVehicles()
                val routes = routeRepository.getRoutes().associate { it.id to it.number }
                Triple(favorites.map { it.vehicleId }.toSet(), vehicles, routes)
            }.onSuccess { (ids, vehicles, routes) ->
                _uiState.value = FavoriteUiState(
                    loading = false,
                    vehicles = vehicles.filter { it.id in ids },
                    favoriteIds = ids,
                    routeNumbers = routes,
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = error.userMessage(),
                )
            }
        }
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
            runCatching {
                if (favorite) {
                    favoriteRepository.removeFavorite(vehicleId)
                } else {
                    favoriteRepository.addFavorite(vehicleId)
                }
            }.onSuccess {
                val ids = _uiState.value.favoriteIds.toMutableSet()
                if (favorite) ids.remove(vehicleId) else ids.add(vehicleId)

                val vehicles = if (favorite) {
                    _uiState.value.vehicles.filterNot { it.id == vehicleId }
                } else {
                    (_uiState.value.vehicles + vehicle).distinctBy { it.id }
                }

                _uiState.value = _uiState.value.copy(
                    favoriteIds = ids,
                    vehicles = vehicles,
                    favoriteToggleInProgress = _uiState.value.favoriteToggleInProgress - vehicleId,
                    error = null,
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    favoriteToggleInProgress = _uiState.value.favoriteToggleInProgress - vehicleId,
                    error = error.userMessage(),
                )
            }
        }
    }

    fun retry() = load()

    private fun Throwable.userMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: "Не удалось сохранить избранное"
}
