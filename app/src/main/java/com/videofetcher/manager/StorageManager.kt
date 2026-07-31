package com.videofetcher.manager

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.videofetcher.feature.files.viewmodel.DownloadedFileDetails
import java.io.File

class StorageManager(private val permissionManager: PermissionManager) {

    fun getInitialFiles(context: Context, existingMap: Map<String, DownloadedFileDetails>): List<DownloadedFileDetails> {
        val customPath = getCustomDownloadFolderPath()
        val targetDir = File(customPath)
        val fileSet = mutableSetOf<String>()
        val filesList = mutableListOf<File>()

        val fileRegex = Regex(".*_vdf\\.(mp4|mp3|m4a)$", RegexOption.IGNORE_CASE)
        val mediaStoreMetadataMap = getMediaStoreMetadata(context, targetDir, fileSet, filesList, fileRegex)
        
        val directFiles = getDirectFiles(targetDir, fileSet, fileRegex)
        filesList.addAll(directFiles)
        
        val safFiles = getSafFiles(context, targetDir, fileSet, fileRegex)
        filesList.addAll(safFiles)

        val thumbCacheDir = File(context.cacheDir, "thumbnails")

        return filesList.map { file ->
            existingMap[file.absolutePath] ?: run {
                val (title, signature) = parseFileName(file.name)
                val thumbFile = File(thumbCacheDir, "${file.name}.jpg")
                val ext = file.extension.lowercase()
                val isAudio = ext in listOf("mp3", "m4a") || (file.name.contains("_vdf.", ignoreCase = true) && !file.name.endsWith("_vdf.mp4", ignoreCase = true))
                
                val (msDurMs, msSzBytes) = mediaStoreMetadataMap[file.absolutePath] ?: (0L to file.length())
                val msDur = if (msDurMs > 0) formatDuration(msDurMs) else "--:--"
                val resolvedSize = formatFileSize(if (msSzBytes > 0) msSzBytes else file.length())
                
                DownloadedFileDetails(
                    title = title,
                    path = file.absolutePath,
                    signature = signature,
                    size = resolvedSize,
                    duration = msDur,
                    thumbnailUriStr = if (thumbFile.exists()) Uri.fromFile(thumbFile).toString() else "",
                    isAudio = isAudio
                )
            }
        }
    }

    private fun getMediaStoreMetadata(context: Context, targetDir: File, fileSet: MutableSet<String>, filesList: MutableList<File>, fileRegex: Regex): Map<String, Pair<Long, Long>> {
        val mediaStoreMetadataMap = mutableMapOf<String, Pair<Long, Long>>()
        try {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DURATION
            )
            val selection = "${MediaStore.MediaColumns.DATA} LIKE ?"
            val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

            val mediaUris = listOf(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Files.getContentUri("external")
            )
            for (uri in mediaUris) {
                context.contentResolver.query(uri, projection, selection, arrayOf("%_vdf.%"), sortOrder)?.use { cursor ->
                    val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val durationCol = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)

                    while (cursor.moveToNext()) {
                        val path = if (dataCol >= 0) cursor.getString(dataCol) else null
                        if (path != null && path.startsWith(targetDir.absolutePath)) {
                            val file = File(path)
                            if (file.exists() && file.name.matches(fileRegex) && fileSet.add(path)) {
                                filesList.add(file)
                                val durationMs = if (durationCol >= 0) cursor.getLong(durationCol) else 0L
                                val msSize = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                                mediaStoreMetadataMap[path] = durationMs to msSize
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return mediaStoreMetadataMap
    }

    fun getDirectFiles(targetDir: File, fileSet: MutableSet<String>, fileRegex: Regex): List<File> {
        val directFiles = targetDir.listFiles { file ->
            file.isFile && file.name.matches(fileRegex) && fileSet.add(file.absolutePath)
        }
        return directFiles?.sortedByDescending { f -> f.lastModified() } ?: emptyList()
    }

    fun getSafFiles(context: Context, targetDir: File, fileSet: MutableSet<String>, fileRegex: Regex): List<File> {
        val filesList = mutableListOf<File>()
        val possibleUris = listOfNotNull(
            permissionManager.getSavedFolderUri(),
            permissionManager.getCustomDownloadFolderUri()
        )
        for (treeUri in possibleUris) {
            try {
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                context.contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                    val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameCol)
                        if (name != null && name.matches(fileRegex)) {
                            val file = File(targetDir, name)
                            if (fileSet.add(file.absolutePath)) {
                                filesList.add(file)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return filesList
    }

    fun getFileUri(context: Context, absolutePath: String, mimeType: String): Uri? {
        val file = File(absolutePath)
        try {
            val baseUri = if (mimeType.startsWith("audio")) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Files.FileColumns._ID)
            val selection = "${MediaStore.Files.FileColumns.DATA} = ?"
            val selectionArgs = arrayOf(absolutePath)
            
            context.contentResolver.query(baseUri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                    return ContentUris.withAppendedId(baseUri, id)
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        try {
            val possibleUris = listOfNotNull(permissionManager.getSavedFolderUri(), permissionManager.getCustomDownloadFolderUri())
            for (treeUri in possibleUris) {
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                context.contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        if (cursor.getString(nameCol) == file.name) {
                            return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idCol))
                        }
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        
        if (file.exists()) {
            try {
                return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } catch (e: Exception) { e.printStackTrace() }
        }
        return null
    }

    fun deleteFileSaf(context: Context, file: File): Boolean {
        var isDeleted = if (file.exists()) file.delete() else true
        if (!isDeleted && file.exists()) {
            val possibleUris = listOfNotNull(
                permissionManager.getSavedFolderUri(),
                permissionManager.getCustomDownloadFolderUri()
            )
            for (treeUri in possibleUris) {
                try {
                    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                    var targetDocUri: Uri? = null
                    context.contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        while (cursor.moveToNext()) {
                            if (cursor.getString(nameCol) == file.name) {
                                targetDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idCol))
                                break
                            }
                        }
                    }
                    if (targetDocUri != null) {
                        isDeleted = DocumentsContract.deleteDocument(context.contentResolver, targetDocUri!!)
                        if (isDeleted) break
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
        return isDeleted
    }

    fun deleteMediaStoreEntry(context: Context, path: String) {
        try {
            var uri: Uri? = null
            val projection = arrayOf(MediaStore.Video.Media._ID)
            val selection = "${MediaStore.Video.Media.DATA} = ?"
            val selectionArgs = arrayOf(path)
            val queryUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            context.contentResolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val id = cursor.getLong(idColumn)
                    uri = ContentUris.withAppendedId(queryUri, id)
                }
            }
            uri?.let { context.contentResolver.delete(it, null, null) }
        } catch (e: Exception) { }
    }
    
    suspend fun deleteVideo(
        context: Context,
        fileDetails: DownloadedFileDetails,
        mediaMetadataManager: MediaMetadataManager
    ): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val file = File(fileDetails.path)
        val isDeleted = deleteFileSaf(context, file)
        
        if (!isDeleted) {
            return@withContext false
        }
        
        deleteMediaStoreEntry(context, fileDetails.path)
        mediaMetadataManager.cleanupDeletedFileCache(context, file.name)
        return@withContext true
    }

    fun getCustomDownloadFolderPath(): String = permissionManager.getCustomDownloadFolderPath()

    private fun parseFileName(fileName: String): Pair<String, String> {
        val lastIndex = fileName.lastIndexOf('.')
        if (lastIndex == -1) return fileName to "(MP4)"
        val nameWithoutExt = fileName.substring(0, lastIndex)
        val vdfRegex = """^(.*?)[\s_](\([^)]+\))_vdf$""".toRegex(RegexOption.IGNORE_CASE)
        val vdfMatch = vdfRegex.find(nameWithoutExt)
        if (vdfMatch != null) return vdfMatch.groupValues[1].replace("_", " ").trim() to vdfMatch.groupValues[2]
        
        val endRegex = """^(.*?)[\s_](\([^)]+\))$""".toRegex()
        val endMatch = endRegex.find(nameWithoutExt)
        if (endMatch != null) return endMatch.groupValues[1].replace("_", " ").trim() to endMatch.groupValues[2]
        
        var cleanName = nameWithoutExt
        if (cleanName.endsWith("_vdf", ignoreCase = true)) cleanName = cleanName.substring(0, cleanName.length - 4)
        return cleanName.replace("_", " ").trim() to "()"
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = (durationMs / 1000) % 60
        val minutes = (durationMs / (1000 * 60)) % 60
        val hours = (durationMs / (1000 * 60 * 60)) % 24
        return if (hours > 0) String.format("%02d:%02d:%02d", hours, minutes, seconds) else String.format("%02d:%02d", minutes, seconds)
    }
}
