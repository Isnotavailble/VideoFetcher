package com.videofetcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import com.videofetcher.settings.SettingsManager
import com.videofetcher.theme.VideoFetcherTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

@OptIn(ExperimentalMaterial3Api::class)
class QuickDownloadActivity : ComponentActivity() {
    private lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        settingsManager = SettingsManager(applicationContext)

        // Asynchronously restore surviving persistent cookie backups into private app storage on launch
        lifecycleScope.launch(Dispatchers.IO) {
            com.videofetcher.cookies.NetscapeCookieWriter.restoreAllBackupsToPrivateStorage(applicationContext)
        }

        var sharedUrl = ""
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            // Instantly extract the URL from the shared text
            val urlRegex = """(?i)\b((?:https?://|www\d{0,3}[.]|[a-z0-9.\-]+[.][a-z]{2,4}/)(?:[^\s()<>]+|\(([^\s()<>]+|(\([^\s()<>]+\)))*\))+(?:\(([^\s()<>]+|(\([^\s()<>]+\)))*\)|[^\s`!()\[\]{};:'".,<>?«»“”‘’]))""".toRegex()
            val match = urlRegex.find(sharedText)
            sharedUrl = match?.value ?: sharedText
        }

        // If there is no URL, close silently without interrupting the user
        if (sharedUrl.isBlank()) {
            finish()
            return
        }

        // Fetch the theme synchronously so the bottom sheet never flashes the wrong color
        val initialDarkTheme = runBlocking { settingsManager.isDark.first() }

        setContent {
            val isDarkTheme by settingsManager.isDark.collectAsState(initial = initialDarkTheme)

            // HEAD START: Silently initialize the engine while the user is looking at the UI.
            // If they are fast, the Service will finish it. If they are slow, it will be ready!
            LaunchedEffect(Unit) {
                launch(Dispatchers.IO) {
                    try {
                        YoutubeDL.getInstance().init(applicationContext)
                        FFmpeg.getInstance().init(applicationContext)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            VideoFetcherTheme(darkTheme = isDarkTheme) {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                var selectedFormat by remember { mutableStateOf("1080p") }
                var selectedLightningFormat by remember { mutableStateOf("Best Quality") }
                val scope = rememberCoroutineScope()
                val context = LocalContext.current
                val surfaceColor = MaterialTheme.colorScheme.surface

                val permissionManager = remember { PermissionManager(context) }
                val isResolutionSelectionEnabled = remember { permissionManager.isResolutionSelectionEnabled() }
                val viewModel: DownloaderViewModel = viewModel()
                val videoInfoState by viewModel.videoInfoState.collectAsState()
                val engineUpdateState by viewModel.engineUpdateState.collectAsState()

                LaunchedEffect(Unit) {
                    viewModel.initializeEngine(context.applicationContext)
                    viewModel.checkForEngineUpdate(context.applicationContext)
                }

                // Automatically trigger the background fetch if the user has the setting enabled
                LaunchedEffect(sharedUrl) {
                    if (isResolutionSelectionEnabled && sharedUrl.isNotBlank()) {
                        viewModel.analyzeUrl(sharedUrl, context.applicationContext)
                    }
                }

                LaunchedEffect(videoInfoState) {
                    val currentState = videoInfoState
                    if (currentState is VideoInfoState.Success && selectedFormat !in currentState.formats) {
                        selectedFormat = currentState.formats.firstOrNull() ?: "Best Quality"
                    }
                }

                val fetchingTexts = listOf("Analyzing link...", "Getting resolution options...", "Finalizing options...")
                var fetchingTextIndex by remember { mutableStateOf(0) }

                LaunchedEffect(videoInfoState) {
                    if (videoInfoState is VideoInfoState.Fetching) {
                        fetchingTextIndex = 0
                        while (true) {
                            delay(2500)
                            if (fetchingTextIndex < fetchingTexts.size - 1) fetchingTextIndex++
                        }
                    }
                }
                
                val isFetching = videoInfoState is VideoInfoState.Fetching

                // Launcher to ask for permissions directly from the bottom sheet if it's their first time
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { _ ->
                    val storageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
                    } else {
                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                    }

                    if (storageGranted) {
                        Toast.makeText(context, "VideoFetcher: Downloading...", Toast.LENGTH_SHORT).show()
                        scope.launch {
                            val qualityToDownload = if (isResolutionSelectionEnabled) selectedFormat else "Best Quality"
                            val serviceIntent = Intent(context, DownloadService::class.java).apply {
                                action = "START_DOWNLOAD"
                                putExtra("URL", sharedUrl)
                                putExtra("QUALITY", qualityToDownload)
                            }
                            context.startService(serviceIntent)
                            sheetState.hide()
                            finish()
                        }
                    } else {
                        Toast.makeText(context, "Permissions required to save videos.", Toast.LENGTH_SHORT).show()
                    }
                }

                // The Bottom Sheet overlay
                ModalBottomSheet(
                    onDismissRequest = { finish() }, // Close the transparent activity if they swipe down to cancel
                    sheetState = sheetState,
                    containerColor = surfaceColor,
                    dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)) }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = "VIDEO FETCHER",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = sharedUrl,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        if (isResolutionSelectionEnabled) {
                            AnimatedVisibility(visible = videoInfoState is VideoInfoState.Success || videoInfoState is VideoInfoState.Error) {
                                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                    when (val state = videoInfoState) {
                                        is VideoInfoState.Error -> {
                                            Text(text = state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp))
                                        }
                                        is VideoInfoState.Success -> {
                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(modifier = Modifier.padding(12.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        SubcomposeAsyncImage(
                                                            model = ImageRequest.Builder(LocalContext.current)
                                                                .data(state.thumbnailUrl)
                                                                .crossfade(true)
                                                                .build(),
                                                            contentDescription = "Video Thumbnail",
                                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                                            modifier = Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)),
                                                            loading = { Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))) },
                                                            error = { Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))) }
                                                        )
                                                        Spacer(modifier = Modifier.width(12.dp))
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(text = state.title, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                                                            Spacer(modifier = Modifier.height(4.dp))
                                                            Text(text = state.duration, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                                                        }
                                                    }
                                                    Spacer(modifier = Modifier.height(16.dp))

                                                    var downloadType by remember(videoInfoState) {
                                                        mutableStateOf(
                                                            if (selectedFormat.contains("Audio", ignoreCase = true)) DownloadType.AUDIO else DownloadType.VIDEO
                                                        )
                                                    }

                                                    val videoFormats = state.formats.filter { !it.contains("Audio", ignoreCase = true) }
                                                    val audioFormats = state.formats.filter { it.contains("Audio", ignoreCase = true) }

                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                    ) {
                                                        // Video Pill
                                                        Surface(
                                                            onClick = {
                                                                downloadType = DownloadType.VIDEO
                                                                val firstVideo = videoFormats.firstOrNull() ?: "Best Quality"
                                                                selectedFormat = firstVideo
                                                            },
                                                            shape = RoundedCornerShape(12.dp),
                                                            color = if (downloadType == DownloadType.VIDEO) MaterialTheme.colorScheme.primary else Color.Transparent,
                                                            border = if (downloadType == DownloadType.VIDEO) null else BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                                                            modifier = Modifier.weight(1f)
                                                        ) {
                                                            Row(
                                                                modifier = Modifier.padding(vertical = 8.dp),
                                                                horizontalArrangement = Arrangement.Center,
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Icon(
                                                                    painter = painterResource(id = R.drawable.ic_video),
                                                                    contentDescription = null,
                                                                    modifier = Modifier.size(16.dp),
                                                                    tint = if (downloadType == DownloadType.VIDEO) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                                                )
                                                                Spacer(modifier = Modifier.width(8.dp))
                                                                Text(
                                                                    text = "Video",
                                                                    color = if (downloadType == DownloadType.VIDEO) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                                                )
                                                            }
                                                        }

                                                        // Audio Pill
                                                        Surface(
                                                            onClick = {
                                                                downloadType = DownloadType.AUDIO
                                                                val firstAudio = audioFormats.firstOrNull() ?: "Audio (MP3) - High Quality"
                                                                selectedFormat = firstAudio
                                                            },
                                                            shape = RoundedCornerShape(12.dp),
                                                            color = if (downloadType == DownloadType.AUDIO) MaterialTheme.colorScheme.primary else Color.Transparent,
                                                            border = if (downloadType == DownloadType.AUDIO) null else BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                                                            modifier = Modifier.weight(1f)
                                                        ) {
                                                            Row(
                                                                modifier = Modifier.padding(vertical = 8.dp),
                                                                horizontalArrangement = Arrangement.Center,
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Icon(
                                                                    painter = painterResource(id = R.drawable.ic_music),
                                                                    contentDescription = null,
                                                                    modifier = Modifier.size(16.dp),
                                                                    tint = if (downloadType == DownloadType.AUDIO) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                                                )
                                                                Spacer(modifier = Modifier.width(8.dp))
                                                                Text(
                                                                    text = "Audio Only",
                                                                    color = if (downloadType == DownloadType.AUDIO) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                                                )
                                                            }
                                                        }
                                                    }

                                                    Spacer(modifier = Modifier.height(12.dp))

                                                    val visibleFormats = if (downloadType == DownloadType.VIDEO) videoFormats else audioFormats

                                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                        items(visibleFormats) { format ->
                                                            val isSelected = selectedFormat == format
                                                            Surface(
                                                                onClick = { selectedFormat = format },
                                                                shape = RoundedCornerShape(16.dp),
                                                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                                                border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                                                                modifier = Modifier.defaultMinSize(minWidth = 64.dp)
                                                            ) {
                                                                Text(
                                                                    text = format,
                                                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium),
                                                                    textAlign = TextAlign.Center,
                                                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        else -> {}
                                    }
                                }
                            }
                        } else {
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Text(
                                    text = "Download Format",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                                )
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    val isVideoSelected = selectedLightningFormat == "Best Quality"
                                    
                                    // Video Pill
                                    Surface(
                                        onClick = { selectedLightningFormat = "Best Quality" },
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isVideoSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        border = if (isVideoSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(vertical = 8.dp),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_video),
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = if (isVideoSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Video (Best)",
                                                color = if (isVideoSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                            )
                                        }
                                    }

                                    // Audio Pill
                                    Surface(
                                        onClick = { selectedLightningFormat = "Audio (MP3) - High Quality" },
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (!isVideoSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        border = if (!isVideoSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(vertical = 8.dp),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_music),
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = if (!isVideoSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Audio (MP3)",
                                                color = if (!isVideoSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(32.dp))
                        
                        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                        val pulseAlpha by infiniteTransition.animateFloat(
                            initialValue = 0.5f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(800, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "alpha"
                        )

                        Button(
                            onClick = {
                                val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                }
                                
                                val hasPermissions = permissionsToRequest.all {
                                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                                }

                                if (hasPermissions) {
                                    Toast.makeText(context, "VideoFetcher: Downloading...", Toast.LENGTH_SHORT).show()
                                    scope.launch {
                                        val qualityToDownload = if (isResolutionSelectionEnabled) selectedFormat else selectedLightningFormat
                                        val serviceIntent = Intent(context, DownloadService::class.java).apply {
                                            action = "START_DOWNLOAD"
                                            putExtra("URL", sharedUrl)
                                            putExtra("QUALITY", qualityToDownload)
                                        }
                                        context.startService(serviceIntent)
                                        sheetState.hide()
                                        finish()
                                    }
                                } else {
                                    // Ask for permissions right on top of the bottom sheet!
                                    permissionLauncher.launch(permissionsToRequest)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary, 
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            enabled = !isFetching
                        ) {
                            if (isResolutionSelectionEnabled && isFetching) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(fetchingTexts[fetchingTextIndex], style = MaterialTheme.typography.titleMedium, modifier = Modifier.alpha(pulseAlpha))
                            } else {
                                Icon(painter = painterResource(id = R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "START DOWNLOAD", 
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
                
                EngineUpdateDialog(engineUpdateState, viewModel, context)
            }
        }
    }
}