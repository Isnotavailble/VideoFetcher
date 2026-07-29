package com.videofetcher.settings

import android.content.Context
import android.content.SharedPreferences
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class EngineUpdateManager(private val context: Context) {
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

        val checkUrls = listOf(
            "https://github.com/yt-dlp/yt-dlp/releases/latest",
            "https://ghproxy.net/https://github.com/yt-dlp/yt-dlp/releases/latest"
        )

        for (urlString in checkUrls) {
            try {
                val url = URL(urlString)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    instanceFollowRedirects = false // Catch the 302 redirect
                    setRequestProperty("User-Agent", (context.applicationContext as com.videofetcher.VideoFetcherApp).container.userAgentManager.DESKTOP_USER_AGENT)
                    connectTimeout = 8000
                    readTimeout = 8000
                }

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP || responseCode == HttpURLConnection.HTTP_MOVED_PERM || responseCode == 302 || responseCode == 301) {
                    val location = connection.getHeaderField("Location")
                    if (location != null) {
                        val tag = location.substringAfterLast("tag/").substringAfterLast("/")
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
        }
        return@withContext null
    }

    /**
     * Compares installed yt-dlp version with latest GitHub tag and returns EngineUpdateState.
     */
    suspend fun checkEngineStatus(context: Context, forceCheck: Boolean = false): com.videofetcher.EngineUpdateState = withContext(Dispatchers.IO) {
        val currentVersion = try { YoutubeDL.getInstance().version(context) } catch (e: Exception) { null }
        val latestVersion = fetchLatestVersion(forceCheck)

        if (latestVersion != null) {
            if (latestVersion != currentVersion) {
                com.videofetcher.EngineUpdateState.UpdateAvailable(latestVersion)
            } else {
                if (forceCheck) com.videofetcher.EngineUpdateState.UpToDate else com.videofetcher.EngineUpdateState.Idle
            }
        } else {
            if (forceCheck) com.videofetcher.EngineUpdateState.Error("Failed to check version. Please check network.") else com.videofetcher.EngineUpdateState.Idle
        }
    }

    /**
     * Direct binary downloader: Downloads official yt-dlp release binary directly from
     * yt-dlp/yt-dlp releases (with CDN mirror fallback for ISP blocked regions)
     * and replaces the executable binary in app internal storage.
     */
    suspend fun updateYtDlpDirectly(context: Context): Boolean = withContext(Dispatchers.IO) {
        // 1. Attempt built-in library update first
        try {
            YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
            return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Direct & CDN Mirror Fallback (Bypasses ISP blocks when VPN is OFF)
        val downloadUrls = listOf(
            "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp",
            "https://ghproxy.net/https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"
        )

        for (urlString in downloadUrls) {
            try {
                val binaryUrl = URL(urlString)
                val connection = (binaryUrl.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", (context.applicationContext as com.videofetcher.VideoFetcherApp).container.userAgentManager.DESKTOP_USER_AGENT)
                    connectTimeout = 15000
                    readTimeout = 15000
                }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val baseDir = File(context.noBackupFilesDir, "youtubedl-android")
                    if (!baseDir.exists()) baseDir.mkdirs()

                    val targetFiles = mutableListOf<File>()
                    if (baseDir.exists()) {
                        baseDir.walkTopDown().forEach { file ->
                            if (file.name == "yt-dlp") {
                                targetFiles.add(file)
                            }
                        }
                    }

                    if (targetFiles.isEmpty()) {
                        val defaultBin = File(baseDir, "usr/bin/yt-dlp")
                        defaultBin.parentFile?.mkdirs()
                        targetFiles.add(defaultBin)
                    }

                    val tempFile = File.createTempFile("yt-dlp-update", ".tmp", context.cacheDir)
                    connection.inputStream.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    if (tempFile.length() > 0) {
                        targetFiles.forEach { target ->
                            tempFile.copyTo(target, overwrite = true)
                            target.setExecutable(true, false)
                            target.setReadable(true, false)
                        }
                        tempFile.delete()
                        return@withContext true
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return@withContext false
    }

    fun markUpdateSkippedForNow() {
        lastCheckTime = System.currentTimeMillis()
    }
}
