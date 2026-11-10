package com.safar.app.ui.onboarding

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.safar.app.BuildConfig
import com.safar.app.data.location.DriverLocationService
import com.safar.app.di.AppContainer
import com.safar.app.domain.model.Route
import com.safar.app.domain.model.User
import com.safar.app.domain.model.UserRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val registrationRepository by lazy { AppContainer.registrationRepository }
    private val driverRepository by lazy { AppContainer.driverRepository }
    private val routeRepository by lazy { AppContainer.routeRepository }

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    fun selectLanguage(languageTag: String) {
        val normalized = when (languageTag.lowercase()) {
            "en" -> "en"
            "tg" -> "tg"
            else -> "ru"
        }
        update { copy(languageTag = normalized) }
    }

    fun selectRole(role: UserRole) {
        update {
            copy(
                role = role,
                selectedRouteId = if (role == UserRole.DRIVER) selectedRouteId else null,
            )
        }
        if (role == UserRole.DRIVER && _state.value.driverRoutes.isEmpty()) {
            loadDriverRoutes()
        }
    }

    fun loadDriverRoutes() {
        if (_state.value.routesLoading) return
        viewModelScope.launch {
            update { copy(routesLoading = true, routesError = false) }
            runCatching {
                withContext(Dispatchers.IO) {
                    withTimeout(BACKEND_TIMEOUT_MS) { routeRepository.getRoutes() }
                }
            }
                .map { routes ->
                    val preferredNumbers = listOf("3", "8", "15")
                    val preferred = preferredNumbers.mapNotNull { number ->
                        routes.firstOrNull { it.number == number }
                    }
                    val extras = routes
                        .filterNot { route -> preferred.any { it.id == route.id } }
                        .sortedWith(compareBy<Route> { it.number.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.number })
                    (preferred + extras).take(3)
                }
                .onSuccess { routes ->
                    val currentSelected = _state.value.selectedRouteId
                    val validSelected = currentSelected?.takeIf { id -> routes.any { it.id == id } }
                    update {
                        copy(
                            driverRoutes = routes,
                            selectedRouteId = validSelected,
                            routesLoading = false,
                            routesError = false,
                        )
                    }
                }
                .onFailure { error ->
                    update { copy(routesLoading = false, routesError = true) }
                    if (BuildConfig.DEBUG) {
                        Log.e(TAG, "Driver routes loading failed: ${error.message}", error)
                    }
                }
        }
    }

    fun setSelectedRoute(routeId: String) = update { copy(selectedRouteId = routeId) }

    fun setName(value: String) = update { copy(name = value) }

    fun setPhone(value: String) = update { copy(phone = value) }

    fun setVehicleIdentifier(value: String) = update { copy(vehicleIdentifier = value) }

    fun setAuthMode(mode: AuthMode) = update {
        copy(authMode = mode, registrationError = false, registrationErrorMessage = null)
    }

    fun completeOnboarding() {
        val current = _state.value
        if (current.role == null || current.registrationInProgress) return

        val validationError = when {
            current.name.trim().isEmpty() -> "Введите имя."
            current.phone.trim().isEmpty() -> "Введите номер телефона."
            current.role == UserRole.DRIVER && current.vehicleIdentifier.trim().isEmpty() ->
                "Введите номер транспорта."
            current.authMode == AuthMode.REGISTER &&
                current.role == UserRole.DRIVER && current.selectedRouteId == null ->
                "Выберите номер маршрута."
            else -> null
        }
        if (validationError != null) {
            update {
                copy(
                    registrationError = true,
                    registrationErrorMessage = validationError,
                    registrationInProgress = false,
                )
            }
            return
        }

        if (!com.safar.app.data.remote.SupabaseClientProvider.isConfigured) {
            update {
                copy(
                    registrationError = true,
                    registrationErrorMessage = "Сервис регистрации не настроен. Обратитесь к команде Safar.",
                    registrationInProgress = false,
                )
            }
            return
        }

        update {
            copy(
                registrationInProgress = true,
                registrationError = false,
                registrationErrorMessage = null,
            )
        }

        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    withTimeout(BACKEND_TIMEOUT_MS) {
                        when (current.role) {
                            UserRole.PASSENGER -> if (current.authMode == AuthMode.LOGIN) {
                                registrationRepository.loginPassenger(
                                    name = current.name.trim(),
                                    phone = current.phone.trim(),
                                )
                            } else {
                                registrationRepository.registerPassenger(
                                    name = current.name.trim(),
                                    phone = current.phone.trim(),
                                )
                            }
                            UserRole.DRIVER -> if (current.authMode == AuthMode.LOGIN) {
                                registrationRepository.loginDriver(
                                    name = current.name.trim(),
                                    phone = current.phone.trim(),
                                    vehicleIdentifier = current.vehicleIdentifier.trim(),
                                )
                            } else {
                                val routeId = requireNotNull(current.selectedRouteId)
                                driverRepository.registerDriver(
                                    name = current.name.trim(),
                                    phone = current.phone.trim(),
                                    vehicleIdentifier = current.vehicleIdentifier.trim(),
                                    routeId = routeId,
                                )
                            }
                        }
                    }
                }
            }

            result.onFailure { error ->
                update {
                    copy(
                        registrationInProgress = false,
                        registrationError = true,
                        registrationErrorMessage = OnboardingErrorMessages.message(error, current.authMode, current.role),
                    )
                }
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "Registration backend operation failed", error)
                }
            }.onSuccess { value ->
                // Login returns the stored profile: keep its saved name and phone locally.
                val profile = value as? User
                update {
                    copy(
                        name = profile?.name ?: name,
                        phone = profile?.phone ?: phone,
                        completed = true,
                        registrationInProgress = false,
                        registrationError = false,
                        registrationErrorMessage = null,
                    )
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    withTimeout(BACKEND_TIMEOUT_MS) { AppContainer.userRepository.signOut() }
                }
            }
            AppContainer.realtimeTransport.disconnect()
            runCatching { DriverLocationService.stop(getApplication()) }
            preferences.edit().clear().apply()
            _state.value = OnboardingState()
        }
    }

    fun resetForTesting() {
        preferences.edit().clear().apply()
        _state.value = OnboardingState()
    }

    private fun update(transform: OnboardingState.() -> OnboardingState) {
        val next = _state.value.transform()
        _state.value = next
        persist(next)
    }

    private fun loadState(): OnboardingState {
        // A previous development build could leave completed=true in
        // SharedPreferences. On a real device that can immediately route into
        // the map screen during the first launch of a new APK, before the user
        // can even reach onboarding. Reset the old onboarding state once when
        // this state format changes. This is intentionally local-only and does
        // not touch Supabase data.
        val storedVersion = preferences.getInt(KEY_STATE_VERSION, 0)
        if (storedVersion < ONBOARDING_STATE_VERSION) {
            preferences.edit()
                .remove(KEY_ROLE)
                .remove(KEY_NAME)
                .remove(KEY_PHONE)
                .remove(KEY_VEHICLE)
                .remove(KEY_ROUTE_ID)
                .remove(KEY_COMPLETED)
                .putInt(KEY_STATE_VERSION, ONBOARDING_STATE_VERSION)
                .apply()
            return OnboardingState()
        }

        val role = preferences.getString(KEY_ROLE, null)?.let { value ->
            runCatching { UserRole.valueOf(value) }.getOrNull()
        }
        return OnboardingState(
            languageTag = preferences.getString(KEY_LANGUAGE, "ru") ?: "ru",
            role = role,
            name = preferences.getString(KEY_NAME, "") ?: "",
            phone = preferences.getString(KEY_PHONE, "") ?: "",
            vehicleIdentifier = preferences.getString(KEY_VEHICLE, "") ?: "",
            selectedRouteId = preferences.getString(KEY_ROUTE_ID, null),
            completed = preferences.getBoolean(KEY_COMPLETED, false),
        )
    }

    private fun persist(state: OnboardingState) {
        preferences.edit()
            .putString(KEY_LANGUAGE, state.languageTag)
            .putString(KEY_ROLE, state.role?.name)
            .putString(KEY_NAME, state.name)
            .putString(KEY_PHONE, state.phone)
            .putString(KEY_VEHICLE, state.vehicleIdentifier)
            .putString(KEY_ROUTE_ID, state.selectedRouteId)
            .putBoolean(KEY_COMPLETED, state.completed)
            .apply()
    }

    private companion object {
        const val TAG = "SafarStage03"
        const val PREFS_NAME = "safar_onboarding"
        const val KEY_LANGUAGE = "language"
        const val KEY_ROLE = "role"
        const val KEY_NAME = "name"
        const val KEY_PHONE = "phone"
        const val KEY_VEHICLE = "vehicle_identifier"
        const val KEY_ROUTE_ID = "route_id"
        const val KEY_COMPLETED = "completed"
        const val KEY_STATE_VERSION = "onboarding_state_version"
        const val ONBOARDING_STATE_VERSION = 1
        const val BACKEND_TIMEOUT_MS = 20_000L
    }
}
