package com.videofetcher
import com.videofetcher.manager.DownloadManager
import com.videofetcher.manager.PermissionManager

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

class VideoFetcherApp : Application() {
    private val appScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        DownloadManager.updateEngineState(EngineState.Initializing)

        // Pre-warm YoutubeDL and FFmpeg concurrently in background threads on app startup
        appScope.launch {
            try {
                val permissionManager = PermissionManager(applicationContext)
                val tempBaseDir = java.io.File(permissionManager.getCustomDownloadFolderPath(), ".vdf_temp")
                if (tempBaseDir.exists()) {
                    val now = System.currentTimeMillis()
                    val thresholdMs = 24 * 60 * 60 * 1000L // 24 hours
                    tempBaseDir.listFiles()?.forEach { file ->
                        if (now - file.lastModified() > thresholdMs) {
                            file.deleteRecursively()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            try {
                val ytJob = async { YoutubeDL.getInstance().init(applicationContext) }
                val ffmpegJob = async { FFmpeg.getInstance().init(applicationContext) }
                awaitAll(ytJob, ffmpegJob)
                DownloadManager.updateEngineState(EngineState.Idle)
            } catch (e: Exception) {
                e.printStackTrace()
                DownloadManager.updateEngineState(EngineState.Error("Engine boot failed: ${e.message}"))
            }
        }
    }
}
