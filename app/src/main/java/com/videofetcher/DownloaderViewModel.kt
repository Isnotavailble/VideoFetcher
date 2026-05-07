package com.videofetcher

import android.content.Context
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class DownloaderViewModel : ViewModel() {
    val engineState: StateFlow<EngineState> = DownloadManager.engineState
    val activeDownloads: StateFlow<Map<String, DownloadState>> = DownloadManager.activeDownloads

    private val _filesListState = MutableStateFlow<FilesListState>(FilesListState.Idle)
    val filesListState: StateFlow<FilesListState> = _filesListState.asStateFlow()

    private val _pausedDownloads = MutableStateFlow<List<PausedDownload>>(emptyList())
    val pausedDownloads: StateFlow<List<PausedDownload>> = _pausedDownloads.asStateFlow()

    private val baseDirName = "VideoFetcher"
    private var fetchJob: Job? = null

    fun initializeEngine(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(context)
                FFmpeg.getInstance().init(context)
                if (DownloadManager.engineState.value is EngineState.Initializing) {
                    DownloadManager.updateEngineState(EngineState.Idle)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                DownloadManager.updateEngineState(EngineState.Error("Engine failed to boot: ${e.message}"))
            }
        }
    }

    fun startDownload(url: String, quality: String, context: Context) {
        if (url.isBlank()) return

        val serviceIntent = Intent(context, DownloadService::class.java).apply {
            action = "START_DOWNLOAD"
            putExtra("URL", url)
            putExtra("QUALITY", quality)
        }
        context.startService(serviceIntent)
    }

    fun pauseDownload(context: Context, url: String) {
        val intent = Intent(context, DownloadService::class.java).apply { 
            action = "PAUSE_DOWNLOAD" 
            putExtra("URL", url)
        }
        context.startService(intent)
    }

    fun cancelDownload(context: Context, url: String) {
        val intent = Intent(context, DownloadService::class.java).apply {
            action = "CANCEL_DOWNLOAD"
            putExtra("URL", url)
        }
        context.startService(intent)
    }

    fun fetchPausedDownloads(context: Context) {
        _pausedDownloads.value = PauseRepository(context).getAllPausedDownloads()
    }

    fun resumeDownload(context: Context, url: String, quality: String) {
        PauseRepository(context).removePausedDownload(url)
        fetchPausedDownloads(context)
        startDownload(url, quality, context)
    }

    fun cancelPausedDownload(context: Context, url: String) {
        PauseRepository(context).removePausedDownload(url)
        fetchPausedDownloads(context)
    }

    fun fetchDownloadedFiles(context: Context?): Job? {
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch(Dispatchers.IO) {
            if (_filesListState.value !is FilesListState.Success) {
                _filesListState.value = FilesListState.Fetching
            }
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(downloadsDir, baseDirName)
                
                if (!targetDir.exists()) {
                    _filesListState.value = FilesListState.Success(emptyList())
                    return@launch
                }

                val files = targetDir.listFiles { file ->
                    file.isFile && file.name.endsWith(".mp4", ignoreCase = true)
                } ?: emptyArray()

                val thumbCacheDir = File(context?.cacheDir, "thumbnails")
                if (!thumbCacheDir.exists()) {
                    thumbCacheDir.mkdirs()
                }

                // Grab existing loaded files to prevent overwriting them with placeholders
                val currentSuccessState = _filesListState.value as? FilesListState.Success
                val existingFilesMap = currentSuccessState?.files?.associateBy { it.path } ?: emptyMap()

                // STEP 1: Fast load - Instantly show files without waiting for heavy extraction
                val initialList = files.map { file ->
                    existingFilesMap[file.absolutePath] ?: run {
                        val (title, signature) = parseFileName(file.name)
                        val thumbFile = File(thumbCacheDir, "${file.name}.png")
                        DownloadedFileDetails(
                            title = title,
                            path = file.absolutePath,
                            signature = signature,
                            size = formatFileSize(file.length()),
                            duration = "--:--", // Placeholder, will be updated lazily
                            thumbnailUri = if (thumbFile.exists()) Uri.fromFile(thumbFile) else Uri.EMPTY
                        )
                    }
                }.toMutableList()

                // Immediately update UI with names and sizes
                _filesListState.value = FilesListState.Success(initialList.toList())

                // STEP 2: Lazy processing - Fetch durations and missing thumbnails in background
                for (i in files.indices) {
                    // Skip expensive processing if we already have a valid duration and thumbnail!
                    if (initialList[i].duration != "--:--" && initialList[i].thumbnailUri != Uri.EMPTY) {
                        continue
                    }

                    val file = files[i]
                    val thumbFile = File(thumbCacheDir, "${file.name}.png")
                    var updatedUri = initialList[i].thumbnailUri
                    var updatedDuration = initialList[i].duration

                    val retriever = MediaMetadataRetriever()
                    try {
                        var fileReadable = false
                        var attempts = 0
                        while (!fileReadable && attempts < 10) {
                            try {
                                FileInputStream(file).use { fis -> retriever.setDataSource(fis.fd) }
                                fileReadable = true
                            } catch (e: Exception) {
                                attempts++
                                if (attempts < 10) delay(500)
                            }
                        }

                        if (fileReadable) {
                            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                            updatedDuration = formatDuration(durationMs)

                            if (!thumbFile.exists()) {
                                val timeUs = if (durationMs > 2000) (durationMs / 2) * 1000 else 1000000L
                                val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                if (bitmap != null) {
                                    FileOutputStream(thumbFile).use { out ->
                                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                                    }
                                    updatedUri = Uri.fromFile(thumbFile)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        try { retriever.release() } catch (e: Exception) {}
                    }

                    // Only update the state if something actually changed
                    if (updatedDuration != initialList[i].duration || updatedUri != initialList[i].thumbnailUri) {
                        initialList[i] = initialList[i].copy(duration = updatedDuration, thumbnailUri = updatedUri)
                        _filesListState.value = FilesListState.Success(initialList.toList())
                    }
                    
                    // Add a tiny delay to let Garbage Collection clean up Bitmaps to prevent OOM
                    delay(50)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _filesListState.value = FilesListState.Error("Failed to scan directory: ${e.message}")
            }
        }
        return fetchJob
    }
    
    // PARSING LOGIC: Extracts clean title and (Resolution)
    private fun parseFileName(fileName: String): Pair<String, String> {
        val lastIndex = fileName.lastIndexOf('.')
        if (lastIndex == -1) return fileName to "(MP4)"
        
        val nameWithoutExt = fileName.substring(0, lastIndex)
        
        val signatureRegex = """(.*)[\s_](\(\d+p?\))""".toRegex()
        val matchResult = signatureRegex.find(nameWithoutExt)
        
        return if (matchResult != null) {
            val (title, signature) = matchResult.destructured
            title.replace("_", " ") to signature
        } else {
            nameWithoutExt.replace("_", " ") to "(MP4)"
        }
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = (durationMs / 1000) % 60
        val minutes = (durationMs / (1000 * 60)) % 60
        val hours = (durationMs / (1000 * 60 * 60)) % 24
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    fun playVideo(context: Context, fileDetails: DownloadedFileDetails) {
        try {
            val file = File(fileDetails.path)
            if (!file.exists()) return

            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Play with..."))
        } catch (e: Exception) {
            e.printStackTrace()
            // Optionally, show a toast or update a state with the error
        }
    }

    fun shareVideo(context: Context, fileDetails: DownloadedFileDetails) {
        try {
            val file = File(fileDetails.path)
            if (!file.exists()) return

            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share video..."))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearThumbnailCache(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val thumbCacheDir = File(context.cacheDir, "thumbnails")
                if (thumbCacheDir.exists() && thumbCacheDir.isDirectory) {
                    thumbCacheDir.listFiles()?.forEach { it.delete() }
                }
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Thumbnail cache cleared", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun resetState(url: String) {
        DownloadManager.removeDownload(url)
    }
}