package com.videofetcher

sealed class EngineState {
    object Initializing : EngineState()
    object Idle : EngineState()
    data class Error(val message: String) : EngineState()
}

sealed class DownloadState {
    object Queued : DownloadState()
    data class Downloading(val progress: Float, val status: String) : DownloadState()
    data class Success(val message: String) : DownloadState()
    data class Error(val message: String) : DownloadState()
    object Cancelled : DownloadState()
}