package com.videofetcher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import kotlinx.coroutines.flow.first
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
        
        val initialNotification = createNotification("Starting Download...", "0% • Starting...", 0)
        startForeground(notificationId, initialNotification.build())
        
        val job = serviceScope.launch {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            try {
                // Non-blocking suspend until engine is fully loaded or error
                val currentEngineState = DownloadManager.engineState.first { it !is EngineState.Initializing }
                if (currentEngineState is EngineState.Error) {
                    throw Exception("Engine is not ready: ${currentEngineState.message}")
                }

                val permissionManager = PermissionManager(applicationContext)
                val customPath = permissionManager.getCustomDownloadFolderPath()
                val targetDir = File(customPath)
                if (!targetDir.exists()) targetDir.mkdirs()

                val cleanUrl = try {
                    android.net.Uri.parse(url).buildUpon().clearQuery().build().toString()
                } catch (e: Exception) {
                    url
                }
                val request = YoutubeDLRequest(cleanUrl)
                
                val domainKey = com.videofetcher.cookies.NetscapeCookieWriter.getDomainKey(cleanUrl)
                val platformCookieFile = com.videofetcher.cookies.NetscapeCookieWriter.getCookieFileForUrl(applicationContext, cleanUrl)
                if (platformCookieFile != null) {
                    request.addOption("--cookies", platformCookieFile.absolutePath)
                    val effectiveUserAgent = com.videofetcher.cookies.UserAgentManager.getEffectiveUserAgentForDomain(applicationContext, domainKey)
                    request.addOption("--user-agent", effectiveUserAgent)
                    request.addOption("--retries", "3")
                    request.addOption("--fragment-retries", "5")
                }

                // Global Speed Optimizations
                request.addOption("--no-playlist")
                request.addOption("--no-warnings")
                request.addOption("--buffer-size", "64K")
                if (domainKey.lowercase() != "instagram") {
                    request.addOption("--force-ipv4")
                }

                // User Preference Speed Toggles
                if (permissionManager.isBypassSslEnabled()) {
                    request.addOption("--no-check-certificates")
                }
                if (permissionManager.isBypassExtractorEnabled() && domainKey.lowercase() !in listOf("youtube", "facebook", "instagram", "tiktok")) {
                    request.addOption("--force-generic-extractor")
                }
                
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
                    // Cap automatic "Best Quality" to max 1080p for mobile playback hardware compatibility with pre-merged b/best fallback
                    request.addOption("-f", "b/best/bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=1080]+bestaudio/best[height<=1080]")
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
                    // Force H.264/AVC compatible codecs for Android gallery support with pre-merged b/best fallback
                    request.addOption("-f", "b/best/bestvideo[height<=$targetHeight][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=$targetHeight]+bestaudio/best[height<=$targetHeight]")
                }
                
                // Embed thumbnail safely by converting WebP thumbnails to JPG first via FFmpeg
                request.addOption("--embed-thumbnail")
                request.addOption("--convert-thumbnails", "jpg")
                request.addOption("--no-mtime")
                if (quality != "Best Quality (M4A)" && !quality.startsWith("Audio (MP3)")) {
                    request.addOption("--merge-output-format", "mp4")
                }
                val tempDir = File(targetDir, ".vdf_temp")
                if (!tempDir.exists()) tempDir.mkdirs()

                val initialFiles = targetDir.listFiles()?.map { it.absolutePath }?.toSet() ?: emptySet()
                request.addOption("--trim-filenames", "80")
                request.addOption("-o", "${tempDir.absolutePath}/%(title,id,uuid)s_${resolutionSignature}_vdf.%(ext)s")
                request.addOption("--concurrent-fragments", "1")
                request.addOption("--http-chunk-size", "10M")

                var downloadFinished = false
                var lastUpdateTime = 0L

                try {
                    YoutubeDL.getInstance().execute(request, processId) { progress, etaInSeconds, line ->
                        if (!isActive) {
                            return@execute
                        }

                        val trimmedLine = line.trim()
                        val isFinishedDownload = line.contains("[download] 100%") || line.contains("100% of") || progress >= 100f
                        val isConverting = line.contains("[ffmpeg]") || line.contains("Merging")
                        if (isFinishedDownload) downloadFinished = true

                        val currentTime = System.currentTimeMillis()

                        if (isConverting || isFinishedDownload || trimmedLine.isNotEmpty() || (currentTime - lastUpdateTime > 200)) {
                            lastUpdateTime = currentTime

                            val statusText = if (trimmedLine.isNotEmpty()) {
                                if (progress > 0f && !isConverting) {
                                    "${String.format("%.1f", progress)}% • $trimmedLine"
                                } else {
                                    trimmedLine
                                }
                            } else if (isConverting) {
                                "Converting & Merging... Please wait"
                            } else {
                                "${String.format("%.1f", progress)}% • ETA: ${etaInSeconds}s"
                            }

                            DownloadManager.updateDownloadState(url, DownloadState.Downloading(
                                progress = if (isConverting) 1f else (progress / 100f).coerceIn(0f, 1f),
                                status = statusText
                            ))

                            val titleText = if (isConverting) "Converting & Merging..." else "Downloading Video..."
                            val notification = createNotification(titleText, statusText, progress.toInt())
                            notificationManager.notify(notificationId, notification.build())
                        }
                    }
                } catch (postEx: Exception) {
                    when {
                        !isActive || postEx.message?.contains("Process destroyed") == true -> {
                            // Cancelled – do nothing
                        }
                        downloadFinished -> {
                            // Download finished before FFmpeg post-processing cleanup log
                        }
                        else -> {
                            throw postEx
                        }
                    }
                }

                if (isActive) {
                    val tempOutputFile = tempDir.listFiles()?.firstOrNull { it.isFile && it.name.contains("_vdf.") && it.length() > 0 }
                    if (downloadFinished && tempOutputFile != null && tempOutputFile.exists()) {
                        val originalName = tempOutputFile.name
                        val ext = tempOutputFile.extension
                        val nameWithoutExt = tempOutputFile.nameWithoutExtension

                        val (baseName, vdfSuffix) = if (nameWithoutExt.endsWith("_vdf", ignoreCase = true)) {
                            nameWithoutExt.substring(0, nameWithoutExt.length - 4) to "_vdf"
                        } else {
                            nameWithoutExt to ""
                        }

                        var destFile = File(targetDir, originalName)
                        var counter = 1
                        while (destFile.exists()) {
                            destFile = File(targetDir, "${baseName}_${counter}${vdfSuffix}.${ext}")
                            counter++
                        }

                        val moved = tempOutputFile.renameTo(destFile)
                        if (!moved) {
                            tempOutputFile.copyTo(destFile, overwrite = true)
                            tempOutputFile.delete()
                        }
                        tempDir.deleteRecursively()

                        DownloadManager.updateDownloadState(url, DownloadState.Success("Video successfully saved!"))

                        val clickIntent = Intent(this@DownloadService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        val pendingIntent = PendingIntent.getActivity(
                            this@DownloadService,
                            0,
                            clickIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )

                        val successNotification = NotificationCompat.Builder(this@DownloadService, CHANNEL_ID)
                            .setSmallIcon(R.mipmap.ic_launcher)
                            .setContentTitle("Video Downloaded")
                            .setContentText("Successfully saved to ${targetDir.name}")
                            .setContentIntent(pendingIntent)
                            .setAutoCancel(true)
                            .build()
                        notificationManager.notify(notificationId + 1, successNotification)
                        notificationManager.cancel(notificationId)

                        // Trigger MediaStore scan via absolute path so gallery sees the file
                        MediaScannerConnection.scanFile(
                            applicationContext,
                            arrayOf(destFile.absolutePath),
                            null
                        ) { _, _ -> DownloadManager.triggerFileRefresh() }
                    } else {
                        throw Exception("Download file not found on disk after completion.")
                    }
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
                        rawError.contains("confirm your age", ignoreCase = true) || rawError.contains("Private video", ignoreCase = true) || rawError.contains("login to view", ignoreCase = true) || rawError.contains("login required", ignoreCase = true) -> "Login required."
                        rawError.contains("Not Found", ignoreCase = true) || rawError.contains("404", ignoreCase = true) -> "Video not found or private."
                        rawError.contains("403", ignoreCase = true) || rawError.contains("Forbidden", ignoreCase = true) -> "Access forbidden (HTTP 403)."
                        rawError.contains("Requested format", ignoreCase = true) -> "Selected format not available."
                        else -> rawError.lines().firstOrNull { it.contains("ERROR:", ignoreCase = true) }?.substringAfter("ERROR:")?.trim()?.take(80)
                                ?: rawError.take(80).ifEmpty { "Couldn't download this video." }
                    }
                    
                    DownloadManager.updateDownloadState(url, DownloadState.Error(friendlyMessage))

                    val clickIntent = Intent(this@DownloadService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val pendingIntent = PendingIntent.getActivity(
                        this@DownloadService,
                        0,
                        clickIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    val errorNotification = NotificationCompat.Builder(this@DownloadService, CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle("Download Failed")
                        .setContentText(friendlyMessage)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(friendlyMessage))
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true)
                        .build()

                    notificationManager.notify(notificationId + 2, errorNotification)
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

    private fun createNotification(title: String, status: String, progress: Int): NotificationCompat.Builder {
        val clickIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(status)
            .setProgress(100, progress, false) // Solid progress bar right from 0%
            .setColor(android.graphics.Color.parseColor("#2196F3")) // Standard Download Blue
            .setContentIntent(pendingIntent)
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