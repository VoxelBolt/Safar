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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

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
            // Keep already-rendered profile data while refreshing. A transient
            // backend failure should not blank a profile the user has already seen.
            _uiState.value = _uiState.value.copy(loading = true, error = null)

            try {
                val result = withTimeout(PROFILE_LOAD_TIMEOUT_MS) {
                    retryRead {
                        val user = userRepository.getCurrentUser()
                            ?: throw IllegalStateException("Профиль не найден")
                        val driver = if (isDriver) driverRepository.getDriver() else null
                        val route = driver?.routeId?.let { routeRepository.getRoute(it) }
                        val vehicle = driver?.vehicleId?.let { id ->
                            vehicleRepository.getAllVehicles().firstOrNull { it.id == id }
                        }
                        ProfileLoadResult(user, driver, route, vehicle)
                    }
                }
                _uiState.value = ProfileUiState(
                    user = result.user,
                    driver = result.driver,
                    route = result.route,
                    vehicle = result.vehicle,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = error.userMessage(),
                )
            }
        }
    }

    /**
     * Reads are safe to retry: they don't mutate backend state. A small bounded
     * retry handles short network/auth initialization races without hanging the UI.
     */
    private suspend fun <T> retryRead(block: suspend () -> T): T {
        var lastError: Exception? = null
        for (attempt in 0 until PROFILE_READ_ATTEMPTS) {
            try {
                return block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                if (attempt < PROFILE_READ_ATTEMPTS - 1) {
                    delay(PROFILE_RETRY_DELAYS_MS[attempt])
                }
            }
        }
        throw lastError ?: IllegalStateException("Не удалось загрузить профиль")
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

            try {
                val user = withTimeout(PROFILE_SAVE_TIMEOUT_MS) {
                    userRepository.updateCurrentUser(cleanName, cleanPhone)
                }
                _uiState.value = _uiState.value.copy(
                    user = user,
                    saving = false,
                    error = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    saving = false,
                    error = error.userMessage(),
                )
            }
        }
    }

    fun retry(isDriver: Boolean) {
        load(isDriver)
    }

    private fun Throwable.userMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: "Не удалось загрузить данные"

    private companion object {
        const val PROFILE_LOAD_TIMEOUT_MS = 20_000L
        const val PROFILE_SAVE_TIMEOUT_MS = 20_000L
        const val PROFILE_READ_ATTEMPTS = 3
        val PROFILE_RETRY_DELAYS_MS = longArrayOf(300L, 800L)
    }
}
