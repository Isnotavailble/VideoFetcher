package com.videofetcher

import android.content.Context
import com.videofetcher.manager.CookieManager
import com.videofetcher.manager.DownloadManager
import com.videofetcher.manager.PauseManager
import com.videofetcher.manager.PermissionManager
import com.videofetcher.manager.UserAgentManager
import com.videofetcher.repository.FileRepository
import com.videofetcher.repository.SettingsRepository

/**
 * Manual Dependency Injection container at the application level.
 * This holds the single instances of managers and repositories, 
 * replacing the need for static `object` singletons.
 */
class AppContainer(private val context: Context) {
    val downloadManager = DownloadManager()
    val cookieManager = CookieManager()
    val userAgentManager = UserAgentManager()
    val permissionManager = PermissionManager(context, userAgentManager)
    val pauseManager = PauseManager(context)
    
    val settingsRepository = SettingsRepository(permissionManager)
    val fileRepository = FileRepository(pauseManager)
}
