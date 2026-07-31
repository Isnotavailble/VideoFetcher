package com.videofetcher.repository

import android.content.Context
import com.videofetcher.feature.files.viewmodel.DownloadedFileDetails
import com.videofetcher.manager.IntentManager
import com.videofetcher.manager.MediaMetadataManager
import com.videofetcher.manager.PauseManager
import com.videofetcher.manager.PausedDownload
import com.videofetcher.manager.StorageManager
import kotlinx.coroutines.flow.StateFlow

class FileRepository(
    private val pauseManager: PauseManager,
    private val storageManager: StorageManager,
    private val mediaMetadataManager: MediaMetadataManager,
    private val intentManager: IntentManager
) {
    val pausedDownloadsFlow: StateFlow<List<PausedDownload>> = pauseManager.pausedDownloadsFlow

    fun getAllPausedDownloads(): List<PausedDownload> = pauseManager.getAllPausedDownloads()
    fun removePausedDownload(url: String) = pauseManager.removePausedDownload(url)
    fun savePausedDownload(download: PausedDownload) = pauseManager.savePausedDownload(download)

    fun getInitialFiles(context: Context, existingMap: Map<String, DownloadedFileDetails>): List<DownloadedFileDetails> {
        return storageManager.getInitialFiles(context, existingMap)
    }

    suspend fun extractMetadata(context: Context, fileDetails: DownloadedFileDetails): Pair<String, String>? {
        return mediaMetadataManager.extractMetadata(context, fileDetails) { ctx, path, mime ->
            storageManager.getFileUri(ctx, path, mime)
        }
    }

    fun playVideo(context: Context, fileDetails: DownloadedFileDetails) {
        intentManager.playVideo(context, fileDetails) { ctx, path, mime ->
            storageManager.getFileUri(ctx, path, mime)
        }
    }

    fun shareVideo(context: Context, fileDetails: DownloadedFileDetails) {
        intentManager.shareVideo(context, fileDetails) { ctx, path, mime ->
            storageManager.getFileUri(ctx, path, mime)
        }
    }

    suspend fun deleteVideo(
        context: Context,
        fileDetails: DownloadedFileDetails
    ): Boolean = storageManager.deleteVideo(context, fileDetails, mediaMetadataManager)
}
