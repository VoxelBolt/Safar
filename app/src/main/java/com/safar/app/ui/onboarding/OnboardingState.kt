package com.safar.app.ui.onboarding

import com.safar.app.domain.model.Route
import com.safar.app.domain.model.UserRole

/** UI state for the stage-2 onboarding flow. */
data class OnboardingState(
    val languageTag: String = "ru",
    val role: UserRole? = null,
    val name: String = "",
    val phone: String = "",
    val vehicleIdentifier: String = "",
    val driverRoutes: List<Route> = emptyList(),
    val selectedRouteId: String? = null,
    val routesLoading: Boolean = false,
    val routesError: Boolean = false,
    val completed: Boolean = false,
    // Stage 03 registration state; these fields are transient and are not persisted.
    val registrationInProgress: Boolean = false,
    val registrationError: Boolean = false,
    val registrationErrorMessage: String? = null,
    // true = "Вход" (existing account), false = "Регистрация". Transient.
    val isLoginMode: Boolean = false,
)
