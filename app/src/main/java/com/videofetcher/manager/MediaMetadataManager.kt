package com.videofetcher.manager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.videofetcher.feature.files.viewmodel.DownloadedFileDetails
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream

class MediaMetadataManager {
    suspend fun extractMetadata(
        context: Context,
        fileDetails: DownloadedFileDetails,
        getFileUri: (Context, String, String) -> Uri?
    ): Pair<String, String>? {
        val file = File(fileDetails.path)
        val thumbCacheDir = File(context.cacheDir, "thumbnails")
        if (!thumbCacheDir.exists()) thumbCacheDir.mkdirs()
        
        val thumbFile = File(thumbCacheDir, "${file.name}.jpg")
        var updatedDuration = "--:--"
        var updatedUriStr = if (thumbFile.exists()) Uri.fromFile(thumbFile).toString() else ""
        
        val retriever = MediaMetadataRetriever()
        var fileReadable = false
        var attempts = 0
        
        try {
            while (!fileReadable && attempts < 2) {
                try {
                    val mimeType = if (fileDetails.isAudio) "audio/*" else "video/*"
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
                    val bitmap: Bitmap? = if (fileDetails.isAudio) {
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
            } else {
                return null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }
        
        return updatedDuration to updatedUriStr
    }

    fun cleanupDeletedFileCache(context: Context, fileName: String) {
        val thumbFileJpg = File(context.cacheDir, "thumbnails/$fileName.jpg")
        val thumbFilePng = File(context.cacheDir, "thumbnails/$fileName.png")
        if (thumbFileJpg.exists()) thumbFileJpg.delete()
        if (thumbFilePng.exists()) thumbFilePng.delete()
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
}
