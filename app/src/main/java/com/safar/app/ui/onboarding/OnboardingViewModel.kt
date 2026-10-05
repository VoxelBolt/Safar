package com.safar.app.ui.onboarding

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.safar.app.BuildConfig
import com.safar.app.data.remote.SupabaseClientProvider
import com.safar.app.data.location.DriverLocationService
import com.safar.app.di.AppContainer
import com.safar.app.domain.model.Route
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

    fun completeOnboarding() {
        val current = _state.value
        if (current.role == null || current.registrationInProgress) return

        update {
            copy(
                registrationInProgress = true,
                registrationError = false,
                registrationErrorMessage = null,
            )
        }

        viewModelScope.launch {
            runCatching {
                // Keep all Supabase/Auth/PostgREST work off the Android main
                // thread. Physical devices can initialize Ktor/Auth much slower
                // than an emulator, which can otherwise trigger an ANR.
                withContext(Dispatchers.IO) {
                    withTimeout(BACKEND_TIMEOUT_MS) {
                        when (current.role) {
                            UserRole.PASSENGER -> registrationRepository.registerPassenger(
                                name = current.name.trim(),
                                phone = current.phone.trim(),
                            )

                            UserRole.DRIVER -> {
                                val routeId = requireNotNull(current.selectedRouteId) {
                                    "Route must be selected"
                                }
                                driverRepository.registerDriver(
                                    name = current.name.trim(),
                                    phone = current.phone.trim(),
                                    vehicleIdentifier = current.vehicleIdentifier.trim(),
                                )
                                driverRepository.assignRoute(routeId)
                            }
                        }
                    }
                }
            }.onFailure { error ->
                val message = registrationErrorMessage(error)
                update {
                    copy(
                        registrationInProgress = false,
                        registrationError = true,
                        registrationErrorMessage = message,
                    )
                }
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "Registration backend operation failed: ${error.message}", error)
                }
            }.onSuccess {
                update {
                    copy(
                        completed = true,
                        registrationInProgress = false,
                        registrationError = false,
                        registrationErrorMessage = null,
                    )
                }
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Registration backend operation OK")
                }
            }
        }
    }

    private fun registrationErrorMessage(error: Throwable): String {
        val details = buildString {
            append(error.message.orEmpty())
            error.cause?.message?.let { append(" ").append(it) }
        }.lowercase()

        return when {
            "users_phone_unique" in details ||
                "duplicate key value violates unique constraint" in details && "phone" in details ->
                "Этот номер телефона уже зарегистрирован. Используйте другой номер."
            "route must be selected" in details ->
                "Выберите номер маршрута."
            "vehicle is already assigned" in details ->
                "Этот номер транспорта уже закреплён за другим водителем."
            "vehicle could not be registered" in details ->
                "Не удалось зарегистрировать номер транспорта."
            "authentication is required" in details ->
                "Сессия приложения недействительна. Перезапустите приложение и попробуйте снова."
            else ->
                "Не удалось зарегистрироваться. Проверьте подключение к интернету и попробуйте снова."
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
