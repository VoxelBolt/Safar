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
    val error: String? = null,
)

class FavoriteViewModel : ViewModel() {
    private val favoriteRepository = AppContainer.favoriteRepository
    private val vehicleRepository = AppContainer.vehicleRepository

    private val _uiState = MutableStateFlow(FavoriteUiState())
    val uiState: StateFlow<FavoriteUiState> = _uiState.asStateFlow()

    fun load() {
        if (_uiState.value.loading) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            runCatching {
                val favorites = favoriteRepository.getFavorites()
                val vehicles = vehicleRepository.getAllVehicles()
                favorites.map { it.vehicleId }.toSet() to vehicles
            }.onSuccess { (ids, vehicles) ->
                _uiState.value = FavoriteUiState(
                    loading = false,
                    vehicles = vehicles.filter { it.id in ids },
                    favoriteIds = ids,
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
        val favorite = isFavorite(vehicle.id)
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(error = null)
            runCatching {
                if (favorite) {
                    favoriteRepository.removeFavorite(vehicle.id)
                } else {
                    favoriteRepository.addFavorite(vehicle.id)
                }
            }.onSuccess {
                val ids = _uiState.value.favoriteIds.toMutableSet()
                if (favorite) ids.remove(vehicle.id) else ids.add(vehicle.id)
                val vehicles = if (favorite) {
                    _uiState.value.vehicles.filterNot { it.id == vehicle.id }
                } else {
                    (_uiState.value.vehicles + vehicle).distinctBy { it.id }
                }
                _uiState.value = _uiState.value.copy(
                    favoriteIds = ids,
                    vehicles = vehicles,
                    error = null,
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(error = error.userMessage())
            }
        }
    }

    fun retry() = load()

    private fun Throwable.userMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: "Не удалось сохранить избранное"
}
