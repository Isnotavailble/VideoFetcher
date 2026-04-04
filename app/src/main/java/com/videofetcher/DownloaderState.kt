package com.videofetcher

sealed class DownloadState {
    object Initializing : DownloadState()
    object Idle : DownloadState()
    data class Downloading(val progress: Float, val status: String) : DownloadState()
    data class Success(val fileName: String) : DownloadState()
    data class Error(val message: String) : DownloadState()
    object Cancelled : DownloadState()
}