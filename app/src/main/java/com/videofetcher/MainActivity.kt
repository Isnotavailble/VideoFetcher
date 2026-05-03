package com.videofetcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.videofetcher.settings.SettingsManager
import com.videofetcher.theme.VideoFetcherTheme
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // Hold the shared URL in a state variable so Compose can react to it instantly
    private var sharedUrlState = mutableStateOf("")
    private lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Stop system from force-inverting explicit colors (Fixes the white header bug on Xiaomi/Samsung)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.decorView.isForceDarkAllowed = false
        }

        settingsManager = SettingsManager(applicationContext)

        // Handle intent if app is opened fresh
        handleIntent(intent)

        setContent {
            val isDarkTheme by settingsManager.isDark.collectAsState(initial = false)
            val scope = rememberCoroutineScope()

            // INSTANTLY paint the native window background before Compose draws the next frame.
            // This completely hides the 1-frame buffer drop caused by the MIUI shield.
            val windowBackgroundColor = if (isDarkTheme) {
                android.graphics.Color.parseColor("#000000") // Match Dark background
            } else {
                android.graphics.Color.parseColor("#FEFEFE") // Match Light background
            }
            window.decorView.setBackgroundColor(windowBackgroundColor)

            VideoFetcherTheme(darkTheme = isDarkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.99f }, // Shield the entire screen
                    color = MaterialTheme.colorScheme.background
                ) {
                    VideoDownloaderUI(
                        sharedUrl = sharedUrlState.value,
                        isDarkTheme = isDarkTheme,
                        onThemeToggle = { scope.launch { settingsManager.setDarkMode(!isDarkTheme) } }
                    )
                }
            }
        }
    }

    // Handle intent if app is already open in the background
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            
            // Phase 3: Offload heavy Regex parsing to a background thread to prevent ANR crashes
            lifecycleScope.launch(Dispatchers.Default) {
                val urlRegex = """(?i)\b((?:https?://|www\d{0,3}[.]|[a-z0-9.\-]+[.][a-z]{2,4}/)(?:[^\s()<>]+|\(([^\s()<>]+|(\([^\s()<>]+\)))*\))+(?:\(([^\s()<>]+|(\([^\s()<>]+\)))*\)|[^\s`!()\[\]{};:'".,<>?«»“”‘’]))""".toRegex()
                val match = urlRegex.find(sharedText)
                
                if (match != null) {
                    sharedUrlState.value = match.value
                } else {
                    sharedUrlState.value = sharedText
                }
            }
        }
    }
}