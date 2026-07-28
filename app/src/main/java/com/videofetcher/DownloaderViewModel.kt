package com.videofetcher

import android.content.Context
import android.content.ContentUris
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
private val FILE_SIGNATURE_REGEX = Regex(".*_vdf\\.(mp4|mp3|m4a|aac|flac|opus|wav|ogg|mkv|webm|3gp)$", RegexOption.IGNORE_CASE)

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

    private val _videoFiles = MutableStateFlow<List<DownloadedFileDetails>>(emptyList())
    val videoFiles: StateFlow<List<DownloadedFileDetails>> = _videoFiles.asStateFlow()

    private val _audioFiles = MutableStateFlow<List<DownloadedFileDetails>>(emptyList())
    val audioFiles: StateFlow<List<DownloadedFileDetails>> = _audioFiles.asStateFlow()

    private fun updateFilesListState(list: List<DownloadedFileDetails>) {
        _videoFiles.value = list.filter { !it.isAudio && it.path.endsWith("_vdf.mp4", ignoreCase = true) }
        _audioFiles.value = list.filter { it.isAudio }
        _filesListState.value = FilesListState.Success(list)
    }

    private val _pausedDownloads = MutableStateFlow<List<PausedDownload>>(emptyList())
    val pausedDownloads: StateFlow<List<PausedDownload>> = _pausedDownloads.asStateFlow()

    private val _videoInfoState = MutableStateFlow<VideoInfoState>(VideoInfoState.Idle)
    val videoInfoState: StateFlow<VideoInfoState> = _videoInfoState.asStateFlow()

    private val _engineUpdateState = MutableStateFlow<EngineUpdateState>(EngineUpdateState.Idle)
    val engineUpdateState: StateFlow<EngineUpdateState> = _engineUpdateState.asStateFlow()

    private val baseDirName = "VideoFetcher"
    private var fetchJob: Job? = null
    private var analyzeJob: Job? = null

    // ContentObserver for detecting external file deletions (e.g. via another file manager).
    // Uses a 300ms debounce Handler to collapse rapid successive MediaStore events into one call.
    // Calls triggerExternalRefresh() which has a 3s suppression window to avoid double-render
    // when the app's own MediaScannerConnection.scanFile() triggers the observer.
    private var appContext: Context? = null
    private val debounceHandler = Handler(Looper.getMainLooper())
    private val externalRefreshRunnable = Runnable { DownloadManager.triggerExternalRefresh() }
    private val mediaStoreObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            debounceHandler.removeCallbacks(externalRefreshRunnable)
            debounceHandler.postDelayed(externalRefreshRunnable, 300L)
        }
    }

    init {
        // Observer is registered lazily when fetchDownloadedFiles() is first called with a Context.
        // See registerMediaObserver(context).
    }

    private fun registerMediaObserver(context: Context) {
        if (appContext != null) return // Already registered
        appContext = context.applicationContext
        val resolver = appContext!!.contentResolver
        resolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaStoreObserver
        )
        resolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaStoreObserver
        )
    }

    override fun onCleared() {
        super.onCleared()
        debounceHandler.removeCallbacks(externalRefreshRunnable)
        appContext?.contentResolver?.unregisterContentObserver(mediaStoreObserver)
        appContext = null
    }

    fun checkForEngineUpdate(context: Context, forceCheck: Boolean = false) {
        viewModelScope.launch {
            if (forceCheck) {
                _engineUpdateState.value = EngineUpdateState.Checking
            }
            _engineUpdateState.value = com.videofetcher.settings.EngineUpdateManager(context).checkEngineStatus(context, forceCheck)
        }
    }

    fun updateEngine(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _engineUpdateState.value = EngineUpdateState.Updating
            val success = com.videofetcher.settings.EngineUpdateManager(context).updateYtDlpDirectly(context)
            if (success) {
                _engineUpdateState.value = EngineUpdateState.Success
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
                val cleanUrl = try {
                    android.net.Uri.parse(url).buildUpon().clearQuery().build().toString()
                } catch (e: Exception) {
                    url
                }
                val request = YoutubeDLRequest(cleanUrl)

                if (context != null) {
                    val domainKey = com.videofetcher.cookies.NetscapeCookieWriter.getDomainKey(cleanUrl)
                    val platformCookieFile = com.videofetcher.cookies.NetscapeCookieWriter.getCookieFileForUrl(context, cleanUrl)
                    val hasCookies = platformCookieFile != null

                    if (platformCookieFile != null) {
                        request.addOption("--cookies", platformCookieFile.absolutePath)
                        request.addOption("--retries", "2")
                        request.addOption("--fragment-retries", "1")
                    }

                    val effectiveUserAgent = com.videofetcher.cookies.UserAgentManager.getEffectiveUserAgentForDomain(
                        context,
                        domainKey,
                        isAuthenticated = hasCookies
                    )
                    request.addOption("--user-agent", effectiveUserAgent)
                }
                
                val domainKey = if (context != null) com.videofetcher.cookies.NetscapeCookieWriter.getDomainKey(cleanUrl) else ""
                
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
                if (context != null) {
                    val permissionManager = PermissionManager(context)
                    if (permissionManager.isBypassSslEnabled()) {
                        request.addOption("--no-check-certificates")
                    }
                    if (permissionManager.isBypassExtractorEnabled() && domainKey.lowercase() !in listOf("youtube", "facebook", "instagram", "tiktok")) {
                        request.addOption("--force-generic-extractor")
                    }
                }
                
                var info: com.yausername.youtubedl_android.mapper.VideoInfo? = null
                var attempt = 0
                val maxAttempts = if (context != null && com.videofetcher.cookies.NetscapeCookieWriter.getCookieFileForUrl(context, cleanUrl) != null) 2 else 1

                while (attempt < maxAttempts && isActive) {
                    attempt++
                    try {
                        info = YoutubeDL.getInstance().getInfo(request)
                        break
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        if (maxAttempts > 1 && com.videofetcher.cookies.NetscapeCookieWriter.isAuthException(e.message) && attempt < maxAttempts) {
                            delay(1000)
                        } else if (attempt >= maxAttempts) {
                            throw e
                        }
                    }
                }

                if (info == null) {
                    _videoInfoState.value = VideoInfoState.Error("Failed to fetch media metadata.")
                    return@launch
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

        val currentInfo = _videoInfoState.value
        val thumbUrl = if (currentInfo is VideoInfoState.Success) currentInfo.thumbnailUrl else ""
        val titleText = if (currentInfo is VideoInfoState.Success) currentInfo.title else "Video"

        if (thumbUrl.isNotBlank()) {
            DownloadManager.updateDownloadThumbnail(url, thumbUrl)
        }

        val serviceIntent = Intent(context, DownloadService::class.java).apply {
            action = "START_DOWNLOAD"
            putExtra("URL", url)
            putExtra("QUALITY", quality)
            putExtra("THUMBNAIL_URL", thumbUrl)
            putExtra("TITLE", titleText)
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
                registerMediaObserver(context)
                val permissionManager = PermissionManager(context)
                val customPath = permissionManager.getCustomDownloadFolderPath()
                val targetDir = File(customPath)

                // Leverage MediaStore SQLite index for 0ms metadata retrieval of custom folder
                val fileSet = mutableSetOf<String>()
                val filesList = mutableListOf<File>()
                val mediaStoreMetadataMap = mutableMapOf<String, Pair<String, String>>() // path -> (duration, size)
                
                try {
                    val projection = arrayOf(
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DATA,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.DURATION
                    )
                    val selection = "${MediaStore.MediaColumns.DATA} LIKE ?"
                    val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

                    // Query MediaStore for ALL _vdf files
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

                // STEP 1: Fast load - Instantly populate metadata from MediaStore SQLite query
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

                // Immediately update UI with names and sizes
                updateFilesListState(initialList.toList())

                // STEP 2: Parallel lazy processing with strict CPU throttling (max 2 worker threads)
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
                                            updateFilesListState(initialList.toList())
                                        }
                                    }
                                }
                            }
                        }.awaitAll()
                    }
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
    
    // PARSING LOGIC: Strictly extracts clean title and (Resolution) subtext anchored right-to-left before _vdf at the end
    private fun parseFileName(fileName: String): Pair<String, String> {
        val lastIndex = fileName.lastIndexOf('.')
        if (lastIndex == -1) return fileName to "(MP4)"

        val ext = fileName.substring(lastIndex + 1)
        val nameWithoutExt = fileName.substring(0, lastIndex)

        // 1. Anchored Regex matching right-to-left right before _vdf at the end: ..._(reso)_vdf
        val vdfRegex = """^(.*?)[\s_](\([^)]+\))_vdf$""".toRegex(RegexOption.IGNORE_CASE)
        val vdfMatch = vdfRegex.find(nameWithoutExt)

        if (vdfMatch != null) {
            val title = vdfMatch.groupValues[1].replace("_", " ").trim()
            val signature = vdfMatch.groupValues[2]
            return title to signature
        }

        // 2. Fallback if _vdf is missing: match right-to-left anchored at the end of nameWithoutExt: ..._(reso)
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

        return cleanName.replace("_", " ").trim() to "(${ext.uppercase()})"
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
}