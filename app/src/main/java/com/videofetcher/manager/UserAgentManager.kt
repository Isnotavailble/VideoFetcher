package com.videofetcher.manager

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.File

class UserAgentManager {
    val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    val MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

    private val PREFS_NAME = "user_agent_prefs"
    private val KEY_SAVED_USER_AGENT = "saved_user_agent"

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
     * Resolves the User-Agent for WebView login.
     * Uses Mobile UA so users get a clean, touch-friendly mobile login screen.
     */
    fun getBrowserUserAgentForDomain(context: Context, domainKey: String): String {
        val cleanKey = domainKey.lowercase()
        val prefs = getPrefs(context)
        val domainSpecific = prefs.getString("user_agent_$cleanKey", null)
        if (!domainSpecific.isNullOrBlank()) return domainSpecific

        val generalSaved = prefs.getString(KEY_SAVED_USER_AGENT, null)
        return if (!generalSaved.isNullOrBlank()) generalSaved else MOBILE_USER_AGENT
    }

    /**
     * Resolves the effective User-Agent to pass to yt-dlp.
     * Facebook ALWAYS uses DESKTOP_USER_AGENT for yt-dlp scraping sessions (both authenticated and unauthenticated)
     * so yt-dlp can parse www.facebook.com desktop video structures.
     */
    fun getEffectiveUserAgentForDomain(context: Context, domainKey: String, isAuthenticated: Boolean = false): String {
        val cleanKey = domainKey.lowercase()
        if (requiresDesktopUserAgent(cleanKey)) {
            return DESKTOP_USER_AGENT
        }
        if (!isAuthenticated) {
            // Unauthenticated requests for non-Facebook domains use mobile User-Agent for public guest viewing
            return MOBILE_USER_AGENT
        }
        return getBrowserUserAgentForDomain(context, domainKey)
    }

    /**
     * Saves domain-specific User-Agent and backs it up into .useragent/ folder.
     */
    fun saveUserAgentForDomain(context: Context, domainKey: String, userAgent: String) {
        if (domainKey.isBlank()) return
        val cleanKey = domainKey.lowercase()
        val targetUA = userAgent.ifBlank { MOBILE_USER_AGENT }

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
        val targetUA = userAgent.ifBlank { MOBILE_USER_AGENT }
        try {
            val permissionManager = (context.applicationContext as com.videofetcher.VideoFetcherApp).container.permissionManager
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
     */
    fun restoreAllUserAgentsToPrivateStorage(context: Context) {
        try {
            val permissionManager = (context.applicationContext as com.videofetcher.VideoFetcherApp).container.permissionManager
            val customPath = permissionManager.getCustomDownloadFolderPath()
            val backupFolder = File(customPath, ".useragent")
            if (backupFolder.exists() && backupFolder.isDirectory) {
                backupFolder.listFiles()?.forEach { file ->
                    if (file.name.startsWith("useragent_") && file.name.endsWith(".txt")) {
                        val domainKey = file.name.removePrefix("useragent_").removeSuffix(".txt").lowercase()
                        val ua = file.readText().trim()
                        if (ua.isNotBlank() && domainKey.isNotBlank()) {
                            getPrefs(context).edit {
                                putString("user_agent_$domainKey", ua)
                                putString(KEY_SAVED_USER_AGENT, ua)
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
