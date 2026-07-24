package com.videofetcher.cookies

import android.content.Context
import java.io.File

object NetscapeCookieWriter {

    private const val HEADER = "# Netscape HTTP Cookie File\n# http://curl.haxx.se/rfc/cookie_spec.html\n# This is a generated file! Do not edit.\n\n"

    /**
     * Maps an input URL or domain to one of the 4 supported platform keys:
     * "youtube", "instagram", "facebook", "tiktok".
     * Returns null if the URL does not belong to a supported platform.
     */
    fun getPlatformKey(domainOrUrl: String): String? {
        val lower = domainOrUrl.lowercase()
        return when {
            lower.contains("youtube") || lower.contains("youtu.be") -> "youtube"
            lower.contains("instagram") -> "instagram"
            lower.contains("facebook") || lower.contains("fb.watch") -> "facebook"
            lower.contains("tiktok") -> "tiktok"
            else -> null
        }
    }

    /**
     * Gets the dedicated per-platform cookie file in app private directory context.filesDir.
     * Example: youtube_cookies.txt, instagram_cookies.txt, facebook_cookies.txt, tiktok_cookies.txt
     */
    fun getCookieFile(context: Context, platformKey: String): File {
        return File(context.filesDir, "${platformKey}_cookies.txt")
    }

    /**
     * Checks whether the raw cookies contain valid authenticated session tokens for the target platform.
     */
    fun hasAuthTokens(platformKey: String, rawCookieString: String): Boolean {
        if (rawCookieString.isBlank()) return false
        val lowerCookies = rawCookieString.lowercase()
        return when (platformKey) {
            "youtube" -> lowerCookies.contains("sapisid") || lowerCookies.contains("sid") || lowerCookies.contains("login_info")
            "instagram" -> lowerCookies.contains("sessionid") || lowerCookies.contains("ds_user_id")
            "facebook" -> lowerCookies.contains("c_user") || lowerCookies.contains("xs")
            "tiktok" -> lowerCookies.contains("sessionid")
            else -> false
        }
    }

    /**
     * Converts raw cookies from CookieManager into Netscape HTTP Cookie format
     * and saves them to the platform's dedicated cookie file (e.g. youtube_cookies.txt).
     */
    fun writeCookies(context: Context, domain: String, rawCookieString: String): Boolean {
        val platformKey = getPlatformKey(domain) ?: return false
        if (!hasAuthTokens(platformKey, rawCookieString)) return false

        try {
            val file = getCookieFile(context, platformKey)
            val cookieMap = mutableMapOf<String, String>()

            // 1. Read existing lines if cookie file already exists
            if (file.exists()) {
                file.readLines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                        val parts = trimmed.split("\t")
                        if (parts.size >= 7) {
                            val lineDomain = parts[0]
                            val lineName = parts[5]
                            cookieMap["$lineDomain:$lineName"] = trimmed
                        }
                    }
                }
            }

            // 2. Parse new raw cookie string (Format: name1=val1; name2=val2; ...)
            val targetDomain = if (domain.startsWith(".")) domain else ".$domain"
            val newPairs = rawCookieString.split(";")
            for (pair in newPairs) {
                val trimmedPair = pair.trim()
                if (trimmedPair.isBlank() || !trimmedPair.contains("=")) continue

                val eqIdx = trimmedPair.indexOf("=")
                val name = trimmedPair.substring(0, eqIdx).trim()
                val value = trimmedPair.substring(eqIdx + 1).trim()

                if (name.isNotBlank()) {
                    // Expiration set 10 years in the future
                    val expiration = (System.currentTimeMillis() / 1000) + 315360000L
                    val netscapeLine = "$targetDomain\tTRUE\t/\tTRUE\t$expiration\t$name\t$value"
                    cookieMap["$targetDomain:$name"] = netscapeLine
                }
            }

            // 3. Write all merged cookies to platform_cookies.txt
            file.writeText(HEADER + cookieMap.values.joinToString("\n") + "\n")
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * Checks if valid authenticated cookies exist for a specific platform domain.
     */
    fun hasCookiesForPlatform(context: Context, domain: String): Boolean {
        val platformKey = getPlatformKey(domain) ?: return false
        val file = getCookieFile(context, platformKey)
        if (!file.exists() || file.length() <= HEADER.length) return false
        return try {
            val text = file.readText().lowercase()
            hasAuthTokens(platformKey, text)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Resolves the input URL's platform and returns its dedicated cookie file ONLY IF the user
     * has authenticated cookies for that specific platform.
     * Returns null if unauthenticated or not logged in for that platform.
     */
    fun getCookieFileForUrl(context: Context, inputUrl: String): File? {
        val platformKey = getPlatformKey(inputUrl) ?: return null
        val file = getCookieFile(context, platformKey)
        if (!file.exists() || file.length() <= HEADER.length) return null

        return try {
            val text = file.readText().lowercase()
            if (hasAuthTokens(platformKey, text)) file else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Clears all per-platform cookie files.
     */
    fun clearAllCookies(context: Context): Boolean {
        var deletedAny = false
        listOf("youtube", "instagram", "facebook", "tiktok").forEach { key ->
            val file = getCookieFile(context, key)
            if (file.exists() && file.delete()) {
                deletedAny = true
            }
        }
        return deletedAny
    }
}
