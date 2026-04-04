package com.videofetcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Capture URL if opened via YouTube Share
        val sharedUrl = extractSharedUrl(intent)

        setContent {
            // Ollama minimalist theme: White background, Black text/primary
            val ollamaTheme = lightColorScheme(
                background = Color.White,
                surface = Color.White,
                onSurface = Color.Black,
                primary = Color.Black,
                onPrimary = Color.White,
                secondaryContainer = Color(0xFFF5F5F5) // Light gray for inputs
            )

            MaterialTheme(colorScheme = ollamaTheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    VideoDownloaderUI(initialUrl = sharedUrl)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Handle case where app is already open and receives a new share intent
        val sharedUrl = extractSharedUrl(intent)
        if (sharedUrl.isNotEmpty()) {
            // You might want to update your ViewModel directly here in a production app
        }
    }

    private fun extractSharedUrl(intent: Intent?): String {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            return intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        }
        return ""
    }
}