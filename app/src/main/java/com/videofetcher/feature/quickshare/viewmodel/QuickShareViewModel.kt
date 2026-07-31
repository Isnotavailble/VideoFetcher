package com.videofetcher.feature.quickshare.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videofetcher.repository.DownloadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuickShareViewModel(
    private val repository: DownloadRepository
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
}
