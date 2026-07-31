package com.videofetcher.repository

import android.content.Context
import android.net.Uri
import com.videofetcher.manager.PermissionManager
import com.videofetcher.manager.CookieManager
import com.videofetcher.manager.CookieDomainInfo
import kotlinx.coroutines.flow.StateFlow

class SettingsRepository(
    private val permissionManager: PermissionManager,
    private val cookieManager: CookieManager
) {

    fun isResolutionSelectionEnabled(): Boolean = permissionManager.isResolutionSelectionEnabled()
    fun setResolutionSelectionEnabled(enabled: Boolean) = permissionManager.setResolutionSelectionEnabled(enabled)
    val resolutionSelectionEnabledFlow: StateFlow<Boolean> = permissionManager.resolutionSelectionEnabledFlow

    fun isBypassSslEnabled(): Boolean = permissionManager.isBypassSslEnabled()
    fun setBypassSslEnabled(enabled: Boolean) = permissionManager.setBypassSslEnabled(enabled)
    val bypassSslEnabledFlow: StateFlow<Boolean> = permissionManager.bypassSslEnabledFlow

    fun isBypassExtractorEnabled(): Boolean = permissionManager.isBypassExtractorEnabled()
    fun setBypassExtractorEnabled(enabled: Boolean) = permissionManager.setBypassExtractorEnabled(enabled)
    val bypassExtractorEnabledFlow: StateFlow<Boolean> = permissionManager.bypassExtractorEnabledFlow

    fun saveFolderPermission(uri: Uri) = permissionManager.saveFolderPermission(uri)
    fun getSavedFolderUri(): Uri? = permissionManager.getSavedFolderUri()

    val cookieUpdates: StateFlow<Long> = cookieManager.cookieUpdates
    fun getAllSavedCookieDomains(context: Context): List<CookieDomainInfo> = cookieManager.getAllSavedCookieDomains(context)
    
    fun saveCustomDownloadFolder(uri: Uri): Boolean = permissionManager.saveCustomDownloadFolder(uri)
    fun getCustomDownloadFolderUri(): Uri? = permissionManager.getCustomDownloadFolderUri()
    fun getCustomDownloadFolderPath(): String = permissionManager.getCustomDownloadFolderPath()
    fun resetToDefaultFolder() = permissionManager.resetToDefaultFolder()

    fun getEffectiveUserAgent(): String = permissionManager.getEffectiveUserAgent()
    fun getUserAgentForDomain(domainKey: String): String = permissionManager.getUserAgentForDomain(domainKey)
    fun saveUserAgentForDomain(domainKey: String, userAgent: String) = permissionManager.saveUserAgentForDomain(domainKey, userAgent)
    fun getUserAgent(): String = permissionManager.getUserAgent()
    fun saveUserAgent(userAgent: String) = permissionManager.saveUserAgent(userAgent)
}
