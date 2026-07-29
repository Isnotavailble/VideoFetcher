package com.videofetcher.repository

import android.content.Context
import com.videofetcher.manager.DownloadQueueManager
import com.videofetcher.manager.YoutubeDlManager
import com.videofetcher.manager.YoutubeVideoMetadata

class DownloadRepository(
    private val youtubeDlManager: YoutubeDlManager,
    private val downloadQueueManager: DownloadQueueManager
) {
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
