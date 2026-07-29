package com.videofetcher

import android.Manifest
import android.os.Build
import android.net.Uri
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.videofetcher.manager.PausedDownload
import com.videofetcher.manager.DownloadManager
import com.videofetcher.manager.PermissionManager
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.videofetcher.manager.CookieManager
import com.videofetcher.cookies.BrowserScreen
import com.videofetcher.cookies.CookieManagementScreen
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.clickable
import android.provider.DocumentsContract
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.transform.RoundedCornersTransformation
import kotlinx.coroutines.delay

enum class AppTab { HOME, FILES, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
enum class DownloadType {
    VIDEO, AUDIO
}

@Composable
fun VideoDownloaderUI(
    viewModel: DownloaderViewModel = viewModel(factory = AppViewModelFactory((androidx.compose.ui.platform.LocalContext.current.applicationContext as VideoFetcherApp).container)),
    sharedUrl: String = "",
    isDarkTheme: Boolean,
    onThemeToggle: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var url by remember { mutableStateOf("") }
    var selectedFormat by remember { mutableStateOf("1080p") }
    var selectedLightningFormat by remember { mutableStateOf("Best Quality") }
    val engineState by viewModel.engineState.collectAsState()
    val activeDownloads by viewModel.activeDownloads.collectAsState()
    val videoInfoState by viewModel.videoInfoState.collectAsState()
    val filesListState by viewModel.filesListState.collectAsState()
    val pausedDownloads by viewModel.pausedDownloads.collectAsState()
    val engineUpdateState by viewModel.engineUpdateState.collectAsState()

    var currentTab by remember { mutableStateOf(AppTab.HOME) }
    
    val permissionManager = remember { (context.applicationContext as com.videofetcher.VideoFetcherApp).container.permissionManager }
    var isResolutionSelectionEnabled by remember { mutableStateOf(permissionManager.isResolutionSelectionEnabled()) }

    // Only refresh when the number of items or successes changes, avoiding progress-tick loops
    val successCount = activeDownloads.values.count { it is DownloadState.Success }
    val activeCount = activeDownloads.size
    val refreshCounter by (context.applicationContext as com.videofetcher.VideoFetcherApp).container.downloadManager.fileRefreshCounter.collectAsState()
    
    LaunchedEffect(successCount, activeCount, refreshCounter) {
        viewModel.fetchPausedDownloads(context.applicationContext)
        viewModel.fetchDownloadedFiles(context.applicationContext)
    }

    LaunchedEffect(Unit) {
        viewModel.checkForEngineUpdate(context.applicationContext)
    }

    // Update URL field automatically when intent brings a new link
    LaunchedEffect(sharedUrl) {
        if (sharedUrl.isNotEmpty() && sharedUrl != url) {
            url = sharedUrl
            viewModel.clearVideoInfo()
        }
    }

    // Determine which storage permissions to ask for based on Android version
    val storagePermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var hasStoragePermission by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasStoragePermission = storagePermissions.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        if (hasStoragePermission) {
            viewModel.fetchDownloadedFiles(context.applicationContext)
        }
    }

    val storageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        hasStoragePermission = storagePermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        
        // Always ask for notifications next, even if storage was denied or jammed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && 
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (hasStoragePermission) {
            viewModel.fetchDownloadedFiles(context.applicationContext)
        }
    }

    val requestStoragePermission = {
        val missingStorage = storagePermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (missingStorage.isNotEmpty()) {
            storageLauncher.launch(missingStorage)
        }
    }

    LaunchedEffect(Unit) {
        val missingStorage = storagePermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (missingStorage.isNotEmpty()) {
            delay(500) // Increased delay to ensure UI is fully painted before requesting
            storageLauncher.launch(missingStorage)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && 
                   ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            delay(500)
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.fetchDownloadedFiles(context.applicationContext)
        }
    }

    val isReady = engineState is EngineState.Idle

    var showAboutScreen by remember { mutableStateOf(false) }
    var activeBrowserUrl by remember { mutableStateOf<String?>(null) }
    var showCookieManager by remember { mutableStateOf(false) }
    var cookieRefreshTrigger by remember { mutableStateOf(0) }

    if (showAboutScreen) {
        AboutScreen(onBack = { showAboutScreen = false })
    } else if (activeBrowserUrl != null) {
        BrowserScreen(
            initialUrl = activeBrowserUrl!!,
            onBack = { activeBrowserUrl = null },
            onCookiesSaved = { cookieRefreshTrigger++ }
        )
    } else if (showCookieManager) {
        CookieManagementScreen(
            onBack = { showCookieManager = false },
            onOpenBrowserForDomain = { domainUrl ->
                activeBrowserUrl = domainUrl
                showCookieManager = false
            }
        )
    } else {
    Scaffold(
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.height(64.dp),
                    tonalElevation = 0.dp
                ) {
                    val navItemColors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = Color.Transparent, // Removes the standard purple pill
                        unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )

                    NavigationBarItem(
                        selected = currentTab == AppTab.HOME,
                        onClick = { currentTab = AppTab.HOME },
                        icon = { Icon(Icons.Filled.Home, "Home") },
                        label = { Text("Home", fontWeight = if (currentTab == AppTab.HOME) FontWeight.Bold else FontWeight.Normal) },
                        colors = navItemColors
                    )
                    NavigationBarItem(
                        selected = currentTab == AppTab.FILES,
                        onClick = { currentTab = AppTab.FILES },
                        icon = { Icon(painterResource(id = R.drawable.ic_folder), "Files", modifier = Modifier.size(24.dp), tint = LocalContentColor.current) },
                        label = { Text("Files", fontWeight = if (currentTab == AppTab.FILES) FontWeight.Bold else FontWeight.Normal) },
                        colors = navItemColors
                    )
                    NavigationBarItem(
                        selected = currentTab == AppTab.SETTINGS,
                        onClick = { currentTab = AppTab.SETTINGS },
                        icon = { Icon(Icons.Filled.Settings, "Settings") },
                        label = { Text("Settings", fontWeight = if (currentTab == AppTab.SETTINGS) FontWeight.Bold else FontWeight.Normal) },
                        colors = navItemColors
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            
            // --- DYNAMIC CONTENT BASED ON TAB ---
            when (currentTab) {
                AppTab.HOME -> HomeContent(
                    url = url,
                    onUrlChange = { newUrl -> 
                        if (url != newUrl) {
                            url = newUrl
                            viewModel.clearVideoInfo()
                        }
                    },
                    isResolutionSelectionEnabled = isResolutionSelectionEnabled,
                    selectedFormat = selectedFormat,
                    onFormatChange = { selectedFormat = it },
                    selectedLightningFormat = selectedLightningFormat,
                    onLightningFormatChange = { selectedLightningFormat = it },
                    engineState = engineState,
                    videoInfoState = videoInfoState,
                    activeDownloads = activeDownloads,
                    isReady = isReady,
                    viewModel = viewModel,
                    clipboardManager = clipboardManager,
                    context = context,
                    hasStoragePermission = hasStoragePermission,
                    onRequestPermission = requestStoragePermission
                )
                AppTab.FILES -> FilesContent(pausedDownloads, filesListState, viewModel, context, hasStoragePermission, requestStoragePermission)
                AppTab.SETTINGS -> SettingsContent(
                    isDarkTheme = isDarkTheme,
                    onThemeToggle = onThemeToggle,
                    isResolutionSelectionEnabled = isResolutionSelectionEnabled,
                    onResolutionSelectionChange = { 
                        permissionManager.setResolutionSelectionEnabled(it)
                        isResolutionSelectionEnabled = it
                    },
                    viewModel = viewModel,
                    context = context,
                    onAboutClick = { showAboutScreen = true },
                    onOpenBrowser = { url -> activeBrowserUrl = url },
                    onOpenCookieManager = { showCookieManager = true },
                    cookieRefreshTrigger = cookieRefreshTrigger
                )
            }
        }
    }
    
    EngineUpdateDialog(engineUpdateState, viewModel, context)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    url: String,
    onUrlChange: (String) -> Unit,
    isResolutionSelectionEnabled: Boolean,
    selectedFormat: String,
    onFormatChange: (String) -> Unit,
    selectedLightningFormat: String,
    onLightningFormatChange: (String) -> Unit,
    engineState: EngineState,
    videoInfoState: VideoInfoState,
    activeDownloads: Map<String, DownloadState>,
    isReady: Boolean,
    viewModel: DownloaderViewModel,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
    context: android.content.Context,
    hasStoragePermission: Boolean,
    onRequestPermission: () -> Unit
) {
    // Auto-select the best format when fetched successfully
    LaunchedEffect(videoInfoState) {
        if (videoInfoState is VideoInfoState.Success && selectedFormat !in videoInfoState.formats) {
            onFormatChange(videoInfoState.formats.firstOrNull() ?: "Best Quality")
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

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "VIDEO FETCHER",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 48.dp)
        )
        
        val inputContainerColor = if (isReady) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
        
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            placeholder = { Text("Paste video link here...", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) },
            trailingIcon = {
                if (url.isEmpty()) {
                    IconButton(
                        onClick = {
                            val clipText = clipboardManager.getText()?.text
                            if (!clipText.isNullOrBlank()) onUrlChange(clipText)
                        },
                        enabled = isReady
                    ) {
                        Icon(painterResource(id = R.drawable.ic_content_paste), contentDescription = "Paste URL", tint = if (isReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                    }
                } else {
                    IconButton(
                        onClick = { onUrlChange("") },
                        enabled = isReady
                    ) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear URL", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isReady) 0.5f else 0.3f))
                    }
                }
            },
            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = isReady,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = inputContainerColor, 
                unfocusedContainerColor = inputContainerColor, 
                disabledContainerColor = inputContainerColor,
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), 
                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f), 
                disabledBorderColor = Color.Transparent,
                disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            ),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        AnimatedVisibility(visible = isResolutionSelectionEnabled && (videoInfoState is VideoInfoState.Success || videoInfoState is VideoInfoState.Error)) {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                when (val state = videoInfoState) {
                    is VideoInfoState.Error -> {
                        Text(text = state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                    is VideoInfoState.Success -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    VideoThumbnailBox(
                                        imageData = state.thumbnailUrl,
                                        isAudio = false,
                                        size = 60.dp
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
                                            onFormatChange(firstVideo)
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
                                            onFormatChange(firstAudio)
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
                                            onClick = { onFormatChange(format) },
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

        AnimatedVisibility(visible = !isResolutionSelectionEnabled) {
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
                        onClick = { onLightningFormatChange("Best Quality") },
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
                        onClick = { onLightningFormatChange("Audio (MP3) - High Quality") },
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

        val isFetching = videoInfoState is VideoInfoState.Fetching
        val needsAnalysis = isResolutionSelectionEnabled && (videoInfoState is VideoInfoState.Idle || videoInfoState is VideoInfoState.Error)

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
                if (isResolutionSelectionEnabled && needsAnalysis) {
                    viewModel.analyzeUrl(url, context.applicationContext)
                } else {
                    if (hasStoragePermission) {
                        val qualityToDownload = if (isResolutionSelectionEnabled) selectedFormat else selectedLightningFormat
                        viewModel.startDownload(url, qualityToDownload, context.applicationContext)
                        onUrlChange("") 
                    } else {
                        onRequestPermission()
                        Toast.makeText(context, "Storage permission required to download", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary, 
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            ),
            shape = RoundedCornerShape(12.dp), 
            enabled = url.isNotBlank() && isReady && (!isResolutionSelectionEnabled || !isFetching)
        ) {
            if (isResolutionSelectionEnabled && isFetching) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Text(fetchingTexts[fetchingTextIndex], style = MaterialTheme.typography.titleMedium, modifier = Modifier.alpha(pulseAlpha))
            } else if (isResolutionSelectionEnabled && needsAnalysis) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Next", style = MaterialTheme.typography.titleMedium)
            } else {
                Icon(painterResource(id = R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Download", style = MaterialTheme.typography.titleMedium)
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "Active Queue",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (engineState is EngineState.Initializing) {
                item { InitializingCard(); Spacer(modifier = Modifier.height(16.dp)) }
            } else if (engineState is EngineState.Error) {
                item { ErrorCard(engineState.message); Spacer(modifier = Modifier.height(16.dp)) }
            }

            if (isReady && activeDownloads.isEmpty() && engineState !is EngineState.Initializing && engineState !is EngineState.Error) {
                item {
                    Text(
                        text = "No downloads yet.",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp)
                    )
                }
            }

            items(activeDownloads.entries.toList()) { (downloadUrl, downloadState) ->
                ActiveDownloadCard(
                    downloadState = downloadState,
                    url = downloadUrl,
                    viewModel = viewModel,
                    context = context
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesContent(
    pausedDownloads: List<PausedDownload>,
    filesListState: FilesListState,
    viewModel: DownloaderViewModel,
    context: android.content.Context,
    hasStoragePermission: Boolean,
    onRequestPermission: () -> Unit
) {
    var fileToConfirmDelete by remember { mutableStateOf<DownloadedFileDetails?>(null) }

    var fileAwaitingPermission by remember { mutableStateOf<DownloadedFileDetails?>(null) }
    var showFolderInstructionDialog by remember { mutableStateOf(false) }
    var expandedSection by remember { mutableStateOf("VIDEO") }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                val treeDocumentId = DocumentsContract.getTreeDocumentId(uri)
                // Verify they selected our specific folder
                if (treeDocumentId.endsWith("VideoFetcher", ignoreCase = true)) {
                    (context.applicationContext as com.videofetcher.VideoFetcherApp).container.permissionManager.saveFolderPermission(uri)
                    
                    // Permission saved! Automatically retry the deletion silently
                    fileAwaitingPermission?.let { fileToDelete ->
                        val isAudio = fileToDelete.path.contains("(MP3", ignoreCase = true) || fileToDelete.path.contains("(M4A", ignoreCase = true) || fileToDelete.path.endsWith(".mp3", ignoreCase = true) || fileToDelete.path.endsWith(".m4a", ignoreCase = true)
                        val typeLabel = if (isAudio) "Audio" else "Video"
                        viewModel.deleteVideo(
                            context = context.applicationContext,
                            fileDetails = fileToDelete,
                            onSuccess = {
                                Toast.makeText(context, "$typeLabel deleted", Toast.LENGTH_SHORT).show()
                                viewModel.fetchDownloadedFiles(context.applicationContext)
                            },
                            onError = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() },
                            onPermissionRequired = { Toast.makeText(context, "Permission error", Toast.LENGTH_SHORT).show() }
                        )
                    }
                } else {
                    Toast.makeText(context, "Incorrect folder. Please choose 'VideoFetcher'.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "Error verifying folder", Toast.LENGTH_SHORT).show()
            }
        }
        fileAwaitingPermission = null
    }

    if (showFolderInstructionDialog) {
        AlertDialog(
            onDismissRequest = {
                showFolderInstructionDialog = false
                fileAwaitingPermission = null
            },
            title = { Text("Grant Folder Access", fontWeight = FontWeight.Bold) },
            text = { Text("To delete files downloaded from a previous installation, we need access to the VideoFetcher folder.\n\nPlease tap 'Continue', select the 'VideoFetcher' folder, and tap 'Use this folder'.") },
            confirmButton = {
                TextButton(onClick = {
                    showFolderInstructionDialog = false
                    folderPickerLauncher.launch(null)
                }) {
                    Text("Continue", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showFolderInstructionDialog = false
                    fileAwaitingPermission = null
                }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }

    if (fileToConfirmDelete != null) {
        val isAudio = fileToConfirmDelete?.path?.let {
            it.contains("(MP3", ignoreCase = true) || it.contains("(M4A", ignoreCase = true) || it.endsWith(".mp3", ignoreCase = true) || it.endsWith(".m4a", ignoreCase = true)
        } == true
        val typeLabel = if (isAudio) "Audio" else "Video"

        AlertDialog(
            onDismissRequest = { fileToConfirmDelete = null },
            title = { Text("Delete $typeLabel", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to delete '${fileToConfirmDelete?.title}'?\nThis action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val fileToDelete = fileToConfirmDelete!!
                        fileToConfirmDelete = null
                        viewModel.deleteVideo(
                            context = context.applicationContext,
                            fileDetails = fileToDelete,
                            onSuccess = {
                                Toast.makeText(context, "$typeLabel deleted", Toast.LENGTH_SHORT).show()
                                viewModel.fetchDownloadedFiles(context.applicationContext)
                            },
                            onError = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() },
                            onPermissionRequired = {
                                fileAwaitingPermission = fileToDelete
                                showFolderInstructionDialog = true
                            }
                        )
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToConfirmDelete = null }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }

    val videoFiles by viewModel.videoFiles.collectAsState()
    val audioFiles by viewModel.audioFiles.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Text("My Files", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(24.dp))
        
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (!hasStoragePermission) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Storage Permission Missing", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("We need access to your storage to display and manage your downloaded videos.", textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = onRequestPermission,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
                            ) {
                                Text("Grant Permission", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            if (pausedDownloads.isNotEmpty()) {
                item { Text("Paused Videos", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(bottom = 8.dp)) }
                items(items = pausedDownloads, key = { it.url }, contentType = { "paused_card" }) { paused ->
                    PausedVideoCard(
                        paused = paused,
                        onResume = { viewModel.resumeDownload(context.applicationContext, paused.url, paused.quality) },
                        onCancel = { viewModel.cancelPausedDownload(context.applicationContext, paused.url) }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            when (val filesList = filesListState) {
                is FilesListState.Success -> {
                    if (filesList.files.isEmpty() && pausedDownloads.isEmpty()) {
                        item { Text("No downloads yet.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) }
                    } else {
                        // Video Downloads Header
                        stickyHeader {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.background)
                                    .clickable { expandedSection = if (expandedSection == "VIDEO") "NONE" else "VIDEO" }
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Video Downloads", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Icon(
                                    imageVector = if (expandedSection == "VIDEO") Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                    contentDescription = "Expand/Collapse",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        if (expandedSection == "VIDEO") {
                            if (videoFiles.isEmpty()) {
                                item {
                                    Text("No video downloads yet.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(bottom = 16.dp))
                                }
                            } else {
                                items(items = videoFiles, key = { it.path }, contentType = { "downloaded_video" }) { file ->
                                    DownloadedVideoCard(
                                        file = file,
                                        viewModel = viewModel,
                                        context = context,
                                        onDelete = { fileToDelete -> fileToConfirmDelete = fileToDelete }
                                    )
                                }
                            }
                        }

                        // Audio Downloads Header
                        stickyHeader {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.background)
                                    .clickable { expandedSection = if (expandedSection == "AUDIO") "NONE" else "AUDIO" }
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Audio Downloads", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Icon(
                                    imageVector = if (expandedSection == "AUDIO") Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                    contentDescription = "Expand/Collapse",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        if (expandedSection == "AUDIO") {
                            if (audioFiles.isEmpty()) {
                                item {
                                    Text("No audio downloads yet.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(bottom = 16.dp))
                                }
                            } else {
                                items(items = audioFiles, key = { it.path }, contentType = { "downloaded_audio" }) { file ->
                                    DownloadedVideoCard(
                                        file = file,
                                        viewModel = viewModel,
                                        context = context,
                                        onDelete = { fileToDelete -> fileToConfirmDelete = fileToDelete }
                                    )
                                }
                            }
                        }
                    }
                }
                is FilesListState.Error -> { item { ErrorCard(filesList.message) } }
                is FilesListState.Fetching -> { item { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) } }
                else -> {}
            }
        }
    }
}

@Composable
fun SettingsContent(
    isDarkTheme: Boolean, 
    onThemeToggle: () -> Unit,
    isResolutionSelectionEnabled: Boolean,
    onResolutionSelectionChange: (Boolean) -> Unit,
    viewModel: DownloaderViewModel,
    context: android.content.Context,
    onAboutClick: () -> Unit,
    onOpenBrowser: (String) -> Unit,
    onOpenCookieManager: () -> Unit,
    cookieRefreshTrigger: Int
) {
    val scrollState = rememberScrollState()
    val permissionManager = remember { (context.applicationContext as com.videofetcher.VideoFetcherApp).container.permissionManager }
    var currentPath by remember { mutableStateOf(permissionManager.getCustomDownloadFolderPath()) }
    
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val success = permissionManager.saveCustomDownloadFolder(uri)
            if (success) {
                currentPath = permissionManager.getCustomDownloadFolderPath()
                Toast.makeText(context, "Download folder updated!", Toast.LENGTH_SHORT).show()
                viewModel.fetchDownloadedFiles(context)
            } else {
                Toast.makeText(context, "Please select a folder on Internal Storage.", Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(24.dp))

        Text("Appearance", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(id = if (isDarkTheme) R.drawable.ic_dark_mode else R.drawable.ic_light_mode), contentDescription = "Theme", tint = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.width(16.dp))
                Text("Dark Mode", color = MaterialTheme.colorScheme.onSurface)
            }
            Switch(
                checked = isDarkTheme,
                onCheckedChange = { onThemeToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = MaterialTheme.colorScheme.surface,
                    uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    uncheckedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                )
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 8.dp))

        Text("Downloads", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text("Select Resolution", color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Fetch metadata to choose video quality. Turn off for instant 'Best Quality' downloads.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 12.sp, lineHeight = 16.sp)
            }
            Switch(
                checked = isResolutionSelectionEnabled,
                onCheckedChange = { onResolutionSelectionChange(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = MaterialTheme.colorScheme.surface,
                    uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    uncheckedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                )
            )
        }

        var isBypassSslEnabled by remember { mutableStateOf(permissionManager.isBypassSslEnabled()) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text("Bypass SSL Validation", color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Skips certificate checks for faster extraction speed.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 12.sp, lineHeight = 16.sp)
            }
            Switch(
                checked = isBypassSslEnabled,
                onCheckedChange = { enabled ->
                    isBypassSslEnabled = enabled
                    permissionManager.setBypassSslEnabled(enabled)
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = MaterialTheme.colorScheme.surface,
                    uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    uncheckedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                )
            )
        }

        var isBypassExtractorEnabled by remember { mutableStateOf(permissionManager.isBypassExtractorEnabled()) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text("Direct Link Mode", color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Bypasses deep extractor regex checks for instant link parsing.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 12.sp, lineHeight = 16.sp)
            }
            Switch(
                checked = isBypassExtractorEnabled,
                onCheckedChange = { enabled ->
                    isBypassExtractorEnabled = enabled
                    permissionManager.setBypassExtractorEnabled(enabled)
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = MaterialTheme.colorScheme.surface,
                    uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    uncheckedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                )
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 8.dp))

        Text("Engine Version", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Settings, contentDescription = "Engine", tint = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                val currentVersion = try { com.yausername.youtubedl_android.YoutubeDL.getInstance().version(context) } catch(e: Exception) { "Unknown" }
                Text("yt-dlp Engine", color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Version: $currentVersion", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 12.sp, lineHeight = 16.sp)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Button(
                onClick = { viewModel.checkForEngineUpdate(context, forceCheck = true) }, 
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            ) { Text("Check", fontWeight = FontWeight.Bold) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 8.dp))
        
        Text("Account Cookies & Web Browser", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(4.dp))
        Text("Sign into any video website via In-App Browser to bypass bot checks, age limits, and private video restrictions.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 12.sp, lineHeight = 16.sp)
        Spacer(modifier = Modifier.height(8.dp))

        val savedDomains = remember(cookieRefreshTrigger) {
            (context.applicationContext as com.videofetcher.VideoFetcherApp).container.cookieManager.getAllSavedCookieDomains(context)
        }

        // Row 1: In-App Browser
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(painterResource(id = R.drawable.ic_browser), contentDescription = "Browser", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text("In-App Browser", color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Browse & sign into any platform", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 12.sp, lineHeight = 16.sp)
            }
            Button(
                onClick = { onOpenBrowser("https://www.google.com") },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            ) { Text("Open", fontWeight = FontWeight.Bold) }
        }

        // Row 2: Cookie Manager
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(painterResource(id = R.drawable.ic_cookie), contentDescription = "Saved Cookies", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text("Saved Cookies", color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(4.dp))
                Text("${savedDomains.size} websites authenticated", color = if (savedDomains.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 12.sp, lineHeight = 16.sp)
            }
            Button(
                onClick = onOpenCookieManager,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            ) { Text("Manage", fontWeight = FontWeight.Bold) }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 8.dp))
        
        Text("Storage & Cache", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(painterResource(id = R.drawable.ic_folder), contentDescription = "Folder", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Download Location", color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(4.dp))
                Text(currentPath, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                
                val defaultPath = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "VideoFetcher").absolutePath
                if (currentPath != defaultPath) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Reset to Default",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            permissionManager.resetToDefaultFolder()
                            currentPath = permissionManager.getCustomDownloadFolderPath()
                            Toast.makeText(context, "Restored to default folder", Toast.LENGTH_SHORT).show()
                            viewModel.fetchDownloadedFiles(context)
                        }.padding(vertical = 4.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Button(
                onClick = { folderPickerLauncher.launch(null) }, 
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) { Text("Change", fontWeight = FontWeight.Bold) }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Delete, contentDescription = "Clear Cache", tint = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Clear Thumbnail", color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Frees up storage space", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 12.sp, lineHeight = 16.sp)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Button(
                onClick = { viewModel.clearThumbnailCache(context) }, 
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            ) { Text("Clear", fontWeight = FontWeight.Bold) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onAboutClick() }
                .padding(vertical = 12.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Info, contentDescription = "About", tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("About this app", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(2.dp))
                Text("Privacy, Community, and Contact", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 12.sp)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
        
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun VideoThumbnailBox(
    imageData: Any?,
    isAudio: Boolean = false,
    size: Dp = 80.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
        contentAlignment = Alignment.Center
    ) {
        if (isAudio) {
            Icon(
                painter = painterResource(id = R.drawable.ic_earphone),
                contentDescription = "No Thumbnail",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.size((size.value * 0.4f).dp)
            )
        } else {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "No Thumbnail",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.size((size.value * 0.4f).dp)
            )
        }

        val hasData = when (imageData) {
            is String -> imageData.isNotBlank()
            is Uri -> imageData != Uri.EMPTY
            else -> imageData != null
        }

        if (hasData) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageData)
                    .crossfade(true)
                    .build(),
                contentDescription = "Video Thumbnail",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun ActiveDownloadCard(downloadState: DownloadState, url: String, viewModel: DownloaderViewModel, context: android.content.Context) {
    val downloadThumbnails by (androidx.compose.ui.platform.LocalContext.current.applicationContext as VideoFetcherApp).container.downloadManager.downloadThumbnails.collectAsState()
    val thumbnailUrl = downloadThumbnails[url]

    val isFinished = downloadState is DownloadState.Success || downloadState is DownloadState.Error || downloadState is DownloadState.Cancelled
    val isDownloading = downloadState is DownloadState.Downloading

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VideoThumbnailBox(
            imageData = thumbnailUrl,
            isAudio = false,
            size = 80.dp
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            val (titleText, titleColor) = when (downloadState) {
                is DownloadState.Queued -> "Queued" to MaterialTheme.colorScheme.onSurface
                is DownloadState.Downloading -> "Downloading..." to MaterialTheme.colorScheme.primary
                is DownloadState.Success -> "Completed" to MaterialTheme.colorScheme.onSurface
                is DownloadState.Error -> "Failed" to MaterialTheme.colorScheme.error
                is DownloadState.Cancelled -> "Cancelled" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            }

            Text(
                text = titleText,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                color = titleColor
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = url,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )

            if (isDownloading) {
                val state = downloadState as DownloadState.Downloading
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.status,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            } else if (downloadState is DownloadState.Error) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = downloadState.message,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Tip: Try toggling VPN ON/OFF or Wi-Fi/Data if download fails.",
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (isFinished) {
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = { viewModel.resetState(url) }) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        } else {
            Spacer(modifier = Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isDownloading) {
                    IconButton(onClick = { viewModel.pauseDownload(context.applicationContext, url) }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_pause),
                            contentDescription = "Pause",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(onClick = { viewModel.cancelDownload(context.applicationContext, url) }) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp)
                .verticalScroll(scrollState)
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "VideoFetcher",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "VideoFetcher was built by a small, dedicated team for the community of privacy seekers. In a world full of tracking and subscriptions, we believe tools like this should be free, safe, and truly yours.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(24.dp))

            ExpandableCautionItem()

            AboutFeatureItem(
                icon = { Icon(Icons.Filled.Lock, contentDescription = "Privacy", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
                title = "100% Private & Local",
                description = "No cookies. No tracking. No hidden web servers. Every download is processed entirely on your device."
            )
            
            AboutFeatureItem(
                icon = { Icon(painterResource(id = R.drawable.ic_coffee), contentDescription = "Coffee", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
                title = "Community Supported",
                description = "We made this app completely free and ad-free. If it has made your life easier, please consider buying us a coffee!",
            )
            
            AboutFeatureItem(
                icon = { Icon(painterResource(id = R.drawable.ic_rocket), contentDescription = "Hire Us", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
                title = "Hire Us",
                description = "Love the smooth experience? We are available for freelance work! Let our team build your next app idea.",
                onClick = { uriHandler.openUri("https://teleg.one/Tom_lit") } 
            )
            
            AboutFeatureItem(
                icon = { Icon(painterResource(id = R.drawable.ic_telegram), contentDescription = "Telegram", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
                title = "Get in Touch",
                description = "Whether you have feedback, found a bug, or want to discuss a project, let's chat directly on Telegram.",
                onClick = { uriHandler.openUri("https://teleg.one/Tom_lit") }
            )

            Spacer(modifier = Modifier.height(32.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                "VideoFetcher is made possible thanks to incredible open-source projects like YoutubeDL and FFmpeg.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                fontSize = 11.sp,
                lineHeight = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            Text("VideoFetcher v1.0.0", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.align(Alignment.CenterHorizontally), fontSize = 12.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Made with ❤️", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.align(Alignment.CenterHorizontally), fontSize = 12.sp)
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun AboutFeatureItem(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    onClick: (() -> Unit)? = null
) {
    val modifier = if (onClick != null) {
        Modifier.clickable { onClick() }.padding(vertical = 12.dp)
    } else {
        Modifier.padding(vertical = 12.dp)
    }

    Row(modifier = Modifier.fillMaxWidth().then(modifier), verticalAlignment = Alignment.Top) {
        Box(modifier = Modifier.padding(top = 2.dp)) {
            icon()
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(description, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun ExpandableCautionItem() {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(modifier = Modifier.padding(top = 2.dp)) {
            Icon(
                painter = painterResource(id = R.drawable.ic_warning),
                contentDescription = "Caution",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Caution About Cookies",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (expanded) {
                    "Using account cookies is entirely at your own risk. When cookies are attached, yt-dlp mimics your personal digital identity during requests. Social platforms (especially Meta / Facebook) actively detect automated traffic — testing showed Facebook issuing \"Automation Detected\" warnings within 3 to 4 days, which can lead to account bans. Please use cookies with caution and delete them when no longer needed."
                } else {
                    "Tap to read important account risk details regarding Meta & cookies..."
                },
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
fun DownloadedVideoCard(
    file: DownloadedFileDetails,
    viewModel: DownloaderViewModel,
    context: android.content.Context,
    onDelete: (DownloadedFileDetails) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isAudio = file.isAudio
    val onCardClick = remember(file, context) { { viewModel.playVideo(context, file) } }

        Row(
            modifier = Modifier
                .clickable(onClick = onCardClick)
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VideoThumbnailBox(
                imageData = file.thumbnailUriStr.ifEmpty { null },
                isAudio = isAudio,
                size = 80.dp
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = file.signature,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
                Text(
                    text = "${file.duration} | ${file.size}",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { 
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Share, contentDescription = "Share", modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Share") 
                            }
                        },
                        onClick = { viewModel.shareVideo(context, file); menuExpanded = false }
                    )
                    DropdownMenuItem(
                        text = { 
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Delete", color = MaterialTheme.colorScheme.error) 
                            }
                        },
                        onClick = { 
                            menuExpanded = false
                            onDelete(file)
                        }
                    )
                }
            }
        }
}

@Composable
fun InitializingCard() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp).alpha(alpha),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_package),
                contentDescription = "Extracting",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text("Preparing engine...", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}


@Composable
fun PausedVideoCard(paused: PausedDownload, onResume: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VideoThumbnailBox(
                imageData = paused.thumbnailUrl.ifBlank { null },
                isAudio = paused.quality.contains("MP3", ignoreCase = true) || paused.quality.contains("M4A", ignoreCase = true),
                size = 80.dp
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Paused (${paused.quality})", fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = paused.url, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { (paused.progress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "${String.format("%.1f", paused.progress)}%", fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onResume) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Resume", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = "Error",
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun EngineUpdateDialog(
    state: EngineUpdateState,
    viewModel: DownloaderViewModel,
    context: android.content.Context
) {
    when (state) {
        is EngineUpdateState.Checking -> {
            AlertDialog(
                onDismissRequest = { },
                title = { Text("Checking for updates...") },
                text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
                confirmButton = { }
            )
        }
        is EngineUpdateState.UpToDate -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissUpdatePrompt(context) },
                title = { Text("Up to Date") },
                text = { Text("Your download engine is already on the latest version.") },
                confirmButton = {
                    Button(onClick = { viewModel.dismissUpdatePrompt(context) }) {
                        Text("OK")
                    }
                }
            )
        }
        is EngineUpdateState.UpdateAvailable -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissUpdatePrompt(context) },
                title = { Text("Engine Update Available") },
                text = { Text("A new version of the download engine (${state.version}) is available. Would you like to update now?") },
                confirmButton = {
                    Button(onClick = { viewModel.updateEngine(context) }) {
                        Text("Update")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissUpdatePrompt(context) }) {
                        Text("Later")
                    }
                }
            )
        }
        is EngineUpdateState.Updating -> {
            AlertDialog(
                onDismissRequest = { },
                title = { Text("Updating Engine...") },
                text = { 
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Please do not close the app.")
                    }
                },
                confirmButton = { }
            )
        }
        is EngineUpdateState.Success -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissUpdatePrompt(context) },
                title = { Text("Update Successful") },
                text = { Text("The engine was updated and rebooted successfully.") },
                confirmButton = {
                    Button(onClick = { viewModel.dismissUpdatePrompt(context) }) {
                        Text("OK")
                    }
                }
            )
        }
        is EngineUpdateState.Error -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissUpdatePrompt(context) },
                title = { Text("Update Failed") },
                text = { Text(state.message) },
                confirmButton = {
                    Button(onClick = { viewModel.dismissUpdatePrompt(context) }) {
                        Text("Close")
                    }
                }
            )
        }
        else -> {}
    }
}