package com.videofetcher.feature.files.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videofetcher.repository.FileRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

class FilesViewModel(private val repository: FileRepository) : ViewModel() {
    private val _filesListState = MutableStateFlow<FilesListState>(FilesListState.Idle)
    val filesListState: StateFlow<FilesListState> = _filesListState.asStateFlow()
    private val _videoFiles = MutableStateFlow<List<DownloadedFileDetails>>(emptyList())
    val videoFiles: StateFlow<List<DownloadedFileDetails>> = _videoFiles.asStateFlow()

    private val _audioFiles = MutableStateFlow<List<DownloadedFileDetails>>(emptyList())
    val audioFiles: StateFlow<List<DownloadedFileDetails>> = _audioFiles.asStateFlow()

    private var fetchJob: Job? = null

    fun fetchDownloadedFiles(context: Context) {
        fetchJob?.cancel()
        
        if (_filesListState.value !is FilesListState.Success) {
            _filesListState.value = FilesListState.Fetching
        }
        
        val currentState = _filesListState.value
        val existingMap = if (currentState is FilesListState.Success) {
            currentState.files.associateBy { it.path }
        } else emptyMap()

        fetchJob = viewModelScope.launch(Dispatchers.IO) {
            repository.fetchDownloadedFiles(
                context = context,
                scope = this,
                existingFilesMap = existingMap,
                onUpdateState = { updatedList ->
                    _filesListState.value = FilesListState.Success(updatedList)
                    _videoFiles.value = updatedList.filter { !it.isAudio && it.path.endsWith("_vdf.mp4", ignoreCase = true) }
                    _audioFiles.value = updatedList.filter { it.isAudio }
                    _videoFiles.value = updatedList.filter { !it.isAudio && it.path.endsWith("_vdf.mp4", ignoreCase = true) }
                    _audioFiles.value = updatedList.filter { it.isAudio }
                },
                onError = { error ->
                    _filesListState.value = FilesListState.Error(error)
                }
            )
        }
    }

    fun deleteVideo(
        context: Context,
        fileDetails: DownloadedFileDetails,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
        onPermissionRequired: () -> Unit
    ) {
        viewModelScope.launch {
            repository.deleteVideo(context, fileDetails, {
                val currentState = _filesListState.value
                if (currentState is FilesListState.Success) {
                    val updatedList = currentState.files.filter { it.path != fileDetails.path }
                    _filesListState.value = FilesListState.Success(updatedList)
                    _videoFiles.value = updatedList.filter { !it.isAudio && it.path.endsWith("_vdf.mp4", ignoreCase = true) }
                    _audioFiles.value = updatedList.filter { it.isAudio }
                    _videoFiles.value = updatedList.filter { !it.isAudio && it.path.endsWith("_vdf.mp4", ignoreCase = true) }
                    _audioFiles.value = updatedList.filter { it.isAudio }
                }
                onSuccess()
            }, onError, onPermissionRequired)
        }
    }

    fun playVideo(context: Context, fileDetails: DownloadedFileDetails) {
        repository.playVideo(context, fileDetails)
    }

    fun shareVideo(context: Context, fileDetails: DownloadedFileDetails) {
        repository.shareVideo(context, fileDetails)
    }

    fun resumeDownload(context: Context, url: String, quality: String) {
        repository.removePausedDownload(url)
        startDownload(url, quality, context)
    }

    fun cancelPausedDownload(context: Context, url: String) {
        repository.removePausedDownload(url)
    }

    private fun startDownload(url: String, quality: String, context: Context) {
        val intent = android.content.Intent(context, com.videofetcher.DownloadService::class.java).apply {
            action = "START_DOWNLOAD"
            putExtra("URL", url)
            putExtra("QUALITY", quality)
        }
        context.startForegroundService(intent)
    }
}


