package com.safar.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.safar.app.ui.SafarApp
import com.safar.app.ui.theme.SafarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Do not initialize MapLibre during app startup. The onboarding flow
        // must remain independent from the native map renderer. The map screen
        // initializes MapLibre lazily and explicitly selects OpenGL for broad
        // physical-device compatibility.
        setContent {
            SafarTheme {
                SafarApp()
            }
        }
    }
}
