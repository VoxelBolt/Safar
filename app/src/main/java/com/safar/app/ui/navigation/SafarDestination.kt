package com.safar.app.ui.navigation

sealed interface SafarDestination {
    val route: String

    data object Language : SafarDestination {
        override val route = "onboarding/language"
    }

    data object Role : SafarDestination {
        override val route = "onboarding/role"
    }

    data object PassengerRegistration : SafarDestination {
        override val route = "onboarding/registration/passenger"
    }

    data object DriverRegistration : SafarDestination {
        override val route = "onboarding/registration/driver"
    }

    data object PassengerMain : SafarDestination {
        override val route = "main/passenger"
    }

    data object DriverMain : SafarDestination {
        override val route = "main/driver"
    }
}
