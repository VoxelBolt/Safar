package com.safar.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val SafarColors = lightColorScheme(
    primary = SafarBlue,
    onPrimary = SafarWhite,
    background = SafarWhite,
    surface = SafarWhite,
    onBackground = SafarNavy,
    onSurface = SafarNavy
)

@Composable
fun SafarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SafarColors,
        content = content
    )
}
