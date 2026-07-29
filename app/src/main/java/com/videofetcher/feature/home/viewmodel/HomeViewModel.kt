package com.videofetcher.feature.home.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videofetcher.manager.DownloadManager.DownloadState
import com.videofetcher.manager.DownloadManager.EngineState
import com.videofetcher.manager.DownloadManager
import com.videofetcher.manager.PauseManager
import com.videofetcher.manager.PausedDownload
import com.videofetcher.repository.DownloadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(
    private val repository: DownloadRepository,
    private val downloadManager: DownloadManager,
    private val pauseManager: PauseManager
) : ViewModel() {

    sealed class VideoInfoState {
        object Idle : VideoInfoState()
        object Fetching : VideoInfoState()
        data class Success(
            val title: String,
            val duration: String,
            val thumbnailUrl: String,
            val formats: List<String>
        ) : VideoInfoState()
        data class Error(val message: String) : VideoInfoState()
    }

    val engineState: StateFlow<EngineState> = downloadManager.engineState
    val activeDownloads: StateFlow<Map<String, DownloadState>> = downloadManager.activeDownloads

    private val _pausedDownloads = MutableStateFlow<List<PausedDownload>>(emptyList())
    val pausedDownloads: StateFlow<List<PausedDownload>> = _pausedDownloads.asStateFlow()

    private val _videoInfoState = MutableStateFlow<VideoInfoState>(VideoInfoState.Idle)
    val videoInfoState: StateFlow<VideoInfoState> = _videoInfoState.asStateFlow()

    private var analyzeJob: Job? = null

    fun analyzeUrl(url: String, context: Context) {
        analyzeJob?.cancel()
        if (url.isBlank()) {
            _videoInfoState.value = VideoInfoState.Idle
            return
        }

        val urlRegex = """^(https?|ftp)://[^\s/$.?#].[^\s]*$""".toRegex(RegexOption.IGNORE_CASE)
        if (!urlRegex.matches(url)) {
            _videoInfoState.value = VideoInfoState.Error("Invalid URL format")
            return
        }

        analyzeJob = viewModelScope.launch(Dispatchers.IO) {
            _videoInfoState.value = VideoInfoState.Fetching
            try {
                val metadata = repository.fetchVideoMetadata(url, context)
                withContext(Dispatchers.Main) {
                    _videoInfoState.value = VideoInfoState.Success(
                        title = metadata.title,
                        duration = metadata.durationStr,
                        thumbnailUrl = metadata.thumbnailUrl,
                        formats = metadata.formats
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                withContext(Dispatchers.Main) { 
                    _videoInfoState.value = VideoInfoState.Error("Couldn't fetch video info. Is the link correct?") 
                }
            }
        }
    }

    fun clearVideoInfo() {
        analyzeJob?.cancel()
        _videoInfoState.value = VideoInfoState.Idle
    }

    fun startDownload(url: String, quality: String, context: Context) {
        val currentInfo = _videoInfoState.value
        val thumbUrl = if (currentInfo is VideoInfoState.Success) currentInfo.thumbnailUrl else ""
        val titleText = if (currentInfo is VideoInfoState.Success) currentInfo.title else "Video"
        
        repository.startDownload(context, url, quality, thumbUrl, titleText)
    }

    fun pauseDownload(context: Context, url: String) {
        repository.pauseDownload(context, url)
    }

    fun cancelDownload(context: Context, url: String) {
        repository.cancelDownload(context, url)
    }

    fun fetchPausedDownloads(context: Context) {
        _pausedDownloads.value = pauseManager.getAllPausedDownloads()
    }

    fun resumeDownload(context: Context, url: String, quality: String) {
        val currentInfo = _videoInfoState.value
        val thumbUrl = if (currentInfo is VideoInfoState.Success) currentInfo.thumbnailUrl else ""
        val titleText = if (currentInfo is VideoInfoState.Success) currentInfo.title else "Video"
        
        repository.resumeDownload(context, url, quality, thumbUrl, titleText)
        fetchPausedDownloads(context)
    }

    fun cancelPausedDownload(context: Context, url: String) {
        pauseManager.removePausedDownload(url)
        fetchPausedDownloads(context)
    }

    fun resetState(url: String) {
        downloadManager.removeDownload(url)
    }
}
