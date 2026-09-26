package com.safar.app.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safar.app.ui.navigation.SafarNavHost
import com.safar.app.ui.onboarding.OnboardingViewModel
import java.util.Locale
import androidx.activity.compose.LocalActivityResultRegistryOwner

@Composable
fun SafarApp(viewModel: OnboardingViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    val baseContext = LocalContext.current

    val activityResultRegistryOwner =
        LocalActivityResultRegistryOwner.current
            ?: error("ActivityResultRegistryOwner is unavailable")

    val localizedContext = remember(baseContext, state.languageTag) {
        createLocalizedContext(baseContext, state.languageTag)
    }

    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedContext.resources.configuration,
        LocalActivityResultRegistryOwner provides activityResultRegistryOwner,
    ) {
        SafarNavHost(viewModel = viewModel)
    }
}

private fun createLocalizedContext(context: Context, languageTag: String): Context {
    val locale = Locale.forLanguageTag(
        when (languageTag) {
            "en" -> "en"
            "tg" -> "tg"
            else -> "ru"
        },
    )
    Locale.setDefault(locale)
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(locale)
    return context.createConfigurationContext(configuration)
}
