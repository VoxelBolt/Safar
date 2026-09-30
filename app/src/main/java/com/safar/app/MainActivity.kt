    package com.safar.app
    
    import android.os.Bundle
    import androidx.activity.ComponentActivity
    import androidx.activity.compose.setContent
    import com.safar.app.ui.SafarApp
    import com.safar.app.ui.theme.SafarTheme
    
    // Startup verification: keep MainActivity free of backend initialization.
class MainActivity : ComponentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContent {
                SafarTheme {
                    SafarApp()
                }
            }
        }
    }
