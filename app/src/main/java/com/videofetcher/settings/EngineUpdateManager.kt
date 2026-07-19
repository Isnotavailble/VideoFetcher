package com.videofetcher.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class EngineUpdateManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("engine_update_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LAST_CHECK_TIME = "last_check_time"
        private const val KEY_LATEST_VERSION = "latest_available_version"
        private const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L // 12 hours
    }

    var latestAvailableVersion: String?
        get() = prefs.getString(KEY_LATEST_VERSION, null)
        private set(value) = prefs.edit().putString(KEY_LATEST_VERSION, value).apply()

    var lastCheckTime: Long
        get() = prefs.getLong(KEY_LAST_CHECK_TIME, 0L)
        private set(value) = prefs.edit().putLong(KEY_LAST_CHECK_TIME, value).apply()

    fun shouldCheckForUpdates(): Boolean {
        return System.currentTimeMillis() - lastCheckTime > CHECK_INTERVAL_MS
    }

    /**
     * Performs a HEAD request to the yt-dlp latest release URL.
     * Extracts the version tag from the Location redirect header.
     * Returns the version string if successful, null otherwise.
     */
    suspend fun fetchLatestVersion(forceCheck: Boolean = false): String? = withContext(Dispatchers.IO) {
        if (!forceCheck && !shouldCheckForUpdates()) {
            return@withContext latestAvailableVersion
        }

        try {
            val url = URL("https://github.com/yt-dlp/yt-dlp/releases/latest")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.instanceFollowRedirects = false // We want to catch the 302 redirect

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP || responseCode == HttpURLConnection.HTTP_MOVED_PERM || responseCode == 302) {
                val location = connection.getHeaderField("Location")
                if (location != null) {
                    // location is usually like "https://github.com/yt-dlp/yt-dlp/releases/tag/2024.04.09"
                    val tag = location.substringAfterLast("tag/")
                    if (tag.isNotEmpty()) {
                        latestAvailableVersion = tag
                        lastCheckTime = System.currentTimeMillis()
                        return@withContext tag
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }

    fun markUpdateSkippedForNow() {
        // If the user clicks "Later", we just ensure the check time is updated so it doesn't prompt again for 12 hours
        lastCheckTime = System.currentTimeMillis()
    }
}
