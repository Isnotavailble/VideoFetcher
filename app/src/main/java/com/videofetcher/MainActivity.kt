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
import com.yausername.ffmpeg.FFmpeg 
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
    object Initializing : DownloadState()
    object Idle : DownloadState()
    data class Downloading(val progress: Float, val status: String) : DownloadState()
    data class Success(val fileName: String) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

// ==========================================
// 2. VIEWMODEL (BACKGROUND LOGIC)
// ==========================================
class DownloaderViewModel : ViewModel() {
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Initializing)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    fun initializeEngine(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(context)
                FFmpeg.getInstance().init(context) 
                _downloadState.value = DownloadState.Idle
            } catch (e: Exception) {
                e.printStackTrace()
                _downloadState.value = DownloadState.Error("Engine failed to boot: ${e.message}")
            }
        }
    }

    fun startDownload(url: String, quality: String) {
        if (url.isBlank()) {
            _downloadState.value = DownloadState.Error("URL cannot be empty")
            return
        }

        _downloadState.value = DownloadState.Downloading(0f, "Starting...")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val request = YoutubeDLRequest(url)
                val resolution = quality.replace("p", "")
                
                // 1. Target specific resolution and best audio
                request.addOption("-f", "bestvideo[height<=$resolution]+bestaudio/best")
                
                // 2. Force FFmpeg to output as an MP4 container
                request.addOption("--merge-output-format", "mp4")

                // 3. Clean the filename of emojis or illegal characters to prevent OS errors
                request.addOption("--restrict-filenames")
                
                // 4. Set the output path
                request.addOption("-o", "${downloadsDir.absolutePath}/%(title)s.%(ext)s")

                YoutubeDL.getInstance().execute(request, "downloader_process") { progress, etaInSeconds, line ->
                    // Detect if the terminal output indicates FFmpeg has started merging
                    val isConverting = line.contains("[ffmpeg]") || line.contains("Merging") || progress >= 100f
                    
                    val currentStatus = if (isConverting) {
                        "Converting & Merging to MP4... Please wait"
                    } else {
                        "Downloading: ${String.format("%.1f", progress)}% (ETA: ${etaInSeconds}s)"
                    }

                    _downloadState.value = DownloadState.Downloading(
                        progress = if (isConverting) 1f else (progress / 100f),
                        status = currentStatus
                    )
                }

                _downloadState.value = DownloadState.Success("Video successfully saved as MP4!")

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

    LaunchedEffect(Unit) {
        viewModel.initializeEngine(context.applicationContext)
    }

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
                    enabled = state is DownloadState.Idle || state is DownloadState.Success || state is DownloadState.Error
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("1080p", "720p", "480p").forEach { quality ->
                        FilterChip(
                            selected = selectedQuality == quality,
                            onClick = { selectedQuality = quality },
                            label = { Text(quality) },
                            enabled = state is DownloadState.Idle || state is DownloadState.Success || state is DownloadState.Error
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { viewModel.startDownload(url, selectedQuality) },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    enabled = (state is DownloadState.Idle || state is DownloadState.Success || state is DownloadState.Error) && url.isNotBlank()
                ) {
                    Text(
                        when (state) {
                            is DownloadState.Initializing -> "INITIALIZING ENGINE..."
                            is DownloadState.Downloading -> "DOWNLOADING..."
                            else -> "DOWNLOAD"
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        when (state) {
            is DownloadState.Initializing -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(8.dp))
                Text("Extracting libraries...", style = MaterialTheme.typography.bodyMedium)
            }
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