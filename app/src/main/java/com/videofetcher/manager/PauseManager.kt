package com.videofetcher.manager

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// 1. The Data Model
data class PausedDownload(
    val url: String,
    val title: String,
    val quality: String,
    val progress: Float,
    val thumbnailUrl: String = ""
)

// 2. The Manager to manage SharedPreferences safely
class PauseManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("paused_downloads_prefs", Context.MODE_PRIVATE)

    private val _pausedDownloadsFlow = MutableStateFlow(getAllPausedDownloads())
    val pausedDownloadsFlow: StateFlow<List<PausedDownload>> = _pausedDownloadsFlow.asStateFlow()

    // Save or Update a paused download
    fun savePausedDownload(download: PausedDownload) {
        val json = JSONObject().apply {
            put("title", download.title)
            put("quality", download.quality)
            put("progress", download.progress.toDouble()) // JSON prefers Doubles over Floats
            put("thumbnailUrl", download.thumbnailUrl)
        }
        
        // The URL is the KEY, the JSON String is the VALUE
        prefs.edit().putString(download.url, json.toString()).apply()
        _pausedDownloadsFlow.value = getAllPausedDownloads()
    }

    // Get a specific paused download by its URL
    fun getPausedDownload(url: String): PausedDownload? {
        val jsonString = prefs.getString(url, null) ?: return null
        return try {
            val json = JSONObject(jsonString)
            PausedDownload(
                url = url,
                title = json.optString("title", "Video"),
                quality = json.optString("quality", "1080p"),
                progress = json.optDouble("progress", 0.0).toFloat(),
                thumbnailUrl = json.optString("thumbnailUrl", "")
            )
        } catch (e: Exception) {
            null
        }
    }

    // Get a list of ALL paused downloads (great for displaying in your UI!)
    fun getAllPausedDownloads(): List<PausedDownload> {
        return prefs.all.mapNotNull { (url, value) ->
            if (value is String) getPausedDownload(url) else null
        }
    }

    // Remove a download (call this when the user clicks Resume or clicks Cancel)
    fun removePausedDownload(url: String) {
        prefs.edit().remove(url).apply()
        _pausedDownloadsFlow.value = getAllPausedDownloads()
    }
}