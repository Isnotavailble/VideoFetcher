package com.videofetcher.feature.files.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videofetcher.manager.DownloadManager
import com.videofetcher.repository.FileRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class FilesViewModel(
    private val repository: FileRepository,
    private val downloadManager: DownloadManager,
    private val appContext: Context
) : ViewModel() {
    private val _filesListState = MutableStateFlow<FilesListState>(FilesListState.Idle)
    val filesListState: StateFlow<FilesListState> = _filesListState.asStateFlow()
    private val _videoFiles = MutableStateFlow<List<DownloadedFileDetails>>(emptyList())
    val videoFiles: StateFlow<List<DownloadedFileDetails>> = _videoFiles.asStateFlow()

    private val _audioFiles = MutableStateFlow<List<DownloadedFileDetails>>(emptyList())
    val audioFiles: StateFlow<List<DownloadedFileDetails>> = _audioFiles.asStateFlow()

    private var fetchJob: Job? = null

    init {
        viewModelScope.launch {
            downloadManager.fileRefreshCounter.collect {
                fetchDownloadedFiles()
            }
        }
    }

    fun fetchDownloadedFiles() {
        fetchJob?.cancel()
        
        if (_filesListState.value !is FilesListState.Success) {
            _filesListState.value = FilesListState.Fetching
        }
        
        val currentState = _filesListState.value
        val existingMap = if (currentState is FilesListState.Success) {
            currentState.files.associateBy { it.path }
        } else emptyMap()

        fetchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val initialFiles = repository.getInitialFiles(appContext, existingMap).toMutableList()
                
                // 1. Instantly push the fast media-store items to the UI
                _filesListState.value = FilesListState.Success(initialFiles.toList())
                _videoFiles.value = initialFiles.filter { !it.isAudio && it.path.endsWith("_vdf.mp4", ignoreCase = true) }
                _audioFiles.value = initialFiles.filter { it.isAudio }

                // 2. Identify files that need deep metadata extraction (thumbnails)
                val itemsToProcess = initialFiles.indices.filter { i ->
                    initialFiles[i].duration == "--:--" || initialFiles[i].thumbnailUriStr.isEmpty()
                }

                if (itemsToProcess.isNotEmpty()) {
                    val semaphore = Semaphore(2)
                    var processedCount = 0
                    
                    coroutineScope {
                        itemsToProcess.map { i ->
                            async(Dispatchers.IO) {
                                semaphore.withPermit {
                                    val fileDetails = initialFiles[i]
                                    val metadata = repository.extractMetadata(appContext, fileDetails)
                                    
                                    if (metadata != null) {
                                        synchronized(initialFiles) {
                                            initialFiles[i] = initialFiles[i].copy(
                                                duration = metadata.first,
                                                thumbnailUriStr = metadata.second
                                            )
                                            processedCount++
                                        }
                                        
                                        // 3. Progressively update UI every 10 items or at completion
                                        if (processedCount % 10 == 0 || processedCount == itemsToProcess.size) {
                                            val snapshot = initialFiles.toList()
                                            _filesListState.value = FilesListState.Success(snapshot)
                                            _videoFiles.value = snapshot.filter { !it.isAudio && it.path.endsWith("_vdf.mp4", ignoreCase = true) }
                                            _audioFiles.value = snapshot.filter { it.isAudio }
                                        }
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
            } catch (e: Exception) {
                _filesListState.value = FilesListState.Error(e.message ?: "Unknown error")
            }
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
            try {
                val isDeleted = repository.deleteVideo(context, fileDetails)
                if (isDeleted) {
                    val currentState = _filesListState.value
                    if (currentState is FilesListState.Success) {
                        val updatedList = currentState.files.filter { it.path != fileDetails.path }
                        _filesListState.value = FilesListState.Success(updatedList)
                        _videoFiles.value = updatedList.filter { !it.isAudio && it.path.endsWith("_vdf.mp4", ignoreCase = true) }
                        _audioFiles.value = updatedList.filter { it.isAudio }
                    }
                    onSuccess()
                } else {
                    onPermissionRequired()
                }
            } catch (e: Exception) {
                onError(e.message ?: "Unknown error occurred.")
            }
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

    fun cancelPausedDownload(url: String) {
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