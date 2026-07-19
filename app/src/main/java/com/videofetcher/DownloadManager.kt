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
    }

    private val _fileRefreshCounter = MutableStateFlow(0)
    val fileRefreshCounter: StateFlow<Int> = _fileRefreshCounter.asStateFlow()

    fun triggerFileRefresh() {
        _fileRefreshCounter.value += 1
    }
}