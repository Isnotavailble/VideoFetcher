package com.videofetcher

import android.content.Context
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

sealed class VideoInfoState {
    object Idle : VideoInfoState()
    object Fetching : VideoInfoState()
    data class Success(
        val title: String,
        val duration: String,
        val thumbnailUrl: String,
        val formats: List<String>
    ) : VideoInfoState()
    data class Error(val message: String) : VideoInfoState()
}

sealed class EngineUpdateState {
    object Idle : EngineUpdateState()
    object Checking : EngineUpdateState()
    object UpToDate : EngineUpdateState()
    data class UpdateAvailable(val version: String) : EngineUpdateState()
    object Updating : EngineUpdateState()
    object Success : EngineUpdateState()
    data class Error(val message: String) : EngineUpdateState()
}

class DownloaderViewModel : ViewModel() {
    val engineState: StateFlow<EngineState> = DownloadManager.engineState
    val activeDownloads: StateFlow<Map<String, DownloadState>> = DownloadManager.activeDownloads

    private val _filesListState = MutableStateFlow<FilesListState>(FilesListState.Idle)
    val filesListState: StateFlow<FilesListState> = _filesListState.asStateFlow()

    private val _pausedDownloads = MutableStateFlow<List<PausedDownload>>(emptyList())
    val pausedDownloads: StateFlow<List<PausedDownload>> = _pausedDownloads.asStateFlow()

    private val _videoInfoState = MutableStateFlow<VideoInfoState>(VideoInfoState.Idle)
    val videoInfoState: StateFlow<VideoInfoState> = _videoInfoState.asStateFlow()

    private val _engineUpdateState = MutableStateFlow<EngineUpdateState>(EngineUpdateState.Idle)
    val engineUpdateState: StateFlow<EngineUpdateState> = _engineUpdateState.asStateFlow()

    private val baseDirName = "VideoFetcher"
    private var fetchJob: Job? = null
    private var analyzeJob: Job? = null

    fun initializeEngine(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(context)
                FFmpeg.getInstance().init(context)
                if (DownloadManager.engineState.value is EngineState.Initializing) {
                    DownloadManager.updateEngineState(EngineState.Idle)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                DownloadManager.updateEngineState(EngineState.Error("Engine failed to boot: ${e.message}"))
            }
        }
    }

    fun checkForEngineUpdate(context: Context, forceCheck: Boolean = false) {
        viewModelScope.launch {
            if (forceCheck) {
                _engineUpdateState.value = EngineUpdateState.Checking
            }
            
            val manager = com.videofetcher.settings.EngineUpdateManager(context)
            val currentVersion = YoutubeDL.getInstance().version(context)
            val latestVersion = manager.fetchLatestVersion(forceCheck)

            if (latestVersion != null) {
                if (latestVersion != currentVersion) {
                    _engineUpdateState.value = EngineUpdateState.UpdateAvailable(latestVersion)
                } else {
                    if (forceCheck) {
                        _engineUpdateState.value = EngineUpdateState.UpToDate
                    } else {
                        _engineUpdateState.value = EngineUpdateState.Idle
                    }
                }
            } else {
                if (forceCheck) {
                    _engineUpdateState.value = EngineUpdateState.Error("Failed to check version. Please check network.")
                } else {
                    _engineUpdateState.value = EngineUpdateState.Idle
                }
            }
        }
    }

    fun updateEngine(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _engineUpdateState.value = EngineUpdateState.Updating
            val manager = com.videofetcher.settings.EngineUpdateManager(context)
            val success = manager.updateYtDlpDirectly(context)
            if (success) {
                _engineUpdateState.value = EngineUpdateState.Success
                try {
                    YoutubeDL.getInstance().init(context)
                    FFmpeg.getInstance().init(context)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(2000)
                _engineUpdateState.value = EngineUpdateState.Idle
            } else {
                _engineUpdateState.value = EngineUpdateState.Error("Failed to update engine. Check network connection.")
            }
        }
    }

    fun dismissUpdatePrompt(context: Context) {
        com.videofetcher.settings.EngineUpdateManager(context).markUpdateSkippedForNow()
        _engineUpdateState.value = EngineUpdateState.Idle
    }

    fun analyzeUrl(url: String, context: Context? = null) {
        analyzeJob?.cancel()
        if (url.isBlank()) {
            _videoInfoState.value = VideoInfoState.Idle
            return
        }

        // Basic URL format validation before hitting the engine
        val urlRegex = """^(https?|ftp)://[^\s/$.?#].[^\s]*$""".toRegex(RegexOption.IGNORE_CASE)
        if (!urlRegex.matches(url)) {
            _videoInfoState.value = VideoInfoState.Error("Invalid URL format")
            return
        }

        analyzeJob = viewModelScope.launch(Dispatchers.IO) {
            _videoInfoState.value = VideoInfoState.Fetching
            try {
                val targetUrl = resolveCanonicalUrl(url)
                val request = YoutubeDLRequest(targetUrl)

                if (context != null) {
                    val domainKey = com.videofetcher.cookies.NetscapeCookieWriter.getDomainKey(targetUrl)
                    val platformCookieFile = com.videofetcher.cookies.NetscapeCookieWriter.getCookieFileForUrl(context, targetUrl)

                    if (platformCookieFile != null) {
                        request.addOption("--cookies", platformCookieFile.absolutePath)
                        val effectiveUserAgent = com.videofetcher.cookies.UserAgentManager.getEffectiveUserAgentForDomain(context, domainKey)
                        request.addOption("--user-agent", effectiveUserAgent)
                        request.addOption("--retries", "3")
                        request.addOption("--fragment-retries", "5")
                    }
                }
                
                // 1. Aggressive Pruning (Ignore comments, subtitles, and playlists)
                request.addOption("--no-playlist")
                request.addOption("--no-write-subs")
                request.addOption("--compat-options", "no-youtube-unavailable-videos")
                
                // 2. Force IPv4 to prevent silent 10-second timeout hangs
                request.addOption("--force-ipv4")
                
                val info = YoutubeDL.getInstance().getInfo(request)
                
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
                
                withContext(Dispatchers.Main) {
                    _videoInfoState.value = VideoInfoState.Success(
                        title = info.title ?: "Unknown Title",
                        duration = durationStr,
                        thumbnailUrl = info.thumbnail ?: "",
                        formats = formats
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                withContext(Dispatchers.Main) { _videoInfoState.value = VideoInfoState.Error("Couldn't fetch video info. Is the link correct?") }
            }
        }
    }

    fun clearVideoInfo() {
        analyzeJob?.cancel()
        _videoInfoState.value = VideoInfoState.Idle
    }

    fun startDownload(url: String, quality: String, context: Context) {
        if (url.isBlank()) return

        val serviceIntent = Intent(context, DownloadService::class.java).apply {
            action = "START_DOWNLOAD"
            putExtra("URL", url)
            putExtra("QUALITY", quality)
        }
        context.startService(serviceIntent)
    }

    fun pauseDownload(context: Context, url: String) {
        val intent = Intent(context, DownloadService::class.java).apply { 
            action = "PAUSE_DOWNLOAD" 
            putExtra("URL", url)
        }
        context.startService(intent)
    }

    fun cancelDownload(context: Context, url: String) {
        val intent = Intent(context, DownloadService::class.java).apply {
            action = "CANCEL_DOWNLOAD"
            putExtra("URL", url)
        }
        context.startService(intent)
    }

    fun fetchPausedDownloads(context: Context) {
        _pausedDownloads.value = PauseRepository(context).getAllPausedDownloads()
    }

    fun resumeDownload(context: Context, url: String, quality: String) {
        PauseRepository(context).removePausedDownload(url)
        fetchPausedDownloads(context)
        startDownload(url, quality, context)
    }

    fun cancelPausedDownload(context: Context, url: String) {
        PauseRepository(context).removePausedDownload(url)
        fetchPausedDownloads(context)
    }

    fun fetchDownloadedFiles(context: Context?): Job? {
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch(Dispatchers.IO) {
            if (_filesListState.value !is FilesListState.Success) {
                _filesListState.value = FilesListState.Fetching
            }
            try {
                if (context == null) return@launch
                val permissionManager = PermissionManager(context)
                val customPath = permissionManager.getCustomDownloadFolderPath()
                val targetDir = File(customPath)

                // Leverage MediaStore for lightning-fast querying of the custom folder
                val fileSet = mutableSetOf<String>()
                val filesList = mutableListOf<File>()
                
                try {
                    val projection = arrayOf(MediaStore.MediaColumns.DATA)
                    val selection = "${MediaStore.MediaColumns.DATA} LIKE ?"
                    val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

                    // Query MediaStore for ALL _vdf files
                    val mediaUris = listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Files.getContentUri("external"))
                    for (uri in mediaUris) {
                        context.contentResolver.query(uri, projection, selection, arrayOf("$customPath/%_vdf.%"), sortOrder)?.use { cursor ->
                            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                            while (cursor.moveToNext()) {
                                val path = cursor.getString(dataCol)
                                val file = File(path)
                                if (file.exists() && fileSet.add(path)) {
                                    filesList.add(file)
                                }
                            }
                        }
                    }


                } catch (e: Exception) { e.printStackTrace() }

                // Fallback: Ensure we don't miss new files not yet scanned by MediaStore
                val fileRegex = Regex(".*_vdf\\.[^.]+$", RegexOption.IGNORE_CASE)
                val directFiles = targetDir.listFiles { file ->
                    file.isFile && file.name.matches(fileRegex) && fileSet.add(file.absolutePath)
                }
                
                if (directFiles != null) {
                    filesList.addAll(directFiles.sortedByDescending { f -> f.lastModified() })
                }

                // SAF Fallback: If app was reinstalled, Scoped Storage blocks listFiles() on old files.
                // We must read the actual directory using the SAF tree URI.
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

                // Grab existing loaded files to prevent overwriting them with placeholders
                val currentSuccessState = _filesListState.value as? FilesListState.Success
                val existingFilesMap = currentSuccessState?.files?.associateBy { it.path } ?: emptyMap()

                // STEP 1: Fast load - Instantly show files without waiting for heavy extraction
                val initialList = files.map { file ->
                    existingFilesMap[file.absolutePath] ?: run {
                        val (title, signature) = parseFileName(file.name)
                        val thumbFile = File(thumbCacheDir, "${file.name}.png")
                        DownloadedFileDetails(
                            title = title,
                            path = file.absolutePath,
                            signature = signature,
                            size = formatFileSize(file.length()),
                            duration = "--:--", // Placeholder, will be updated lazily
                            thumbnailUri = if (thumbFile.exists()) Uri.fromFile(thumbFile) else Uri.EMPTY
                        )
                    }
                }.toMutableList()

                // Immediately update UI with names and sizes
                _filesListState.value = FilesListState.Success(initialList.toList())

                // STEP 2: Lazy processing - Fetch durations and missing thumbnails in background
                for (i in files.indices) {
                    // Skip expensive processing if we already have a valid duration and thumbnail!
                    if (initialList[i].duration != "--:--" && initialList[i].thumbnailUri != Uri.EMPTY) {
                        continue
                    }

                    val file = files[i]
                    val thumbFile = File(thumbCacheDir, "${file.name}.png")
                    var updatedUri = initialList[i].thumbnailUri
                    var updatedDuration = initialList[i].duration

                    val retriever = MediaMetadataRetriever()
                    try {
                        var fileReadable = false
                        var attempts = 0
                        while (!fileReadable && attempts < 10) {
                            try {
                                val ext = file.extension.lowercase()
                                val mimeType = if (ext in listOf("mp3", "m4a")) "audio/*" else "video/*"
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
                                if (attempts < 10) delay(500)
                            }
                        }

                        if (fileReadable) {
                            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                            updatedDuration = formatDuration(durationMs)

                            if (!thumbFile.exists()) {
                                val timeUs = if (durationMs > 2000) (durationMs / 2) * 1000 else 1000000L
                                val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                if (bitmap != null) {
                                    FileOutputStream(thumbFile).use { out ->
                                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                                    }
                                    updatedUri = Uri.fromFile(thumbFile)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        try { retriever.release() } catch (e: Exception) {}
                    }

                    // Only update the state if something actually changed
                    if (updatedDuration != initialList[i].duration || updatedUri != initialList[i].thumbnailUri) {
                        initialList[i] = initialList[i].copy(duration = updatedDuration, thumbnailUri = updatedUri)
                        _filesListState.value = FilesListState.Success(initialList.toList())
                    }
                    
                    // Add a tiny delay to let Garbage Collection clean up Bitmaps to prevent OOM
                    delay(50)
                }
            } catch (e: Exception) {
                // Ignore Coroutine cancellations so they don't trigger the Error UI
                if (e is kotlinx.coroutines.CancellationException) throw e
                
                e.printStackTrace()
                _filesListState.value = FilesListState.Error("Failed to scan directory: ${e.message}")
            }
        }
        return fetchJob
    }
    
    // PARSING LOGIC: Extracts clean title and (Resolution)
    private fun parseFileName(fileName: String): Pair<String, String> {
        val lastIndex = fileName.lastIndexOf('.')
        if (lastIndex == -1) return fileName to "(MP4)"
        
        val nameWithoutExt = fileName.substring(0, lastIndex)
        
        var cleanName = nameWithoutExt
        if (cleanName.endsWith("_vdf", ignoreCase = true)) {
            cleanName = cleanName.substring(0, cleanName.length - 4)
        }

        val signatureRegex = """(.*)[\s_](\([^)]+\))$""".toRegex()
        val matchResult = signatureRegex.find(cleanName)
        
        return if (matchResult != null) {
            val (title, signature) = matchResult.destructured
            title.replace("_", " ") to signature
        } else {
            cleanName.replace("_", " ") to "(${fileName.substring(lastIndex + 1).uppercase()})"
        }
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

    private fun getFileUri(context: Context, absolutePath: String, mimeType: String): Uri? {
        val file = File(absolutePath)
        
        // 1. Try MediaStore
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

        // 2. Try SAF
        try {
            val permissionManager = PermissionManager(context)
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
        
        // 3. Try FileProvider if file is readable directly
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

    fun clearThumbnailCache(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val thumbCacheDir = File(context.cacheDir, "thumbnails")
                if (thumbCacheDir.exists() && thumbCacheDir.isDirectory) {
                    thumbCacheDir.listFiles()?.forEach { it.delete() }
                }
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Thumbnail cache cleared", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun resetState(url: String) {
        DownloadManager.removeDownload(url)
    }

    fun deleteVideo(
        context: Context,
        fileDetails: DownloadedFileDetails,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
        onPermissionRequired: () -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(fileDetails.path)
                
                // 1. Physically delete the file directly (Works because your app created it)
                var isDeleted = if (file.exists()) file.delete() else true

                // 2. Fallback to SAF if normal delete failed (due to Scoped Storage + reinstall)
                if (!isDeleted && file.exists()) {
                    val permissionManager = PermissionManager(context)

                    // Use either the dedicated delete permission URI or the general custom folder URI
                    val possibleUris = listOfNotNull(
                        permissionManager.getSavedFolderUri(),
                        permissionManager.getCustomDownloadFolderUri()
                    )

                    for (treeUri in possibleUris) {
                        try {
                            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                            var targetDocUri: Uri? = null

                            // Find the specific file inside the granted folder tree
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
                        // We don't have permission yet, ask the UI to request it
                        withContext(Dispatchers.Main) { onPermissionRequired() }
                        return@launch
                    }
                }

                if (isDeleted) {
                    // 3. Silently clear the MediaStore index to prevent broken "ghosts" in the Gallery.
                    // We catch and ignore SecurityExceptions here to guarantee NO system popups appear.
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
                    } catch (e: Exception) {
                        // Suppressed intentionally. The physical file is already gone.
                    }

                    // 3. Clean up the app UI instantly
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

        // Instantly remove from UI State so the user sees it disappear immediately
        val currentState = _filesListState.value
        if (currentState is FilesListState.Success) {
            val updatedList = currentState.files.filter { it.path != fileDetails.path }
            _filesListState.value = FilesListState.Success(updatedList)
        }
    }

    private suspend fun resolveCanonicalUrl(url: String): String {
        if (!url.contains("facebook.com/share") && !url.contains("fb.watch") && !url.contains("youtu.be") && !url.contains("instagr.am")) {
            return url
        }
        return withContext(Dispatchers.IO) {
            try {
                var current = url
                var redirects = 0
                while (redirects < 5) {
                    val conn = java.net.URL(current).openConnection() as java.net.HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.requestMethod = "HEAD"
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    val code = conn.responseCode
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        val loc = conn.getHeaderField("Location")
                        if (!loc.isNullOrBlank()) {
                            current = if (loc.startsWith("http")) loc else "https://www.facebook.com$loc"
                            redirects++
                        } else break
                    } else break
                    conn.disconnect()
                }
                current
            } catch (e: Exception) {
                url
            }
        }
    }
}