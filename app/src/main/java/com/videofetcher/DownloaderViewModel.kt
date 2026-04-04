package com.videofetcher

import android.content.Context
import android.graphics.Bitmap
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

                YoutubeDL.getInstance().execute(request, "downloader_process") { progress, etaInSeconds, line ->
                    val isConverting = line.contains("[ffmpeg]") || line.contains("Merging") || progress >= 100f

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

                // Check if it was manually cancelled
                if (_downloadState.value !is DownloadState.Cancelled) {
                    _downloadState.value = DownloadState.Success("Video successfully saved!")
                    // FIXED: Pass the context so it can actually find the thumbnails folder
                    fetchDownloadedFiles(context)
                }
                
            } catch (e: Exception) {
                if (e.message?.contains("Process destroyed") == true) {
                    _downloadState.value = DownloadState.Cancelled
                } else {
                    _downloadState.value = DownloadState.Error(e.message ?: "An unknown error occurred")
                }
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

                val fileDetailsList = ArrayList<DownloadedFileDetails>()
                
                val thumbCacheDir = File(context?.cacheDir, "thumbnails")
                if (!thumbCacheDir.exists()) {
                    thumbCacheDir.mkdirs()
                }

                for (file in files) {
                    val fileName = file.name
                    val (title, signature) = parseFileName(fileName)
                    val size = formatFileSize(file.length())
                    var duration = "00:00"

                    var thumbnailUri = Uri.EMPTY
                    val thumbFile = File(thumbCacheDir, "${fileName}.png")
                    
                    if (thumbFile.exists()) {
                        thumbnailUri = Uri.fromFile(thumbFile)
                    }

                    // FIXED: A fresh retriever created INSIDE the loop so one file doesn't crash the rest
                    val retriever = MediaMetadataRetriever()
                    try {
                        // SMART SYNC POLLING: Wait for FFmpeg to unlock the file
                        var fileReadable = false
                        var attempts = 0
                        while (!fileReadable && attempts < 10) {
                            try {
                                FileInputStream(file).use { fis ->
                                    retriever.setDataSource(fis.fd)
                                }
                                fileReadable = true
                            } catch (e: Exception) {
                                attempts++
                                if (attempts < 10) {
                                    delay(500)
                                } else {
                                    throw Exception("File locked after 5 seconds")
                                }
                            }
                        }

                        duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.let {
                            formatDuration(it.toLong())
                        } ?: "00:00"
                        
                        if (!thumbFile.exists()) {
                            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                            val timeUs = if (durationMs > 2000) (durationMs / 2) * 1000 else 1000000L
                            
                            val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            if (bitmap != null) {
                                FileOutputStream(thumbFile).use { out ->
                                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                                }
                                thumbnailUri = Uri.fromFile(thumbFile)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        try {
                            retriever.release()
                        } catch (e: Exception) {}
                    }

                    fileDetailsList.add(
                        DownloadedFileDetails(
                            title = title,
                            path = file.absolutePath,
                            signature = signature,
                            size = size,
                            duration = duration,
                            thumbnailUri = thumbnailUri
                        )
                    )
                }
                _filesListState.value = FilesListState.Success(fileDetailsList)
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