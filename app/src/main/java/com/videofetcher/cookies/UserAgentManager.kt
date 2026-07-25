package com.videofetcher.cookies

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.videofetcher.PermissionManager
import java.io.File

object UserAgentManager {
    const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    const val MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

    private const val PREFS_NAME = "user_agent_prefs"
    private const val KEY_SAVED_USER_AGENT = "saved_user_agent"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Clean policy: Determines if a specific platform requires Desktop User-Agent for yt-dlp scraping.
     */
    fun requiresDesktopUserAgent(domainKey: String): Boolean {
        return domainKey.lowercase() == "facebook"
    }

    /**
     * Resolves the User-Agent for WebView login to match yt-dlp policy.
     */
    fun getBrowserUserAgentForDomain(context: Context, domainKey: String): String {
        val cleanKey = domainKey.lowercase()
        if (requiresDesktopUserAgent(cleanKey)) {
            return DESKTOP_USER_AGENT
        }
        val prefs = getPrefs(context)
        val domainSpecific = prefs.getString("user_agent_$cleanKey", null)
        if (!domainSpecific.isNullOrBlank()) return domainSpecific

        val generalSaved = prefs.getString(KEY_SAVED_USER_AGENT, null)
        return if (!generalSaved.isNullOrBlank()) generalSaved else MOBILE_USER_AGENT
    }

    /**
     * Resolves the effective User-Agent to pass to yt-dlp based on platform policy and saved preferences.
     */
    fun getEffectiveUserAgentForDomain(context: Context, domainKey: String): String {
        return getBrowserUserAgentForDomain(context, domainKey)
    }

    /**
     * Saves domain-specific User-Agent and backs it up into .useragent/ folder.
     * Enforces Desktop UA policy if required by the target platform.
     */
    fun saveUserAgentForDomain(context: Context, domainKey: String, userAgent: String) {
        if (domainKey.isBlank()) return
        val cleanKey = domainKey.lowercase()
        val targetUA = if (requiresDesktopUserAgent(cleanKey)) DESKTOP_USER_AGENT else userAgent.ifBlank { MOBILE_USER_AGENT }

        getPrefs(context).edit {
            putString("user_agent_$cleanKey", targetUA)
            putString(KEY_SAVED_USER_AGENT, targetUA)
        }
        syncUserAgentToBackup(context, cleanKey, targetUA)
    }

    /**
     * Backs up useragent_<domainKey>.txt to VideoFetcher/.useragent/ inside custom SAF directory.
     */
    fun syncUserAgentToBackup(context: Context, domainKey: String, userAgent: String) {
        val cleanKey = domainKey.lowercase()
        val targetUA = if (requiresDesktopUserAgent(cleanKey)) DESKTOP_USER_AGENT else userAgent
        try {
            val permissionManager = PermissionManager(context)
            val customPath = permissionManager.getCustomDownloadFolderPath()
            val backupFolder = File(customPath, ".useragent")
            if (!backupFolder.exists()) backupFolder.mkdirs()

            val backupFile = File(backupFolder, "useragent_$cleanKey.txt")
            backupFile.writeText(targetUA)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Restores all useragent_<domainKey>.txt backups from .useragent/ into SharedPreferences.
     * Overwrites platform policy overrides (e.g. Facebook Desktop UA) automatically.
     */
    fun restoreAllUserAgentsToPrivateStorage(context: Context) {
        try {
            val permissionManager = PermissionManager(context)
            val customPath = permissionManager.getCustomDownloadFolderPath()
            val backupFolder = File(customPath, ".useragent")
            if (backupFolder.exists() && backupFolder.isDirectory) {
                backupFolder.listFiles()?.forEach { file ->
                    if (file.name.startsWith("useragent_") && file.name.endsWith(".txt")) {
                        val domainKey = file.name.removePrefix("useragent_").removeSuffix(".txt").lowercase()
                        val ua = if (requiresDesktopUserAgent(domainKey)) DESKTOP_USER_AGENT else file.readText().trim()
                        if (ua.isNotBlank() && domainKey.isNotBlank()) {
                            getPrefs(context).edit {
                                putString("user_agent_$domainKey", ua)
                                putString(KEY_SAVED_USER_AGENT, ua)
                            }
                            if (requiresDesktopUserAgent(domainKey)) {
                                file.writeText(DESKTOP_USER_AGENT)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
