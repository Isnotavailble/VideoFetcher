package com.videofetcher.manager

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

class YoutubeDlManager(
    private val requestFactory: YoutubeDlRequestFactory,
    private val cookieManager: CookieManager
) {
    suspend fun fetchVideoMetadata(url: String, context: Context): YoutubeVideoMetadata {
        val config = requestFactory.createRequest(url, context, isMetadataOnly = true)
        val request = config.request
        val hasCookies = config.hasCookies

        var info: com.yausername.youtubedl_android.mapper.VideoInfo? = null
        var attempt = 0
        val maxAttempts = if (hasCookies) 2 else 1

        while (attempt < maxAttempts) {
            attempt++
            try {
                info = YoutubeDL.getInstance().getInfo(request)
                break
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (maxAttempts > 1 && cookieManager.isAuthException(e.message) && attempt < maxAttempts) {
                    delay(1000)
                } else if (attempt >= maxAttempts) {
                    throw e
                }
            }
        }

        if (info == null) {
            throw Exception("Failed to fetch media metadata.")
        }

        // Resolution Bucketing (handles vertical videos securely)
        val rawMaxHeight = info.formats
            ?.filter { it.height > 0 && it.vcodec != "none" }
            ?.maxOfOrNull { Math.min(it.width.coerceAtLeast(0), it.height) } ?: 0

        val formats = mutableListOf<String>()
        when {
            rawMaxHeight >= 2160 -> formats.addAll(listOf("4K", "2K", "1080p", "720p", "480p"))
            rawMaxHeight >= 1440 -> formats.addAll(listOf("2K", "1080p", "720p", "480p"))
            rawMaxHeight >= 1000 -> formats.addAll(listOf("1080p", "720p", "480p", "360p"))
            rawMaxHeight >= 720 -> formats.addAll(listOf("720p", "480p", "360p"))
            rawMaxHeight >= 480 -> formats.addAll(listOf("480p", "360p"))
            rawMaxHeight > 0 -> formats.add("360p")
            else -> formats.add("Best Quality")
        }
        formats.add("Best Quality (M4A)")
        formats.add("Audio (MP3) - High Quality")
        formats.add("Audio (MP3) - Standard")
        formats.add("Audio (MP3) - Fast")

        val durationStr = formatDuration((info.duration * 1000).toLong())

        return YoutubeVideoMetadata(
            title = info.title ?: "Unknown Title",
            durationStr = durationStr,
            thumbnailUrl = info.thumbnail ?: "",
            formats = formats
        )
    }

    private fun formatDuration(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % TimeUnit.HOURS.toMinutes(1)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % TimeUnit.MINUTES.toSeconds(1)
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
}
