package com.videofetcher

import android.content.Context
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

// ==========================================
// 1. STATE MANAGEMENT
// ==========================================
sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progress: Float, val status: String) : DownloadState()
    data class Success(val fileName: String) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

// ==========================================
// 2. VIEWMODEL (BACKGROUND LOGIC)
// ==========================================
class DownloaderViewModel : ViewModel() {
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    fun startDownload(context: Context, url: String, quality: String) {
        if (url.isBlank()) {
            _downloadState.value = DownloadState.Error("URL cannot be empty")
            return
        }

        _downloadState.value = DownloadState.Downloading(0f, "Starting...")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Point to the public Android Downloads folder
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val appFolder = File(downloadsDir, "VideoFetcher")
                if (!appFolder.exists()) appFolder.mkdir()

                val request = YoutubeDLRequest(url)
                val resolution = quality.replace("p", "")
                request.addOption("-f", "bestvideo[height<=$resolution]+bestaudio/best")
                request.addOption("-o", "${appFolder.absolutePath}/%(title)s.%(ext)s")

                YoutubeDL.getInstance().execute(request, "downloader_process") { progress, etaInSeconds, _ ->
                    _downloadState.value = DownloadState.Downloading(
                        progress = progress / 100f,
                        status = "Downloading: ${String.format("%.1f", progress)}% (ETA: ${etaInSeconds}s)"
                    )
                }

                _downloadState.value = DownloadState.Success("Saved to Downloads/VideoFetcher")

            } catch (e: Exception) {
                e.printStackTrace()
                _downloadState.value = DownloadState.Error(e.message ?: "An unknown error occurred")
            }
        }
    }
    
    fun resetState() {
        _downloadState.value = DownloadState.Idle
    }
}

// ==========================================
// 3. MAIN ACTIVITY (ENTRY POINT)
// ==========================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize the library
        try {
            YoutubeDL.getInstance().init(applicationContext)
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

// ==========================================
// 4. USER INTERFACE (COMPOSE)
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDownloaderUI(viewModel: DownloaderViewModel = viewModel()) {
    val context = LocalContext.current
    var url by remember { mutableStateOf("") }
    var selectedQuality by remember { mutableStateOf("1080p") }
    val state by viewModel.downloadState.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Video Fetcher", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { 
                        url = it
                        if (state is DownloadState.Error) viewModel.resetState() 
                    },
                    label = { Text("Paste Video URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = state !is DownloadState.Downloading
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("1080p", "720p", "480p").forEach { quality ->
                        FilterChip(
                            selected = selectedQuality == quality,
                            onClick = { selectedQuality = quality },
                            label = { Text(quality) },
                            enabled = state !is DownloadState.Downloading
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { viewModel.startDownload(context, url, selectedQuality) },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    enabled = state !is DownloadState.Downloading && url.isNotBlank()
                ) {
                    Text(if (state is DownloadState.Downloading) "DOWNLOADING..." else "DOWNLOAD")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        when (state) {
            is DownloadState.Idle -> { }
            is DownloadState.Downloading -> {
                val downloadState = state as DownloadState.Downloading
                LinearProgressIndicator(progress = downloadState.progress, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = downloadState.status, style = MaterialTheme.typography.bodyMedium)
            }
            is DownloadState.Success -> {
                Text((state as DownloadState.Success).fileName, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            is DownloadState.Error -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = "Error", tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text((state as DownloadState.Error).message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}