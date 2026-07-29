package com.videofetcher.manager

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDLRequest

class YoutubeDlRequestFactory(
    private val cookieManager: CookieManager,
    private val userAgentManager: UserAgentManager,
    private val permissionManager: PermissionManager
) {
    /**
     * Creates a pre-configured YoutubeDLRequest with all platform rules, cookies, 
     * User-Agents, and IPv4 settings automatically applied.
     * 
     * @param url The raw URL to download or analyze
     * @param context Application context
     * @param isMetadataOnly Set to true if this is for X-Ray fetching (forces no-playlist, etc.)
     *                       Set to false for actual downloading
     */
    fun createRequest(url: String, context: Context, isMetadataOnly: Boolean): YoutubeDlRequestConfig {
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

        // 1. Global Speed Optimizations
        request.addOption("--no-playlist")
        request.addOption("--no-warnings")
        request.addOption("--buffer-size", "64K")
        
        if (isMetadataOnly) {
            request.addOption("--no-write-subs")
        }

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

        return YoutubeDlRequestConfig(request, hasCookies)
    }
}

/**
 * Wrapper class to return both the configured request and whether cookies were used,
 * as the caller might need to adjust retry attempts based on cookie usage.
 */
data class YoutubeDlRequestConfig(
    val request: YoutubeDLRequest,
    val hasCookies: Boolean
)
