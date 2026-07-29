package com.videofetcher.repository

import android.content.Context
import com.videofetcher.manager.PauseManager
import com.videofetcher.manager.PausedDownload

class FileRepository(private val pauseManager: PauseManager) {

    fun getAllPausedDownloads(): List<PausedDownload> = pauseManager.getAllPausedDownloads()
    fun removePausedDownload(url: String) = pauseManager.removePausedDownload(url)
    fun savePausedDownload(download: PausedDownload) = pauseManager.savePausedDownload(download)

    // MediaStore querying logic will be moved here in Phase 3
}
