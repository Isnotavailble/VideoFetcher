package com.videofetcher.manager

import android.content.Context
import android.content.Intent
import com.videofetcher.DownloadService

class DownloadQueueManager(
    private val downloadManager: DownloadManager,
    private val pauseManager: PauseManager
) {
    fun startDownload(context: Context, url: String, quality: String, thumbUrl: String, titleText: String) {
        if (url.isBlank()) return

        if (thumbUrl.isNotBlank()) {
            downloadManager.updateDownloadThumbnail(url, thumbUrl)
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

    fun resumeDownload(context: Context, url: String, quality: String, thumbUrl: String, titleText: String) {
        pauseManager.removePausedDownload(url)
        startDownload(context, url, quality, thumbUrl, titleText)
    }
}
