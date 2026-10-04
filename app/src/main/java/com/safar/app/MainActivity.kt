package com.safar.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.safar.app.ui.SafarApp
import com.safar.app.ui.theme.SafarTheme
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize MapLibre before any Compose screen can create a MapView.
        // The OpenGL ES MapLibre artifact is used so map initialization does not
        // depend on the device's Vulkan implementation.
        runCatching {
            if (!MapLibre.hasInstance()) {
                MapLibre.getInstance(applicationContext)
            }
        }.onFailure { error ->
            android.util.Log.e("SafarMap", "MapLibre initialization failed", error)
        }

        setContent {
            SafarTheme {
                SafarApp()
            }
        }
    }
}
