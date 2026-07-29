package com.videofetcher.repository

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.videofetcher.feature.files.viewmodel.DownloadedFileDetails
import com.videofetcher.manager.PauseManager
import com.videofetcher.manager.PausedDownload
import com.videofetcher.manager.PermissionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class FileRepository(
    private val pauseManager: PauseManager,
    private val permissionManager: PermissionManager
) {
    fun getAllPausedDownloads(): List<PausedDownload> = pauseManager.getAllPausedDownloads()
    fun removePausedDownload(url: String) = pauseManager.removePausedDownload(url)
    fun savePausedDownload(download: PausedDownload) = pauseManager.savePausedDownload(download)

    suspend fun fetchDownloadedFiles(
        context: Context,
        scope: CoroutineScope,
        existingFilesMap: Map<String, DownloadedFileDetails>,
        onUpdateState: (List<DownloadedFileDetails>) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val customPath = permissionManager.getCustomDownloadFolderPath()
            val targetDir = File(customPath)

            val fileSet = mutableSetOf<String>()
            val filesList = mutableListOf<File>()
            val mediaStoreMetadataMap = mutableMapOf<String, Pair<String, String>>()
            
            try {
                val projection = arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DURATION
                )
                val selection = "${MediaStore.MediaColumns.DATA} LIKE ?"
                val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

                val mediaUris = listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, MediaStore.Files.getContentUri("external"))
                for (uri in mediaUris) {
                    context.contentResolver.query(uri, projection, selection, arrayOf("%_vdf.%"), sortOrder)?.use { cursor ->
                        val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                        val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                        val durationCol = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)

                        while (cursor.moveToNext()) {
                            val path = if (dataCol >= 0) cursor.getString(dataCol) else null
                            if (path != null && path.startsWith(targetDir.absolutePath)) {
                                val file = File(path)
                                if (file.exists() && fileSet.add(path)) {
                                    filesList.add(file)
                                    val durationMs = if (durationCol >= 0) cursor.getLong(durationCol) else 0L
                                    val msSize = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                                    val sizeBytes = if (msSize > 0L) msSize else file.length()
                                    val formattedDur = if (durationMs > 0) formatDuration(durationMs) else "--:--"
                                    val formattedSz = formatFileSize(sizeBytes)
                                    mediaStoreMetadataMap[path] = formattedDur to formattedSz
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }

            val fileRegex = Regex(".*_vdf\\.[^.]+$", RegexOption.IGNORE_CASE)
            val directFiles = targetDir.listFiles { file ->
                file.isFile && file.name.matches(fileRegex) && fileSet.add(file.absolutePath)
            }
            
            if (directFiles != null) {
                filesList.addAll(directFiles.sortedByDescending { f -> f.lastModified() })
            }

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
            
            val files = filesList.toTypedArray()

            val thumbCacheDir = File(context.cacheDir, "thumbnails")
            if (!thumbCacheDir.exists()) {
                thumbCacheDir.mkdirs()
            }

            val initialList = files.map { file ->
                existingFilesMap[file.absolutePath] ?: run {
                    val (title, signature) = parseFileName(file.name)
                    val thumbFile = File(thumbCacheDir, "${file.name}.jpg")
                    val ext = file.extension.lowercase()
                    val isAudio = ext in listOf("mp3", "m4a") || (file.name.contains("_vdf.", ignoreCase = true) && !file.name.endsWith("_vdf.mp4", ignoreCase = true))
                    val (msDur, msSz) = mediaStoreMetadataMap[file.absolutePath] ?: ("--:--" to formatFileSize(file.length()))
                    val resolvedSize = if (msSz == "0 B" || msSz == "0.0 B") formatFileSize(file.length()) else msSz
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
            }.toMutableList()

            onUpdateState(initialList.toList())

            val itemsToProcess = files.indices.filter { i ->
                initialList[i].duration == "--:--" || initialList[i].thumbnailUriStr.isEmpty()
            }

            if (itemsToProcess.isNotEmpty()) {
                val semaphore = Semaphore(2)
                var processedCount = 0
                coroutineScope {
                    itemsToProcess.map { i ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                val file = files[i]
                                val thumbFile = File(thumbCacheDir, "${file.name}.jpg")
                                var updatedUriStr = initialList[i].thumbnailUriStr
                                var updatedDuration = initialList[i].duration

                                val retriever = MediaMetadataRetriever()
                                try {
                                    var fileReadable = false
                                    var attempts = 0
                                    while (!fileReadable && attempts < 2) {
                                        try {
                                            val mimeType = if (initialList[i].isAudio) "audio/*" else "video/*"
                                            val uri = getFileUri(context, file.absolutePath, mimeType)
                                            
                                            if (uri != null) {
                                                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                                    retriever.setDataSource(pfd.fileDescriptor)
                                                    fileReadable = true
                                                }
                                            }
                                            
                                            if (!fileReadable) {
                                                retriever.setDataSource(file.absolutePath)
                                                fileReadable = true
                                            }
                                        } catch (e: Exception) {
                                            attempts++
                                            if (attempts < 2) delay(50)
                                        }
                                    }

                                    if (fileReadable) {
                                        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                                        updatedDuration = formatDuration(durationMs)

                                        if (!thumbFile.exists()) {
                                            val bitmap: Bitmap? = if (initialList[i].isAudio) {
                                                val pictureBytes = retriever.embeddedPicture
                                                if (pictureBytes != null) {
                                                    BitmapFactory.decodeByteArray(pictureBytes, 0, pictureBytes.size)
                                                } else null
                                            } else {
                                                val timeUs = if (durationMs > 2000) (durationMs / 2) * 1000 else 1000000L
                                                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                            }

                                            if (bitmap != null) {
                                                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 250, 250, true)
                                                FileOutputStream(thumbFile).use { out ->
                                                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                                                }
                                                if (scaledBitmap != bitmap) scaledBitmap.recycle()
                                                scaledBitmap.recycle()
                                                updatedUriStr = Uri.fromFile(thumbFile).toString()
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                } finally {
                                    try { retriever.release() } catch (e: Exception) {}
                                }

                                synchronized(initialList) {
                                    initialList[i] = initialList[i].copy(duration = updatedDuration, thumbnailUriStr = updatedUriStr)
                                    processedCount++
                                    if (processedCount % 10 == 0 || processedCount == itemsToProcess.size) {
                                        onUpdateState(initialList.toList())
                                    }
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
            onError("Failed to scan directory: " + e.message)
        }
    }

    private fun parseFileName(fileName: String): Pair<String, String> {
        val lastIndex = fileName.lastIndexOf('.')
        if (lastIndex == -1) return fileName to "(MP4)"

        val ext = fileName.substring(lastIndex + 1)
        val nameWithoutExt = fileName.substring(0, lastIndex)

        val vdfRegex = """^(.*?)[\s_](\([^)]+\))_vdf$""".toRegex(RegexOption.IGNORE_CASE)
        val vdfMatch = vdfRegex.find(nameWithoutExt)

        if (vdfMatch != null) {
            val title = vdfMatch.groupValues[1].replace("_", " ").trim()
            val signature = vdfMatch.groupValues[2]
            return title to signature
        }

        val endRegex = """^(.*?)[\s_](\([^)]+\))$""".toRegex()
        val endMatch = endRegex.find(nameWithoutExt)

        if (endMatch != null) {
            val title = endMatch.groupValues[1].replace("_", " ").trim()
            val signature = endMatch.groupValues[2]
            return title to signature
        }

        var cleanName = nameWithoutExt
        if (cleanName.endsWith("_vdf", ignoreCase = true)) {
            cleanName = cleanName.substring(0, cleanName.length - 4)
        }

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
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
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

    fun playVideo(context: Context, fileDetails: DownloadedFileDetails) {
        try {
            val file = File(fileDetails.path)
            val ext = file.extension.lowercase()
            val mimeType = if (ext in listOf("mp3", "m4a")) "audio/*" else "video/*"
            
            val uri = getFileUri(context, fileDetails.path, mimeType)
            if (uri == null) {
                android.widget.Toast.makeText(context, "Cannot access file. Try resetting download folder.", android.widget.Toast.LENGTH_SHORT).show()
                return
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Play with..."))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun shareVideo(context: Context, fileDetails: DownloadedFileDetails) {
        try {
            val file = File(fileDetails.path)
            val ext = file.extension.lowercase()
            val mimeType = when (ext) {
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                else -> "video/mp4"
            }
            
            val uri = getFileUri(context, fileDetails.path, mimeType)
            if (uri == null) {
                android.widget.Toast.makeText(context, "Cannot access file.", android.widget.Toast.LENGTH_SHORT).show()
                return
            }

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share..."))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun deleteVideo(
        context: Context,
        fileDetails: DownloadedFileDetails,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
        onPermissionRequired: () -> Unit
    ) {
        withContext(Dispatchers.IO) {
            try {
                val file = File(fileDetails.path)
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
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    if (!isDeleted) {
                        withContext(Dispatchers.Main) { onPermissionRequired() }
                        return@withContext
                    }
                }

                if (isDeleted) {
                    try {
                        var uri: Uri? = null
                        val projection = arrayOf(MediaStore.Video.Media._ID)
                        val selection = "${MediaStore.Video.Media.DATA} = ?"
                        val selectionArgs = arrayOf(fileDetails.path)
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

                    cleanupDeletedFile(context, fileDetails)
                    withContext(Dispatchers.Main) { onSuccess() }
                } else {
                    withContext(Dispatchers.Main) { onError("Cannot delete file. Storage access denied.") }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
                withContext(Dispatchers.Main) { onError(e.message ?: "Unknown error occurred.") }
            }
        }
    }

    private fun cleanupDeletedFile(context: Context, fileDetails: DownloadedFileDetails) {
        val file = File(fileDetails.path)
        if (file.exists()) file.delete()
        
        val thumbFile = File(context.cacheDir, "thumbnails/${file.name}.png")
        if (thumbFile.exists()) thumbFile.delete()
    }
}
