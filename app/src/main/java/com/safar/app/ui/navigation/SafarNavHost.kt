package com.safar.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.safar.app.domain.model.UserRole
import com.safar.app.ui.onboarding.LanguageSelectionScreen
import com.safar.app.ui.onboarding.OnboardingViewModel
import com.safar.app.ui.onboarding.RegistrationScreen
import com.safar.app.ui.onboarding.RoleSelectionScreen
import com.safar.app.ui.screens.DriverMainScreen
import com.safar.app.ui.screens.PassengerMainScreen

@Composable
fun SafarNavHost(
    navController: NavHostController = rememberNavController(),
    viewModel: OnboardingViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.completed, state.role) {
        if (state.completed) {
            val destination = if (state.role == UserRole.DRIVER) {
                SafarDestination.DriverMain.route
            } else {
                SafarDestination.PassengerMain.route
            }
            if (navController.currentDestination?.route != destination) {
                navController.navigate(destination) {
                    popUpTo(SafarDestination.Language.route) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }

    val startDestination = when {
        state.completed && state.role == UserRole.DRIVER -> SafarDestination.DriverMain.route
        state.completed && state.role == UserRole.PASSENGER -> SafarDestination.PassengerMain.route
        else -> SafarDestination.Language.route
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(SafarDestination.Language.route) {
            LanguageSelectionScreen(
                selectedLanguage = state.languageTag,
                onLanguageSelected = viewModel::selectLanguage,
                onContinue = { navController.navigate(SafarDestination.Role.route) },
            )
        }

        composable(SafarDestination.Role.route) {
            RoleSelectionScreen(
                selectedRole = state.role,
                onBack = { navController.popBackStack() },
                onRoleSelected = viewModel::selectRole,
                onContinue = {
                    val role = state.role ?: return@RoleSelectionScreen
                    navController.navigate(
                        if (role == UserRole.DRIVER) SafarDestination.DriverRegistration.route
                        else SafarDestination.PassengerRegistration.route,
                    )
                },
            )
        }

        composable(SafarDestination.PassengerRegistration.route) {
            RegistrationScreen(
                role = UserRole.PASSENGER,
                state = state,
                onBack = { navController.popBackStack() },
                onNameChange = viewModel::setName,
                onPhoneChange = viewModel::setPhone,
                onVehicleChange = {},
                onContinue = {
                    viewModel.completeOnboarding()
                },
            )
        }

        composable(SafarDestination.DriverRegistration.route) {
            LaunchedEffect(Unit) {
                viewModel.loadDriverRoutes()
            }
            RegistrationScreen(
                role = UserRole.DRIVER,
                state = state,
                onBack = { navController.popBackStack() },
                onNameChange = viewModel::setName,
                onPhoneChange = viewModel::setPhone,
                onVehicleChange = viewModel::setVehicleIdentifier,
                driverRoutes = state.driverRoutes,
                selectedRouteId = state.selectedRouteId,
                routesLoading = state.routesLoading,
                routesError = state.routesError,
                onRouteSelected = { route -> viewModel.setSelectedRoute(route.id) },
                onContinue = {
                    viewModel.completeOnboarding()
                },
            )
        }

        composable(SafarDestination.PassengerMain.route) {
            PassengerMainScreen(state = state)
        }

        composable(SafarDestination.DriverMain.route) {
            DriverMainScreen(state = state)
        }
    }
}
