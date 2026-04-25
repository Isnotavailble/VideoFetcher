package com.videofetcher

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class DownloaderViewModel : ViewModel() {
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Initializing)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _filesListState = MutableStateFlow<FilesListState>(FilesListState.Idle)
    val filesListState: StateFlow<FilesListState> = _filesListState.asStateFlow()

    private val baseDirName = "VideoFetcher"

    fun initializeEngine(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(context)
                FFmpeg.getInstance().init(context)
                _downloadState.value = DownloadState.Idle
            } catch (e: Exception) {
                e.printStackTrace()
                _downloadState.value = DownloadState.Error("Engine failed to boot: ${e.message}")
            }
        }
    }

    fun startDownload(url: String, quality: String, context: Context) {
        if (url.isBlank()) {
            _downloadState.value = DownloadState.Error("URL cannot be empty")
            return
        }

        _downloadState.value = DownloadState.Downloading(0f, "Starting...")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                
                // Target: Downloads/VideoFetcher
                val targetDir = File(downloadsDir, baseDirName)
                if (!targetDir.exists()) {
                    if (!targetDir.mkdirs()) {
                        throw Exception("Could not create target directory: ${targetDir.absolutePath}")
                    }
                }

                val request = YoutubeDLRequest(url)
                val resolution = quality.replace("p", "")
                
                // This is the clean signature we add to the end: (1080p)
                val resolutionSignature = "(${resolution}p)"

                request.addOption("-f", "bestvideo[height<=$resolution]+bestaudio/best")
                request.addOption("--merge-output-format", "mp4")
                request.addOption("--restrict-filenames")
                
                // Saves as: /path/Video_Title_(1080p).mp4 
                request.addOption("-o", "${targetDir.absolutePath}/%(title)s_${resolutionSignature}.%(ext)s")

                var lastUpdateTime = 0L
                var lastProgress = -1f

                YoutubeDL.getInstance().execute(request, "downloader_process") { progress, etaInSeconds, line ->
                    val isConverting = line.contains("[ffmpeg]") || line.contains("Merging") || progress >= 100f
                    val currentTime = System.currentTimeMillis()

                    // Throttle updates: Only update if converting, finished, or 300ms have passed with new progress
                    if (isConverting || progress >= 100f || (currentTime - lastUpdateTime > 300 && progress != lastProgress)) {
                        lastUpdateTime = currentTime
                        lastProgress = progress

                        val currentStatus = if (isConverting) {
                            "Converting & Merging to MP4... Please wait"
                        } else {
                            "Downloading: ${String.format("%.1f", progress)}% (ETA: ${etaInSeconds}s)"
                        }

                        _downloadState.value = DownloadState.Downloading(
                            progress = if (isConverting) 1f else (progress / 100f),
                            status = currentStatus
                        )
                    }
                }

                // Check if it was manually cancelled
                if (_downloadState.value !is DownloadState.Cancelled) {
                    _downloadState.value = DownloadState.Success("Video successfully saved!")

                    // Find the newest file in the directory (the one we just downloaded)
                    val newFile = targetDir.listFiles()?.maxByOrNull { it.lastModified() }
                    if (newFile != null) {
                        // Trigger a media scan to make the video appear in the gallery immediately
                        MediaScannerConnection.scanFile(context, arrayOf(newFile.absolutePath), null, null)
                    }

                    fetchDownloadedFiles(context)
                }
				
                
            } catch (e: Exception) {
                // 1. If the user clicked Cancel, ignore the resulting crash
                if (_downloadState.value is DownloadState.Cancelled || e.message?.contains("Process destroyed") == true) {
                    _downloadState.value = DownloadState.Cancelled
                    return@launch
                }

                // Log the real, ugly error to the console just in case you need to debug it later
                e.printStackTrace()

                // 2. Smart Error Mapper: Translate raw terminal errors into friendly UI messages
                val rawError = e.message ?: ""
                val friendlyMessage = when {
                    rawError.contains("is not a valid URL", ignoreCase = true) -> "The link provided is not a valid video URL."
                    rawError.contains("Unsupported URL", ignoreCase = true) -> "We don't support downloading from this website yet."
                    rawError.contains("Sign in", ignoreCase = true) || rawError.contains("login", ignoreCase = true) -> "Login required. Tip: Don't copy-paste the link. Instead, use the 'Share with this app' button and choose VideoFetcher!"
                    rawError.contains("Not Found", ignoreCase = true) || rawError.contains("404", ignoreCase = true) -> "Video not found. The link might be broken or private."
                    else -> "Couldn't download this video. Please check the link and try again."
                }

                _downloadState.value = DownloadState.Error(friendlyMessage)
            }
        }
    }

    fun cancelDownload() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().destroyProcessById("downloader_process")
                _downloadState.value = DownloadState.Cancelled
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun fetchDownloadedFiles(context: Context?) {
        _filesListState.value = FilesListState.Fetching
        viewModelScope.launch(Dispatchers.IO) {
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

                // STEP 1: Fast load - Instantly show files without waiting for heavy extraction
                val initialList = files.map { file ->
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
                }.toMutableList()

                // Immediately update UI with names and sizes
                _filesListState.value = FilesListState.Success(initialList.toList())

                // STEP 2: Lazy processing - Fetch durations and missing thumbnails in background
                for (i in files.indices) {
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

    fun resetState() {
        _downloadState.value = DownloadState.Idle
    }
}