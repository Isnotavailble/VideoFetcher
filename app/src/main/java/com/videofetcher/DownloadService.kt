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
import kotlinx.coroutines.launch
import java.io.File

class DownloadService : Service() {
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    
    private val CHANNEL_ID = "VideoFetcherDownloadChannel"
    private val NOTIFICATION_ID = 1001
    
    private var isCancelled = false
    private var isPausing = false

    private var currentUrl = ""
    private var currentQuality = ""
    private var currentProgress = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "PAUSE_DOWNLOAD") {
            isPausing = true
            isCancelled = true
            stopProcess()
            return START_NOT_STICKY
        }

        if (intent?.action == "CANCEL_DOWNLOAD") {
            isPausing = false
            isCancelled = true
            DownloadManager.updateState(DownloadState.Cancelled)
            if (currentUrl.isNotEmpty()) {
                PauseRepository(applicationContext).removePausedDownload(currentUrl)
            }
            stopProcess()
            return START_NOT_STICKY
        }

        val url = intent?.getStringExtra("URL") ?: return START_NOT_STICKY
        val quality = intent.getStringExtra("QUALITY") ?: "1080p"

        if (DownloadManager.isDownloading()) {
            return START_NOT_STICKY
        }

        isCancelled = false
        isPausing = false
        currentUrl = url
        currentQuality = quality
        currentProgress = 0f

        DownloadManager.updateState(DownloadState.Downloading(0f, "0% • Starting..."))

        val initialNotification = createNotification("0% • Starting...", 0)
        startForeground(NOTIFICATION_ID, initialNotification.build())

        startBackgroundDownload(url, quality)

        return START_NOT_STICKY
    }

    private fun startBackgroundDownload(url: String, quality: String) {
        serviceScope.launch {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(downloadsDir, "VideoFetcher")
                if (!targetDir.exists()) targetDir.mkdirs()

                // Ensure engines are initialized. If already installed, this skips instantly (~1ms).
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)

                val request = YoutubeDLRequest(url)
                val resolution = quality.replace("p", "")
                val resolutionSignature = "(${resolution}p)"

                request.addOption("-f", "bestvideo[height<=$resolution]+bestaudio/best")
                request.addOption("--merge-output-format", "mp4")
                request.addOption("--restrict-filenames")
                request.addOption("-o", "${targetDir.absolutePath}/%(title)s_${resolutionSignature}.%(ext)s")
                
                // Force multi-threaded downloading for DASH streams to prevent throttling
                request.addOption("--concurrent-fragments", "4")

                var lastUpdateTime = 0L
                var lastProgress = -1f

                YoutubeDL.getInstance().execute(request, "downloader_process") { progress, etaInSeconds, line ->
                    if (isCancelled) return@execute
                    
                    currentProgress = progress

                    val isConverting = line.contains("[ffmpeg]") || line.contains("Merging") || progress >= 100f
                    val currentTime = System.currentTimeMillis()

                    if (isConverting || progress >= 100f || (currentTime - lastUpdateTime > 500 && progress != lastProgress)) {
                        lastUpdateTime = currentTime
                        lastProgress = progress

                        val statusText = if (isConverting) {
                            "Converting & Merging... Please wait"
                        } else {
                            "${String.format("%.1f", progress)}% • ETA: ${etaInSeconds}s"
                        }

                        DownloadManager.updateState(DownloadState.Downloading(
                            progress = if (isConverting) 1f else (progress / 100f),
                            status = statusText
                        ))

                        val notification = createNotification(statusText, progress.toInt())
                        notificationManager.notify(NOTIFICATION_ID, notification.build())
                    }
                }

                if (!isCancelled) {
                    DownloadManager.updateState(DownloadState.Success("Video successfully saved!"))

                    val successNotification = NotificationCompat.Builder(this@DownloadService, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle("Video Downloaded")
                        .setContentText("Successfully saved to Downloads/VideoFetcher")
                        .build()
                    notificationManager.notify(NOTIFICATION_ID + 1, successNotification)

                    // Find the newest file and trigger a media scan to make it visible in the gallery
                    val newFile = targetDir.listFiles()?.maxByOrNull { it.lastModified() }
                    if (newFile != null) {
                        MediaScannerConnection.scanFile(applicationContext, arrayOf(newFile.absolutePath), null, null)
                    }
                }

            } catch (e: Exception) {
                if (isPausing) {
                    // Expected exception when process is destroyed for pausing. Ignore safely.
                } else if (!isCancelled && e.message?.contains("Process destroyed") != true) {
                    e.printStackTrace()
                    val rawError = e.message ?: ""
                    val friendlyMessage = when {
                        rawError.contains("is not a valid URL", ignoreCase = true) -> "Invalid video URL."
                        rawError.contains("Unsupported URL", ignoreCase = true) -> "Website not supported yet."
                        rawError.contains("Sign in", ignoreCase = true) || rawError.contains("login", ignoreCase = true) -> "Login required."
                        rawError.contains("Not Found", ignoreCase = true) || rawError.contains("404", ignoreCase = true) -> "Video not found or private."
                        else -> "Couldn't download this video."
                    }
                    
                    DownloadManager.updateState(DownloadState.Error(friendlyMessage))

                    val errorNotification = NotificationCompat.Builder(this@DownloadService, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_notify_error)
                        .setContentTitle("Download Failed")
                        .setContentText(friendlyMessage)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(friendlyMessage))
                        .build()
                    notificationManager.notify(NOTIFICATION_ID + 2, errorNotification)
                }
            } finally {
                if (isPausing) {
                    PauseRepository(applicationContext).savePausedDownload(
                        PausedDownload(currentUrl, "Video", currentQuality, currentProgress)
                    )
                    DownloadManager.updateState(DownloadState.Idle)
                } else if (isCancelled) {
                    DownloadManager.updateState(DownloadState.Cancelled)
                }

                @Suppress("DEPRECATION")
                stopForeground(true)
                stopSelf()
            }
        }
    }

    private fun stopProcess() {
        serviceScope.launch {
            try {
                YoutubeDL.getInstance().destroyProcessById("downloader_process")
            } catch (e: Exception) {
                e.printStackTrace()
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