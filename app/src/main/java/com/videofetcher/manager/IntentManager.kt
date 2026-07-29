package com.videofetcher.manager

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.videofetcher.feature.files.viewmodel.DownloadedFileDetails
import java.io.File

class IntentManager {

    fun playVideo(context: Context, fileDetails: DownloadedFileDetails, getUri: (Context, String, String) -> Uri?) {
        try {
            val file = File(fileDetails.path)
            val ext = file.extension.lowercase()
            val mimeType = if (ext in listOf("mp3", "m4a")) "audio/*" else "video/*"
            
            val uri = getUri(context, fileDetails.path, mimeType)
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

    fun shareVideo(context: Context, fileDetails: DownloadedFileDetails, getUri: (Context, String, String) -> Uri?) {
        try {
            val file = File(fileDetails.path)
            val ext = file.extension.lowercase()
            val mimeType = when (ext) {
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                else -> "video/mp4"
            }
            
            val uri = getUri(context, fileDetails.path, mimeType)
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
}
