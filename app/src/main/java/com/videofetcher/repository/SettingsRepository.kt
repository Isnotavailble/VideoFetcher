package com.videofetcher.repository

import android.content.Context
import android.net.Uri
import com.videofetcher.manager.PermissionManager

class SettingsRepository(private val permissionManager: PermissionManager) {

    fun isResolutionSelectionEnabled(): Boolean = permissionManager.isResolutionSelectionEnabled()
    fun setResolutionSelectionEnabled(enabled: Boolean) = permissionManager.setResolutionSelectionEnabled(enabled)

    fun isBypassSslEnabled(): Boolean = permissionManager.isBypassSslEnabled()
    fun setBypassSslEnabled(enabled: Boolean) = permissionManager.setBypassSslEnabled(enabled)

    fun isBypassExtractorEnabled(): Boolean = permissionManager.isBypassExtractorEnabled()
    fun setBypassExtractorEnabled(enabled: Boolean) = permissionManager.setBypassExtractorEnabled(enabled)

    fun saveFolderPermission(uri: Uri) = permissionManager.saveFolderPermission(uri)
    fun getSavedFolderUri(): Uri? = permissionManager.getSavedFolderUri()
    
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
