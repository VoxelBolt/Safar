package com.safar.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

val LocalSafarDarkMode = compositionLocalOf { false }
val LocalSetSafarDarkMode = compositionLocalOf<(Boolean) -> Unit> { {} }

private val SafarLightColors = lightColorScheme(
    primary = SafarBlue,
    onPrimary = SafarWhite,
    background = SafarWhite,
    surface = SafarWhite,
    onBackground = SafarNavy,
    onSurface = SafarNavy,
)

private val SafarDarkColors = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF09234A),
    background = Color(0xFF101318),
    surface = Color(0xFF1B2028),
    onBackground = Color(0xFFE8EDF5),
    onSurface = Color(0xFFE8EDF5),
    secondary = Color(0xFFB6C8E5),
)

@Composable
fun SafarTheme(content: @Composable () -> Unit) {
    var darkMode by remember { mutableStateOf(false) }
    CompositionLocalProvider(
        LocalSafarDarkMode provides darkMode,
        LocalSetSafarDarkMode provides { darkMode = it },
    ) {
        MaterialTheme(
            colorScheme = if (darkMode) SafarDarkColors else SafarLightColors,
            content = content,
        )
    }
}
