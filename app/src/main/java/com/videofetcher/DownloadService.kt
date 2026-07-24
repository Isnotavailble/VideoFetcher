package com.videofetcher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class DownloadService : Service() {
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    
    private val CHANNEL_ID = "VideoFetcherDownloadChannel"
    
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeQualities = ConcurrentHashMap<String, String>()
    private val pendingQueue = CopyOnWriteArrayList<Pair<String, String>>()
    
    // Temporary hardcode: Will be connected to Settings in Phase 3
    private val maxParallelDownloads = 3

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val url = intent?.getStringExtra("URL") ?: return START_NOT_STICKY
        
        when (action) {
            "PAUSE_DOWNLOAD" -> pauseDownload(url)
            "CANCEL_DOWNLOAD" -> cancelDownload(url)
            "START_DOWNLOAD" -> {
                val quality = intent.getStringExtra("QUALITY") ?: "1080p"
                
                if (activeJobs.containsKey(url) || pendingQueue.any { it.first == url }) {
                    return START_NOT_STICKY // Already active or queued
                }

                if (activeJobs.size >= maxParallelDownloads) {
                    pendingQueue.add(Pair(url, quality))
                    DownloadManager.updateDownloadState(url, DownloadState.Queued)
                } else {
                    startBackgroundDownload(url, quality)
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun startBackgroundDownload(url: String, quality: String) {
        val processId = "downloader_${url.hashCode()}"
        val notificationId = url.hashCode()
        
        activeQualities[url] = quality
        DownloadManager.updateDownloadState(url, DownloadState.Downloading(0f, "0% • Starting..."))
        
        val initialNotification = createNotification("0% • Starting...", 0)
        startForeground(notificationId, initialNotification.build())
        
        val job = serviceScope.launch {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            try {
                // SAFETY NET: Ensure engine is initialized before attempting download.
                // This takes a few seconds on first launch, but is nearly instant on subsequent runs.
                try {
                    YoutubeDL.getInstance().init(applicationContext)
                    FFmpeg.getInstance().init(applicationContext)
                } catch (e: Exception) {
                    throw Exception("Failed to initialize engine: ${e.message}")
                }

                val permissionManager = PermissionManager(applicationContext)
                val customPath = permissionManager.getCustomDownloadFolderPath()
                val targetDir = File(customPath)
                if (!targetDir.exists()) targetDir.mkdirs()

                val request = YoutubeDLRequest(url)
                
                val platformCookieFile = com.videofetcher.cookies.NetscapeCookieWriter.getCookieFileForUrl(applicationContext, url)
                if (platformCookieFile != null) {
                    request.addOption("--cookies", platformCookieFile.absolutePath)
                    val savedUserAgent = permissionManager.getUserAgent()
                    if (!savedUserAgent.isNullOrBlank()) {
                        request.addOption("--user-agent", savedUserAgent)
                    }
                }
                
                // Force IPv4 to prevent 15-30s timeout hangs on mobile network IPv6 addresses
                request.addOption("--force-ipv4")
                
                val targetHeight = when {
                    quality == "4K" -> "2160"
                    quality == "2K" -> "1440"
                    quality == "Best Quality" -> "1080"
                    quality == "Best Quality (M4A)" -> "audio"
                    quality.startsWith("Audio (MP3)") -> "audio"
                    else -> quality.replace("p", "")
                }
                
                val resolutionSignature = when {
                    quality == "Best Quality" -> "(Best)"
                    quality == "Best Quality (M4A)" -> "(M4A)"
                    quality == "Audio (MP3) - High Quality" -> "(MP3_High)"
                    quality == "Audio (MP3) - Standard" -> "(MP3_Std)"
                    quality == "Audio (MP3) - Fast" -> "(MP3_Fast)"
                    else -> "(${quality.replace(" ", "_")})"
                }

                if (quality == "Best Quality") {
                    // Cap automatic "Best Quality" to max 1080p for mobile playback hardware compatibility, with /best fallback for TikTok/IG/FB
                    request.addOption("-f", "bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=1080]+bestaudio/best[height<=1080]/best")
                } else if (quality == "Best Quality (M4A)") {
                    request.addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
                    request.addOption("--extract-audio")
                    request.addOption("--audio-format", "m4a")
                } else if (quality.startsWith("Audio (MP3)")) {
                    request.addOption("-f", "bestaudio/best")
                    request.addOption("--extract-audio")
                    request.addOption("--audio-format", "mp3")
                    when (quality) {
                        "Audio (MP3) - High Quality" -> request.addOption("--audio-quality", "320K")
                        "Audio (MP3) - Standard" -> request.addOption("--audio-quality", "192K")
                        "Audio (MP3) - Fast" -> request.addOption("--audio-quality", "128K")
                    }
                } else {
                    // Force H.264/AVC compatible codecs for Android gallery support with universal /best fallback for all platforms
                    request.addOption("-f", "bestvideo[height<=$targetHeight][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=$targetHeight]+bestaudio/best[height<=$targetHeight]/best")
                }
                
                // Embed thumbnail safely by converting WebP thumbnails to JPG first via FFmpeg
                request.addOption("--embed-thumbnail")
                request.addOption("--convert-thumbnails", "jpg")
                request.addOption("--no-mtime")
                if (quality != "Best Quality (M4A)" && !quality.startsWith("Audio (MP3)")) {
                    request.addOption("--merge-output-format", "mp4")
                }
                request.addOption("--restrict-filenames")
                request.addOption("-o", "${targetDir.absolutePath}/%(title)s_${resolutionSignature}_vdf.%(ext)s")
                request.addOption("--concurrent-fragments", "4")

                var mergeStarted = false
                var lastUpdateTime = 0L
                var lastProgress = -1f

                try {
                    YoutubeDL.getInstance().execute(request, processId) { progress, etaInSeconds, line ->
                        if (!isActive) {
                            return@execute
                        }

                        val isConverting = line.contains("[ffmpeg]") || line.contains("Merging") || progress >= 100f
                        if (isConverting) mergeStarted = true

                        val currentTime = System.currentTimeMillis()

                        if (isConverting || progress >= 100f || (currentTime - lastUpdateTime > 500 && progress != lastProgress)) {
                            lastUpdateTime = currentTime
                            lastProgress = progress

                            val statusText = if (isConverting) {
                                "Converting & Merging... Please wait"
                            } else {
                                "${String.format("%.1f", progress)}% • ETA: ${etaInSeconds}s"
                            }

                            DownloadManager.updateDownloadState(url, DownloadState.Downloading(
                                progress = if (isConverting) 1f else (progress / 100f),
                                status = statusText
                            ))

                            val notification = createNotification(statusText, progress.toInt())
                            notificationManager.notify(notificationId, notification.build())
                        }
                    }
                } catch (postEx: Exception) {
                    when {
                        !isActive || postEx.message?.contains("Process destroyed") == true -> {
                            // Cancelled – do nothing
                        }
                        mergeStarted -> {
                            // Download + merge already completed before this exception.
                            // It is FFmpeg stderr noise (thumbnail embed warning, temp file
                            // cleanup, etc.). Treat as success – fall through to success block.
                        }
                        else -> {
                            // Exception thrown before merge started = genuine download failure.
                            throw postEx
                        }
                    }
                }

                if (isActive) {
                    DownloadManager.updateDownloadState(url, DownloadState.Success("Video successfully saved!"))

                    val successNotification = NotificationCompat.Builder(this@DownloadService, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle("Video Downloaded")
                        .setContentText("Successfully saved to ${targetDir.name}")
                        .build()
                    notificationManager.notify(notificationId + 1, successNotification)
                    notificationManager.cancel(notificationId)

                    // Trigger MediaStore scan via absolute path so gallery sees the file
                    val scanPath = "${targetDir.absolutePath}"
                    MediaScannerConnection.scanFile(
                        applicationContext,
                        arrayOf(scanPath),
                        null
                    ) { _, _ -> DownloadManager.triggerFileRefresh() }
                }

            } catch (e: Exception) {
                if (e.message?.contains("Process destroyed") == true || !isActive) {
                    // Normal behavior when cancelled/paused
                } else {
                    e.printStackTrace()
                    val rawError = e.message ?: ""
                    val friendlyMessage = when {
                        rawError.contains("is not a valid URL", ignoreCase = true) -> "Invalid video URL."
                        rawError.contains("Unsupported URL", ignoreCase = true) -> "Website not supported yet."
                        rawError.contains("confirm your age", ignoreCase = true) || rawError.contains("Private video", ignoreCase = true) || rawError.contains("login to view", ignoreCase = true) -> "Login required."
                        rawError.contains("Not Found", ignoreCase = true) || rawError.contains("404", ignoreCase = true) -> "Video not found or private."
                        else -> "Couldn't download this video."
                    }
                    
                    DownloadManager.updateDownloadState(url, DownloadState.Error(friendlyMessage))
                    notificationManager.cancel(notificationId)
                }
            } finally {
                activeJobs.remove(url)
                activeQualities.remove(url)
                checkPendingQueue()
                
                if (activeJobs.isEmpty()) {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            }
        }
        activeJobs[url] = job
    }

    private fun pauseDownload(url: String) {
        serviceScope.launch {
            val job = activeJobs.remove(url)
            job?.cancel()
            try {
                YoutubeDL.getInstance().destroyProcessById("downloader_${url.hashCode()}")
            } catch (e: Exception) { e.printStackTrace() }
            
            val lastState = DownloadManager.activeDownloads.value[url]
            val progress = if (lastState is DownloadState.Downloading) lastState.progress * 100f else 0f
            val quality = activeQualities.remove(url) ?: "1080p"
            
            PauseRepository(applicationContext).savePausedDownload(
                PausedDownload(url, "Video", quality, progress)
            )
            DownloadManager.updateDownloadState(url, DownloadState.Cancelled)
            DownloadManager.removeDownload(url)
            checkPendingQueue()
        }
    }

    private fun cancelDownload(url: String) {
        serviceScope.launch {
            val pendingItem = pendingQueue.find { it.first == url }
            if (pendingItem != null) {
                pendingQueue.remove(pendingItem)
                DownloadManager.removeDownload(url)
                return@launch
            }

            val job = activeJobs.remove(url)
            job?.cancel()
            try {
                YoutubeDL.getInstance().destroyProcessById("downloader_${url.hashCode()}")
            } catch (e: Exception) { e.printStackTrace() }
            
            DownloadManager.updateDownloadState(url, DownloadState.Cancelled)
            PauseRepository(applicationContext).removePausedDownload(url)
            checkPendingQueue()
        }
    }

    private fun checkPendingQueue() {
        if (activeJobs.size < maxParallelDownloads) {
            if (pendingQueue.isNotEmpty()) {
                val next = pendingQueue.removeAt(0)
                startBackgroundDownload(next.first, next.second)
            } else if (activeJobs.isEmpty()) {
                stopSelf()
            }
        }
    }

    private fun createNotification(status: String, progress: Int): NotificationCompat.Builder {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading Video...")
            .setContentText(status)
            .setProgress(100, progress, false) // Solid progress bar right from 0%
            .setColor(android.graphics.Color.parseColor("#2196F3")) // Standard Download Blue
            .setOngoing(true)
            .setOnlyAlertOnce(true)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video Downloads",
                NotificationManager.IMPORTANCE_LOW // Low importance so it doesn't vibrate/beep every second!
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}