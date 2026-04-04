package com.videofetcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    // Hold the shared URL in a state variable so Compose can react to it instantly
    private var sharedUrlState = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle intent if app is opened fresh
        handleIntent(intent)

        setContent {
            val ollamaTheme = lightColorScheme(
                background = Color.White,
                surface = Color.White,
                onSurface = Color.Black,
                primary = Color.Black,
                onPrimary = Color.White,
                secondaryContainer = Color(0xFFF5F5F5)
            )

            MaterialTheme(colorScheme = ollamaTheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    VideoDownloaderUI(sharedUrl = sharedUrlState.value)
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
            
            // YouTube often sends text like: "Video Title https://youtu.be/xyz"
            // This Regex ensures we ONLY extract the URL part for the downloader
            val urlRegex = "(?i)\\b((?:https?://|www\\d{0,3}[.]|[a-z0-9.\\-]+[.][a-z]{2,4}/)(?:[^\\s()<>]+|\\((?:[^\\s()<>]+|(?:\\([^\\s()<>]+\\)))*\\))+(?:\\((?:[^\\s()<>]+|(?:\\([^\\s()<>]+\\)))*\\)|[^\\s`!()\\[\\]{};:'\".,<>?«»“”‘’]))".toRegex()
            val match = urlRegex.find(sharedText)
            
            if (match != null) {
                sharedUrlState.value = match.value
            } else {
                sharedUrlState.value = sharedText
            }
        }
    }
}