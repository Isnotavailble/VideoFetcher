package com.videofetcher.cookies

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.webkit.CookieManager
import com.videofetcher.PermissionManager
import java.io.File
import java.net.URI

data class CookieDomainInfo(
    val domainKey: String,
    val displayName: String,
    val file: File,
    val lastModified: Long,
    val cookieCount: Int
)

object NetscapeCookieWriter {

    private const val HEADER = "# Netscape HTTP Cookie File\n# http://curl.haxx.se/rfc/cookie_spec.html\n# This is a generated file! Do not edit.\n\n"

    /**
     * Dynamically extracts a clean primary domain key for ANY website URL or domain string.
     */
    fun getDomainKey(domainOrUrl: String): String {
        val lower = domainOrUrl.trim().lowercase()
        return when {
            lower.contains("youtube") || lower.contains("youtu.be") -> "youtube"
            lower.contains("instagram") || lower.contains("instagr.am") -> "instagram"
            lower.contains("facebook") || lower.contains("fb.watch") || lower.contains("fb.com") -> "facebook"
            lower.contains("tiktok") -> "tiktok"
            lower.contains("twitter") || lower.contains("x.com") -> "twitter"
            lower.contains("bilibili") -> "bilibili"
            lower.contains("vimeo") -> "vimeo"
            lower.contains("reddit") -> "reddit"
            lower.contains("dailymotion") -> "dailymotion"
            else -> extractGenericDomainKey(lower)
        }
    }

    private fun extractGenericDomainKey(input: String): String {
        return try {
            val uriStr = if (!input.startsWith("http://") && !input.startsWith("https://")) {
                "https://$input"
            } else input
            val host = URI(uriStr).host ?: input
            val cleanHost = host.removePrefix("www.").removePrefix("m.").removePrefix("mobile.").removePrefix("touch.")
            val parts = cleanHost.split(".")
            if (parts.size >= 2) {
                val candidate = parts[parts.size - 2]
                if (candidate.length > 2) candidate else parts[0]
            } else {
                cleanHost.replace(Regex("[^a-z0-9]"), "")
            }
        } catch (e: Exception) {
            input.replace(Regex("[^a-z0-9]"), "").take(20).ifEmpty { "unknown" }
        }
    }

    /**
     * Resolves the persistent backup directory (.cookies inside VideoFetcher download folder).
     */
    fun getPersistentBackupDir(context: Context): File {
        val permissionManager = PermissionManager(context)
        val customPath = permissionManager.getCustomDownloadFolderPath()
        val backupDir = File(customPath, ".cookies")
        try {
            if (!backupDir.exists()) backupDir.mkdirs()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return backupDir
    }

    /**
     * Resolves the persistent User-Agent backup directory (.useragent inside VideoFetcher download folder).
     */
    fun getPersistentUserAgentDir(context: Context): File {
        val permissionManager = PermissionManager(context)
        val customPath = permissionManager.getCustomDownloadFolderPath()
        val backupDir = File(customPath, ".useragent")
        try {
            if (!backupDir.exists()) backupDir.mkdirs()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return backupDir
    }

    /**
     * Saves a domain-specific User-Agent into the persistent backup folder .useragent/useragent_<domainKey>.txt
     */
    fun syncUserAgentToBackup(context: Context, domainKey: String, userAgent: String) {
        if (userAgent.isBlank() || domainKey.isBlank()) return
        try {
            val backupDir = getPersistentUserAgentDir(context)
            val backupFile = File(backupDir, "useragent_${domainKey}.txt")
            backupFile.writeText(userAgent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Pre-injects a saved Netscape cookie file into Android's native WebView CookieManager.
     */
    fun injectCookiesIntoCookieManager(context: Context, domainOrUrl: String) {
        val domainKey = getDomainKey(domainOrUrl)
        val file = getCookieFile(context, domainKey)
        if (!file.exists() || file.length() <= HEADER.length) return

        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            file.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                    val parts = trimmed.split("\t")
                    if (parts.size >= 7) {
                        val domain = parts[0].trim()
                        val path = parts[2].trim().ifEmpty { "/" }
                        val name = parts[5].trim()
                        val value = parts[6].trim()

                        if (name.isNotBlank()) {
                            val cleanDomain = if (domain.startsWith(".")) domain else ".$domain"
                            val targetUrl = "https://${cleanDomain.removePrefix(".")}"
                            val cookieString = "$name=$value; Domain=$cleanDomain; Path=$path; Secure; SameSite=None"
                            cookieManager.setCookie(targetUrl, cookieString)
                        }
                    }
                }
            }
            cookieManager.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Restores surviving cookie files and User-Agent files from SAF Tree Uri if direct File access was blocked after app reinstall.
     */
    fun restoreFromSafTreeUri(context: Context, treeUri: Uri): Boolean {
        var restoredAny = false
        val permissionManager = PermissionManager(context)
        val contentResolver = context.contentResolver
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)

        // 1. Restore .cookies
        try {
            val cookiesDocId = "$treeDocumentId/.cookies"
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, cookiesDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE
            )

            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)

                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idCol)
                    val name = cursor.getString(nameCol)
                    val size = cursor.getLong(sizeCol)

                    if (!name.isNullOrBlank() && name.endsWith("_cookies.txt") && size > HEADER.length) {
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        val targetFile = File(context.filesDir, name)
                        contentResolver.openInputStream(fileUri)?.use { inputStream ->
                            targetFile.outputStream().use { outputStream ->
                                inputStream.copyTo(outputStream)
                            }
                            restoredAny = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Restore .useragent
        try {
            val userAgentDocId = "$treeDocumentId/.useragent"
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, userAgentDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )

            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idCol)
                    val name = cursor.getString(nameCol)

                    if (!name.isNullOrBlank() && name.startsWith("useragent_") && name.endsWith(".txt")) {
                        val domainKey = name.removePrefix("useragent_").removeSuffix(".txt")
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        contentResolver.openInputStream(fileUri)?.use { inputStream ->
                            val userAgentStr = inputStream.bufferedReader().use { it.readText().trim() }
                            if (userAgentStr.isNotBlank()) {
                                permissionManager.saveUserAgentForDomain(domainKey, userAgentStr)
                                restoredAny = true
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return restoredAny
    }

    /**
     * Restores surviving cookie files and User-Agent files into context.filesDir / SharedPreferences after app reinstall.
     */
    fun restoreAllBackupsToPrivateStorage(context: Context) {
        val permissionManager = PermissionManager(context)

        // 1. Direct File access for .cookies
        try {
            val backupDir = getPersistentBackupDir(context)
            if (backupDir.exists() && backupDir.isDirectory) {
                val files = backupDir.listFiles()
                if (files != null) {
                    for (file in files) {
                        if (file.isFile && file.name.endsWith("_cookies.txt") && file.length() > HEADER.length) {
                            val targetFile = File(context.filesDir, file.name)
                            if (!targetFile.exists() || targetFile.length() < file.length()) {
                                file.copyTo(targetFile, overwrite = true)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Direct File access for .useragent
        try {
            val uaDir = getPersistentUserAgentDir(context)
            if (uaDir.exists() && uaDir.isDirectory) {
                val files = uaDir.listFiles()
                if (files != null) {
                    for (file in files) {
                        if (file.isFile && file.name.startsWith("useragent_") && file.name.endsWith(".txt")) {
                            val domainKey = file.name.removePrefix("useragent_").removeSuffix(".txt")
                            val userAgentStr = file.readText().trim()
                            if (userAgentStr.isNotBlank()) {
                                permissionManager.saveUserAgentForDomain(domainKey, userAgentStr)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. Delegate User-Agent restoration to UserAgentManager
        try {
            UserAgentManager.restoreAllUserAgentsToPrivateStorage(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Gets the dedicated cookie file in app private directory context.filesDir.
     */
    fun getCookieFile(context: Context, domainKey: String): File {
        val privateFile = File(context.filesDir, "${domainKey}_cookies.txt")
        if (!privateFile.exists() || privateFile.length() <= HEADER.length) {
            restoreAllBackupsToPrivateStorage(context)
        }
        return privateFile
    }

    private fun syncToBackup(context: Context, domainKey: String, sourcePrivateFile: File) {
        try {
            val backupDir = getPersistentBackupDir(context)
            val backupFile = File(backupDir, "${domainKey}_cookies.txt")
            if (sourcePrivateFile.exists() && sourcePrivateFile.length() > 0) {
                sourcePrivateFile.copyTo(backupFile, overwrite = true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Validates whether a raw cookie string contains non-empty session cookies.
     */
    fun hasAuthTokens(domainKey: String, rawCookieString: String): Boolean {
        if (rawCookieString.isBlank()) return false
        val lower = rawCookieString.lowercase()
        return when (domainKey) {
            "youtube" -> lower.contains("sapisid") || lower.contains("sid") || lower.contains("login_info")
            "instagram" -> lower.contains("sessionid") || lower.contains("ds_user_id")
            "facebook" -> lower.contains("c_user") || lower.contains("xs")
            "tiktok" -> lower.contains("sessionid")
            "twitter" -> lower.contains("auth_token") || lower.contains("ct0")
            else -> lower.lines().any { line ->
                val trimmed = line.trim()
                trimmed.isNotBlank() && !trimmed.startsWith("#") && trimmed.split("\t").size >= 6
            } || rawCookieString.contains("=")
        }
    }

    /**
     * Converts raw cookies from CookieManager into Netscape HTTP Cookie format
     * and saves to domain_cookies.txt (both private storage and .cookies backup).
     */
    fun writeCookies(context: Context, domainOrUrl: String, rawCookieString: String): Boolean {
        val domainKey = getDomainKey(domainOrUrl)
        if (!hasAuthTokens(domainKey, rawCookieString)) return false

        try {
            val file = File(context.filesDir, "${domainKey}_cookies.txt")
            val cookieMap = mutableMapOf<String, String>()

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

            val targetDomain = when (domainKey) {
                "youtube" -> ".youtube.com"
                "facebook" -> ".facebook.com"
                "instagram" -> ".instagram.com"
                "tiktok" -> ".tiktok.com"
                "twitter" -> ".twitter.com"
                else -> if (domainOrUrl.contains(".")) {
                    val host = domainOrUrl.substringAfter("://").substringBefore("/").removePrefix("www.").removePrefix("m.").removePrefix("touch.")
                    if (host.startsWith(".")) host else ".$host"
                } else ".${domainKey}.com"
            }

            val newPairs = rawCookieString.split(";")
            for (pair in newPairs) {
                val trimmedPair = pair.trim()
                if (trimmedPair.isBlank() || !trimmedPair.contains("=")) continue

                val eqIdx = trimmedPair.indexOf("=")
                val name = trimmedPair.substring(0, eqIdx).trim()
                val value = trimmedPair.substring(eqIdx + 1).trim()

                if (name.isNotBlank()) {
                    val expiration = (System.currentTimeMillis() / 1000) + 315360000L
                    val netscapeLine = "$targetDomain\tTRUE\t/\tTRUE\t$expiration\t$name\t$value"
                    cookieMap["$targetDomain:$name"] = netscapeLine
                }
            }

            file.writeText(HEADER + cookieMap.values.joinToString("\n") + "\n")
            syncToBackup(context, domainKey, file)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * Resolves the input URL's domain key and returns its cookie file if authenticated session tokens exist.
     */
    fun getCookieFileForUrl(context: Context, inputUrl: String): File? {
        val domainKey = getDomainKey(inputUrl)
        val file = getCookieFile(context, domainKey)
        if (!file.exists() || file.length() <= HEADER.length) return null

        return try {
            val text = file.readText().lowercase()
            if (hasAuthTokens(domainKey, text)) file else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Lists all saved cookie domain files from internal app private storage.
     */
    fun getAllSavedCookieDomains(context: Context): List<CookieDomainInfo> {
        val result = mutableMapOf<String, CookieDomainInfo>()

        if (context.filesDir.exists() && context.filesDir.isDirectory) {
            val files = context.filesDir.listFiles()
            if (files != null) {
                for (file in files) {
                    if (file.isFile && file.name.endsWith("_cookies.txt") && file.length() > HEADER.length) {
                        val key = file.name.removeSuffix("_cookies.txt")
                        val lines = try { file.readLines().filter { it.isNotBlank() && !it.startsWith("#") } } catch (e: Exception) { emptyList() }
                        if (lines.isNotEmpty()) {
                            val displayName = key.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } + ".com"
                            result[key] = CookieDomainInfo(
                                domainKey = key,
                                displayName = displayName,
                                file = file,
                                lastModified = file.lastModified(),
                                cookieCount = lines.size
                            )
                        }
                    }
                }
            }
        }

        return result.values.sortedByDescending { it.lastModified }
    }

    /**
     * Deletes a cookie file from app private internal storage ONLY.
     */
    fun deleteCookieFile(context: Context, domainKey: String): Boolean {
        return try {
            val privateFile = File(context.filesDir, "${domainKey}_cookies.txt")
            if (privateFile.exists()) privateFile.delete() else false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Clears all saved cookie files from app private internal storage.
     */
    fun clearAllCookies(context: Context): Boolean {
        var deletedAny = false
        getAllSavedCookieDomains(context).forEach { info ->
            if (deleteCookieFile(context, info.domainKey)) {
                deletedAny = true
            }
        }
        return deletedAny
    }
}
