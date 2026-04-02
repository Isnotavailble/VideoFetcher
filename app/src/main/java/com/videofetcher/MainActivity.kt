package com.videofetcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            com.yausername.youtubedl_android.YoutubeDL.getInstance().init(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VideoDownloaderUI()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDownloaderUI() {
    var url by remember { mutableStateOf("") }
    var selectedQuality by remember { mutableStateOf("1080p") }
    var statusText by remember { mutableStateOf("Ready") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Video Fetcher", style = MaterialTheme.typography.headlineMedium)
        
        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Enter Video URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            listOf("1080p", "720p", "480p").forEach { quality ->
                FilterChip(
                    selected = selectedQuality == quality,
                    onClick = { selectedQuality = quality },
                    label = { Text(quality) }
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { statusText = "Downloading $selectedQuality..." },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text("DOWNLOAD")
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        Text(text = statusText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}