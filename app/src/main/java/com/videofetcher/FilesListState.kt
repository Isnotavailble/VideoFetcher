package com.videofetcher

import android.net.Uri

// Data model for standard downloaded file card model
data class DownloadedFileDetails(
    val title: String,
    val path: String,
    val signature: String, // like "(MP4 1080)" which helps duplication check, resolution change, signature, and standard card model
    val size: String,
    val duration: String,
    val thumbnailUriStr: String, // fast thumbnail caching, video preview
    val isAudio: Boolean = false
)

sealed class FilesListState {
    object Fetching : FilesListState()
    data class Success(val files: List<DownloadedFileDetails>) : FilesListState()
    data class Error(val message: String) : FilesListState()
    object Idle : FilesListState()
}