package com.videofetcher

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DownloadManager {
    private val _engineState = MutableStateFlow<EngineState>(EngineState.Initializing)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    // Map of active/queued downloads keyed by URL
    private val _activeDownloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val activeDownloads: StateFlow<Map<String, DownloadState>> = _activeDownloads.asStateFlow()

    // Map of thumbnail URLs keyed by video URL
    private val _downloadThumbnails = MutableStateFlow<Map<String, String>>(emptyMap())
    val downloadThumbnails: StateFlow<Map<String, String>> = _downloadThumbnails.asStateFlow()

    fun updateDownloadThumbnail(url: String, thumbnailUrl: String) {
        if (thumbnailUrl.isNotBlank()) {
            val current = _downloadThumbnails.value.toMutableMap()
            current[url] = thumbnailUrl
            _downloadThumbnails.value = current
        }
    }

    fun updateEngineState(state: EngineState) {
        _engineState.value = state
    }

    fun updateDownloadState(url: String, state: DownloadState) {
        val current = _activeDownloads.value.toMutableMap()
        current[url] = state
        _activeDownloads.value = current
    }

    fun removeDownload(url: String) {
        val current = _activeDownloads.value.toMutableMap()
        current.remove(url)
        _activeDownloads.value = current

        val currentThumbs = _downloadThumbnails.value.toMutableMap()
        currentThumbs.remove(url)
        _downloadThumbnails.value = currentThumbs
    }

    private val _fileRefreshCounter = MutableStateFlow(0)
    val fileRefreshCounter: StateFlow<Int> = _fileRefreshCounter.asStateFlow()

    fun triggerFileRefresh() {
        _fileRefreshCounter.value += 1
    }
}