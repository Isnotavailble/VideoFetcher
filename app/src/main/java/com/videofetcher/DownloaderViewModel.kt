package com.videofetcher
import com.videofetcher.manager.DownloadManager
import com.videofetcher.manager.PermissionManager

import android.content.Context
import android.content.ContentUris
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.DocumentsContract
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import com.videofetcher.manager.PauseManager
import com.videofetcher.manager.PausedDownload

private val FILE_SIGNATURE_REGEX = Regex(".*_vdf\\.(mp4|mp3|m4a|aac|flac|opus|wav|ogg|mkv|webm|3gp)$", RegexOption.IGNORE_CASE)

sealed class VideoInfoState {
    object Idle : VideoInfoState()
    object Fetching : VideoInfoState()
    data class Success(
        val title: String,
        val duration: String,
        val thumbnailUrl: String,
        val formats: List<String>
    ) : VideoInfoState()
    data class Error(val message: String) : VideoInfoState()
}



class DownloaderViewModel(private val container: AppContainer) : ViewModel() {
    val engineState: StateFlow<EngineState> = container.downloadManager.engineState
    val activeDownloads: StateFlow<Map<String, DownloadState>> = container.downloadManager.activeDownloads

    private val _pausedDownloads = MutableStateFlow<List<PausedDownload>>(emptyList())
    val pausedDownloads: StateFlow<List<PausedDownload>> = _pausedDownloads.asStateFlow()

    private val _videoInfoState = MutableStateFlow<VideoInfoState>(VideoInfoState.Idle)
    val videoInfoState: StateFlow<VideoInfoState> = _videoInfoState.asStateFlow()



    private val baseDirName = "VideoFetcher"
    private var fetchJob: Job? = null
    private var analyzeJob: Job? = null

    // ContentObserver for detecting external file deletions (e.g. via another file manager).
    // Uses a 300ms debounce Handler to collapse rapid successive MediaStore events into one call.
    // Calls triggerExternalRefresh() which has a 3s suppression window to avoid double-render
    // when the app's own MediaScannerConnection.scanFile() triggers the observer.
    private var appContext: Context? = null
    private val debounceHandler = Handler(Looper.getMainLooper())
    private val externalRefreshRunnable = Runnable { container.downloadManager.triggerExternalRefresh() }
    private val mediaStoreObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            debounceHandler.removeCallbacks(externalRefreshRunnable)
            debounceHandler.postDelayed(externalRefreshRunnable, 300L)
        }
    }

    init {
        // Observer is registered lazily when fetchDownloadedFiles() is first called with a Context.
        // See registerMediaObserver(context).
    }

    private fun registerMediaObserver(context: Context) {
        if (appContext != null) return // Already registered
        appContext = context.applicationContext
        val resolver = appContext!!.contentResolver
        resolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaStoreObserver
        )
        resolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaStoreObserver
        )
    }

    override fun onCleared() {
        super.onCleared()
        debounceHandler.removeCallbacks(externalRefreshRunnable)
        appContext?.contentResolver?.unregisterContentObserver(mediaStoreObserver)
        appContext = null
    }





    fun dismissUpdatePrompt(context: Context) {
        com.videofetcher.manager.EngineUpdateManager(context).markUpdateSkippedForNow()
    }

    fun analyzeUrl(url: String, context: Context? = null) {
        analyzeJob?.cancel()
        if (url.isBlank()) {
            _videoInfoState.value = VideoInfoState.Idle
            return
        }

        // Basic URL format validation before hitting the engine
        val urlRegex = """^(https?|ftp)://[^\s/$.?#].[^\s]*$""".toRegex(RegexOption.IGNORE_CASE)
        if (!urlRegex.matches(url)) {
            _videoInfoState.value = VideoInfoState.Error("Invalid URL format")
            return
        }

        analyzeJob = viewModelScope.launch(Dispatchers.IO) {
            _videoInfoState.value = VideoInfoState.Fetching
            try {
                val cleanUrl = try {
                    android.net.Uri.parse(url).buildUpon().clearQuery().build().toString()
                } catch (e: Exception) {
                    url
                }
                val request = YoutubeDLRequest(cleanUrl)

                if (context != null) {
                    val domainKey = container.cookieManager.getDomainKey(cleanUrl)
                    val platformCookieFile = container.cookieManager.getCookieFileForUrl(context, cleanUrl)
                    val hasCookies = platformCookieFile != null

                    if (platformCookieFile != null) {
                        request.addOption("--cookies", platformCookieFile.absolutePath)
                        request.addOption("--retries", "2")
                        request.addOption("--fragment-retries", "1")
                    }

                    val effectiveUserAgent = container.userAgentManager.getEffectiveUserAgentForDomain(
                        context,
                        domainKey,
                        isAuthenticated = hasCookies
                    )
                    request.addOption("--user-agent", effectiveUserAgent)
                }
                
                val domainKey = if (context != null) container.cookieManager.getDomainKey(cleanUrl) else ""
                
                // 1. Global Speed Optimizations & Aggressive Pruning
                request.addOption("--no-playlist")
                request.addOption("--no-warnings")
                request.addOption("--buffer-size", "64K")
                request.addOption("--no-write-subs")
                
                // 2. Force IPv4 for non-Instagram requests
                if (domainKey.lowercase() != "instagram") {
                    request.addOption("--force-ipv4")
                }

                // 3. User Preference Speed Toggles
                if (context != null) {
                    val permissionManager = container.permissionManager
                    if (permissionManager.isBypassSslEnabled()) {
                        request.addOption("--no-check-certificates")
                    }
                    if (permissionManager.isBypassExtractorEnabled() && domainKey.lowercase() !in listOf("youtube", "facebook", "instagram", "tiktok")) {
                        request.addOption("--force-generic-extractor")
                    }
                }
                
                var info: com.yausername.youtubedl_android.mapper.VideoInfo? = null
                var attempt = 0
                val maxAttempts = if (context != null && container.cookieManager.getCookieFileForUrl(context, cleanUrl) != null) 2 else 1

                while (attempt < maxAttempts && isActive) {
                    attempt++
                    try {
                        info = YoutubeDL.getInstance().getInfo(request)
                        break
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        if (maxAttempts > 1 && container.cookieManager.isAuthException(e.message) && attempt < maxAttempts) {
                            delay(1000)
                        } else if (attempt >= maxAttempts) {
                            throw e
                        }
                    }
                }

                if (info == null) {
                    _videoInfoState.value = VideoInfoState.Error("Failed to fetch media metadata.")
                    return@launch
                }
                
                // Resolution Bucketing (handles vertical videos securely)
                val rawMaxHeight = info.formats
                    ?.filter { it.height > 0 && it.vcodec != "none" }
                    ?.maxOfOrNull { Math.min(it.width.coerceAtLeast(0), it.height) } ?: 0

                val formats = mutableListOf<String>()
                when {
                    rawMaxHeight >= 2160 -> formats.addAll(listOf("4K", "2K", "1080p", "720p", "480p"))
                    rawMaxHeight >= 1440 -> formats.addAll(listOf("2K", "1080p", "720p", "480p"))
                    rawMaxHeight >= 1000 -> formats.addAll(listOf("1080p", "720p", "480p", "360p"))
                    rawMaxHeight >= 720 -> formats.addAll(listOf("720p", "480p", "360p"))
                    rawMaxHeight >= 480 -> formats.addAll(listOf("480p", "360p"))
                    rawMaxHeight > 0 -> formats.add("360p")
                    else -> formats.add("Best Quality")
                }
                formats.add("Best Quality (M4A)")
                formats.add("Audio (MP3) - High Quality")
                formats.add("Audio (MP3) - Standard")
                formats.add("Audio (MP3) - Fast")
                val durationStr = formatDuration((info.duration * 1000).toLong())
                
                withContext(Dispatchers.Main) {
                    _videoInfoState.value = VideoInfoState.Success(
                        title = info.title ?: "Unknown Title",
                        duration = durationStr,
                        thumbnailUrl = info.thumbnail ?: "",
                        formats = formats
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                withContext(Dispatchers.Main) { _videoInfoState.value = VideoInfoState.Error("Couldn't fetch video info. Is the link correct?") }
            }
        }
    }

    fun clearVideoInfo() {
        analyzeJob?.cancel()
        _videoInfoState.value = VideoInfoState.Idle
    }

    fun startDownload(url: String, quality: String, context: Context) {
        if (url.isBlank()) return

        val currentInfo = _videoInfoState.value
        val thumbUrl = if (currentInfo is VideoInfoState.Success) currentInfo.thumbnailUrl else ""
        val titleText = if (currentInfo is VideoInfoState.Success) currentInfo.title else "Video"

        if (thumbUrl.isNotBlank()) {
            container.downloadManager.updateDownloadThumbnail(url, thumbUrl)
        }

        val serviceIntent = Intent(context, DownloadService::class.java).apply {
            action = "START_DOWNLOAD"
            putExtra("URL", url)
            putExtra("QUALITY", quality)
            putExtra("THUMBNAIL_URL", thumbUrl)
            putExtra("TITLE", titleText)
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
        _pausedDownloads.value = PauseManager(context).getAllPausedDownloads()
    }

    fun resumeDownload(context: Context, url: String, quality: String) {
        PauseManager(context).removePausedDownload(url)
        fetchPausedDownloads(context)
        startDownload(url, quality, context)
    }

    fun cancelPausedDownload(context: Context, url: String) {
        PauseManager(context).removePausedDownload(url)
        fetchPausedDownloads(context)
    }



    fun resetState(url: String) {
        container.downloadManager.removeDownload(url)
    }
    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        val m = seconds / 60
        val s = seconds % 60
        val h = m / 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m % 60, s)
        else String.format("%d:%02d", m, s)
    }
}

