package com.videofetcher

import android.content.Context
import com.videofetcher.manager.CookieManager
import com.videofetcher.manager.DownloadManager
import com.videofetcher.manager.PauseManager
import com.videofetcher.manager.PermissionManager
import com.videofetcher.manager.UserAgentManager
import com.videofetcher.manager.StorageManager
import com.videofetcher.manager.MediaMetadataManager
import com.videofetcher.manager.IntentManager
import com.videofetcher.repository.FileRepository
import com.videofetcher.repository.SettingsRepository
import com.videofetcher.repository.DownloadRepository

/**
 * Manual Dependency Injection container at the application level.
 * This holds the single instances of managers and repositories, 
 * replacing the need for static `object` singletons.
 */
class AppContainer(private val context: Context) {
    val appContext: Context = context.applicationContext
    val downloadManager = DownloadManager()
    val cookieManager = CookieManager()
    val userAgentManager = UserAgentManager()
    val permissionManager = PermissionManager(context, userAgentManager)
    val pauseManager = PauseManager(context)
    val storageManager = StorageManager(permissionManager)
    val mediaMetadataManager = MediaMetadataManager()
    val intentManager = IntentManager()
    
    val settingsRepository = SettingsRepository(permissionManager, cookieManager)
    val fileRepository = FileRepository(pauseManager, storageManager, mediaMetadataManager, intentManager)
    
    val youtubeDlRequestFactory = com.videofetcher.manager.YoutubeDlRequestFactory(cookieManager, userAgentManager, permissionManager)
    val youtubeDlManager = com.videofetcher.manager.YoutubeDlManager(youtubeDlRequestFactory, cookieManager)
    val downloadQueueManager = com.videofetcher.manager.DownloadQueueManager(downloadManager, pauseManager)
    val downloadRepository: DownloadRepository by lazy {
        DownloadRepository(youtubeDlManager, downloadQueueManager, downloadManager, pauseManager)
    }
}
