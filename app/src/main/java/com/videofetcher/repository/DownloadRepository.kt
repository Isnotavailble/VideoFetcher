package com.videofetcher.repository

import android.content.Context
import com.videofetcher.manager.DownloadQueueManager
import com.videofetcher.manager.YoutubeDlManager
import com.videofetcher.manager.YoutubeVideoMetadata

import com.videofetcher.manager.DownloadManager
import com.videofetcher.manager.PauseManager
import com.videofetcher.manager.PausedDownload
import kotlinx.coroutines.flow.StateFlow

class DownloadRepository(
    private val youtubeDlManager: YoutubeDlManager,
    private val downloadQueueManager: DownloadQueueManager,
    private val downloadManager: DownloadManager,
    private val pauseManager: PauseManager
) {
    val engineState: StateFlow<DownloadManager.EngineState> = downloadManager.engineState
    val activeDownloads: StateFlow<Map<String, DownloadManager.DownloadState>> = downloadManager.activeDownloads
    fun removeDownload(url: String) {
        downloadManager.removeDownload(url)
    }

    fun getAllPausedDownloads(): List<PausedDownload> {
        return pauseManager.getAllPausedDownloads()
    }

    fun removePausedDownload(url: String) {
        pauseManager.removePausedDownload(url)
    }
    suspend fun fetchVideoMetadata(url: String, context: Context): YoutubeVideoMetadata {
        return youtubeDlManager.fetchVideoMetadata(url, context)
    }

    fun startDownload(context: Context, url: String, quality: String, thumbUrl: String, titleText: String) {
        downloadQueueManager.startDownload(context, url, quality, thumbUrl, titleText)
    }

    fun pauseDownload(context: Context, url: String) {
        downloadQueueManager.pauseDownload(context, url)
    }

    fun cancelDownload(context: Context, url: String) {
        downloadQueueManager.cancelDownload(context, url)
    }

    fun resumeDownload(context: Context, url: String, quality: String, thumbUrl: String, titleText: String) {
        downloadQueueManager.resumeDownload(context, url, quality, thumbUrl, titleText)
    }
}
