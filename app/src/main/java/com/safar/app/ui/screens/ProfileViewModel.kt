package com.safar.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safar.app.di.AppContainer
import com.safar.app.domain.model.Driver
import com.safar.app.domain.model.Route
import com.safar.app.domain.model.User
import com.safar.app.domain.model.Vehicle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProfileUiState(
    val user: User? = null,
    val driver: Driver? = null,
    val route: Route? = null,
    val vehicle: Vehicle? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
)

private data class ProfileLoadResult(
    val user: User,
    val driver: Driver?,
    val route: Route?,
    val vehicle: Vehicle?,
)

class ProfileViewModel : ViewModel() {
    private val userRepository = AppContainer.userRepository
    private val driverRepository = AppContainer.driverRepository
    private val routeRepository = AppContainer.routeRepository
    private val vehicleRepository = AppContainer.vehicleRepository

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    fun load(isDriver: Boolean) {
        if (_uiState.value.loading) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)

            runCatching {
                val user = userRepository.getCurrentUser()
                    ?: throw IllegalStateException("Профиль не найден")
                val driver = if (isDriver) driverRepository.getDriver() else null
                val route = driver?.routeId?.let { routeRepository.getRoute(it) }
                val vehicle = driver?.vehicleId?.let { id ->
                    vehicleRepository.getAllVehicles().firstOrNull { it.id == id }
                }
                ProfileLoadResult(user, driver, route, vehicle)
            }.onSuccess { result ->
                _uiState.value = ProfileUiState(
                    user = result.user,
                    driver = result.driver,
                    route = result.route,
                    vehicle = result.vehicle,
                )
            }.onFailure { throwable ->
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = throwable.userMessage(),
                )
            }
        }
    }

    fun save(name: String, phone: String) {
        if (_uiState.value.saving) return

        val cleanName = name.trim()
        val cleanPhone = phone.trim()

        if (cleanName.isBlank() || cleanPhone.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Заполните имя и номер телефона")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(saving = true, error = null)

            runCatching {
                userRepository.updateCurrentUser(cleanName, cleanPhone)
            }.onSuccess { user ->
                _uiState.value = _uiState.value.copy(
                    user = user,
                    saving = false,
                    error = null,
                )
            }.onFailure { throwable ->
                _uiState.value = _uiState.value.copy(
                    saving = false,
                    error = throwable.userMessage(),
                )
            }
        }
    }

    fun retry(isDriver: Boolean) {
        load(isDriver)
    }

    private fun Throwable.userMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: "Не удалось загрузить данные"
}
