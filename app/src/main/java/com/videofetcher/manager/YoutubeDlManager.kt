package com.videofetcher.manager

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

class YoutubeDlManager(
    private val cookieManager: CookieManager,
    private val userAgentManager: UserAgentManager,
    private val permissionManager: PermissionManager
) {
    suspend fun fetchVideoMetadata(url: String, context: Context): YoutubeVideoMetadata {
        val cleanUrl = try {
            android.net.Uri.parse(url).buildUpon().clearQuery().build().toString()
        } catch (e: Exception) {
            url
        }
        val request = YoutubeDLRequest(cleanUrl)

        val domainKey = cookieManager.getDomainKey(cleanUrl)
        val platformCookieFile = cookieManager.getCookieFileForUrl(context, cleanUrl)
        val hasCookies = platformCookieFile != null

        if (platformCookieFile != null) {
            request.addOption("--cookies", platformCookieFile.absolutePath)
            request.addOption("--retries", "2")
            request.addOption("--fragment-retries", "1")
        }

        val effectiveUserAgent = userAgentManager.getEffectiveUserAgentForDomain(
            context,
            domainKey,
            isAuthenticated = hasCookies
        )
        request.addOption("--user-agent", effectiveUserAgent)

        // 1. Global Speed Optimizations & Aggressive Pruning
        request.addOption("--no-playlist")
        request.addOption("--no-warnings")
        request.addOption("--buffer-size", "64K")
        request.addOption("--no-write-subs")

        // 2. Force IPv4 for non-Instagram requests
        if (domainKey.lowercase() != "instagram") {
            request.addOption("--force-ipv4")
        }

        // 3. User Preference Speed Toggles
        if (permissionManager.isBypassSslEnabled()) {
            request.addOption("--no-check-certificates")
        }
        if (permissionManager.isBypassExtractorEnabled() && domainKey.lowercase() !in listOf("youtube", "facebook", "instagram", "tiktok")) {
            request.addOption("--force-generic-extractor")
        }

        var info: com.yausername.youtubedl_android.mapper.VideoInfo? = null
        var attempt = 0
        val maxAttempts = if (platformCookieFile != null) 2 else 1

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
