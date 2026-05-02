package com.videofetcher

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DownloadManager {
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Initializing)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    fun updateState(state: DownloadState) {
        _downloadState.value = state
    }

    fun isDownloading(): Boolean {
        return _downloadState.value is DownloadState.Downloading
    }
}