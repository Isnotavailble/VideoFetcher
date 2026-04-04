package com.videofetcher

import android.content.Context
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DownloaderViewModel : ViewModel() {
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Initializing)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val processId = "downloader_process"

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

                request.addOption("-f", "bestvideo[height<=$resolution]+bestaudio/best")
                request.addOption("--merge-output-format", "mp4")
                request.addOption("--restrict-filenames")
                request.addOption("-o", "${downloadsDir.absolutePath}/%(title)s.%(ext)s")

                YoutubeDL.getInstance().execute(request, processId) { progress, etaInSeconds, line ->
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

                // Check if it was cancelled manually
                if (_downloadState.value !is DownloadState.Cancelled) {
                    _downloadState.value = DownloadState.Success("Video successfully saved!")
                }
            } catch (e: Exception) {
                if (e.message?.contains("Process destroyed") == true) {
                    _downloadState.value = DownloadState.Cancelled
                } else {
                    _downloadState.value = DownloadState.Error(e.message ?: "An unknown error occurred")
                }
            }
        }
    }

    fun cancelDownload() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().destroyProcessById(processId)
                _downloadState.value = DownloadState.Cancelled
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun resetState() {
        _downloadState.value = DownloadState.Idle
    }
}