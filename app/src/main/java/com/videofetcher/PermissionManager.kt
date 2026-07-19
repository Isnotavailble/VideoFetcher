package com.videofetcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.edit
import java.io.File

/**
 * A helper class to manage persistent folder access permissions using SharedPreferences.
 * This is used for the Storage Access Framework (SAF) to allow file operations
 * even after the app has been reinstalled.
 */
class PermissionManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("permission_prefs", Context.MODE_PRIVATE)
    private val key = "video_fetcher_folder_uri"
    
    private val customUriKey = "custom_folder_uri"
    private val customPathKey = "custom_folder_path"

    fun isResolutionSelectionEnabled(): Boolean {
        return prefs.getBoolean("resolution_selection_enabled", false) // Default is Instant "Best Quality" Mode
    }

    fun setResolutionSelectionEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("resolution_selection_enabled", enabled) }
    }

    /**
     * Takes a URI granted from ACTION_OPEN_DOCUMENT_TREE, makes the permission persistent,
     * and saves its string representation to SharedPreferences.
     */
    fun saveFolderPermission(uri: Uri) {
        val contentResolver = context.contentResolver
        val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        contentResolver.takePersistableUriPermission(uri, takeFlags)

        prefs.edit {
            putString(key, uri.toString())
        }
    }

    /**
     * Retrieves the saved folder URI and verifies that the permission is still active.
     * Returns null if no URI is saved or if the permission has been revoked.
     */
    fun getSavedFolderUri(): Uri? {
        val uriString = prefs.getString(key, null) ?: return null
        val uri = Uri.parse(uriString)

        // Verify that we still have the permission.
        return if (context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }) uri else null
    }

    /**
     * Saves a custom user-selected folder for downloads, resolving its absolute path.
     * Returns true if successful (must be on primary external storage for native binaries to work).
     */
    fun saveCustomDownloadFolder(uri: Uri): Boolean {
        val absolutePath = resolveTreeUriToAbsolutePath(uri) ?: return false

        val contentResolver = context.contentResolver
        val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }

        prefs.edit {
            putString(customUriKey, uri.toString())
            putString(customPathKey, absolutePath)
        }
        return true
    }

    fun getCustomDownloadFolderUri(): Uri? {
        val uriString = prefs.getString(customUriKey, null) ?: return null
        val uri = Uri.parse(uriString)
        return if (context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }) uri else null
    }

    fun getCustomDownloadFolderPath(): String {
        val path = prefs.getString(customPathKey, null) 
            ?: File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VideoFetcher").absolutePath
            
        try {
            val dir = File(path)
            if (!dir.exists()) dir.mkdirs()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return path
    }

    fun resetToDefaultFolder() {
        prefs.edit {
            remove(customUriKey)
            remove(customPathKey)
        }
    }

    private fun resolveTreeUriToAbsolutePath(uri: Uri): String? {
        val path = uri.path ?: return null
        // Only allow primary storage to ensure YoutubeDL native binaries can write to it
        if (path.startsWith("/tree/primary:")) {
            val relativePath = path.substringAfter("/tree/primary:")
            return "${Environment.getExternalStorageDirectory().absolutePath}/$relativePath"
        }
        return null // Rejects SD cards / unsupported cloud providers gracefully
    }
}