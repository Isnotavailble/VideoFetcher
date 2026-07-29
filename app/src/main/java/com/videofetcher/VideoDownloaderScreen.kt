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
import com.videofetcher.feature.files.ui.ErrorCard
import com.videofetcher.manager.PermissionManager
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.videofetcher.manager.CookieManager
import com.videofetcher.feature.cookies.BrowserScreen
import com.videofetcher.feature.cookies.CookieManagementScreen
import com.videofetcher.feature.settings.ui.AboutScreen
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
    filesViewModel: com.videofetcher.feature.files.viewmodel.FilesViewModel = viewModel(factory = AppViewModelFactory((androidx.compose.ui.platform.LocalContext.current.applicationContext as VideoFetcherApp).container)),
    settingsViewModel: com.videofetcher.feature.settings.viewmodel.SettingsViewModel = viewModel(factory = AppViewModelFactory((androidx.compose.ui.platform.LocalContext.current.applicationContext as VideoFetcherApp).container)),
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
    val filesListState by filesViewModel.filesListState.collectAsState()
    val pausedDownloads by viewModel.pausedDownloads.collectAsState()
    val engineUpdateState by settingsViewModel.engineUpdateState.collectAsState()

    var currentTab by remember { mutableStateOf(AppTab.HOME) }
    
    val permissionManager = remember { (context.applicationContext as com.videofetcher.VideoFetcherApp).container.permissionManager }
    var isResolutionSelectionEnabled by remember { mutableStateOf(permissionManager.isResolutionSelectionEnabled()) }

    // Only refresh when the number of items or successes changes, avoiding progress-tick loops
    val successCount = activeDownloads.values.count { it is DownloadState.Success }
    val activeCount = activeDownloads.size
    val refreshCounter by (context.applicationContext as com.videofetcher.VideoFetcherApp).container.downloadManager.fileRefreshCounter.collectAsState()
    
    LaunchedEffect(successCount, activeCount, refreshCounter) {
        viewModel.fetchPausedDownloads(context.applicationContext)
        filesViewModel.fetchDownloadedFiles(context.applicationContext)
    }

    LaunchedEffect(Unit) {
        settingsViewModel.checkForEngineUpdate(context.applicationContext)
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
            filesViewModel.fetchDownloadedFiles(context.applicationContext)
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
            filesViewModel.fetchDownloadedFiles(context.applicationContext)
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
            filesViewModel.fetchDownloadedFiles(context.applicationContext)
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
                AppTab.FILES -> com.videofetcher.feature.files.ui.FilesScreen(pausedDownloads, filesListState, filesViewModel, context, hasStoragePermission, requestStoragePermission)
                AppTab.SETTINGS -> com.videofetcher.feature.settings.ui.SettingsScreen(
                    isDarkTheme = isDarkTheme,
                    onThemeToggle = onThemeToggle,
                    viewModel = settingsViewModel,
                    onPathChange = { 
                        filesViewModel.fetchDownloadedFiles(context)
                    },
                    onOpenBrowser = { url -> activeBrowserUrl = url },
                    onOpenCookieManager = { showCookieManager = true },
                    onAboutClick = { showAboutScreen = true },
                    cookieRefreshTrigger = cookieRefreshTrigger
                )
            }
        }
    }
    
    EngineUpdateDialog(engineUpdateState, settingsViewModel, context)
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
fun EngineUpdateDialog(
    state: com.videofetcher.feature.settings.viewmodel.EngineUpdateState,
    viewModel: com.videofetcher.feature.settings.viewmodel.SettingsViewModel,
    context: android.content.Context
) {
    when (state) {
        is com.videofetcher.feature.settings.viewmodel.EngineUpdateState.Checking -> {
            AlertDialog(
                onDismissRequest = { },
                title = { Text("Checking for updates...") },
                text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
                confirmButton = { }
            )
        }
        is com.videofetcher.feature.settings.viewmodel.EngineUpdateState.UpToDate -> {
            AlertDialog(
                onDismissRequest = { viewModel.resetEngineUpdateState() },
                title = { Text("Up to Date") },
                text = { Text("Your download engine is already on the latest version.") },
                confirmButton = {
                    Button(onClick = { viewModel.resetEngineUpdateState() }) {
                        Text("OK")
                    }
                }
            )
        }
        is com.videofetcher.feature.settings.viewmodel.EngineUpdateState.UpdateAvailable -> {
            AlertDialog(
                onDismissRequest = { viewModel.resetEngineUpdateState() },
                title = { Text("Engine Update Available") },
                text = { Text("A new version of the download engine (${state.version}) is available. Would you like to update now?") },
                confirmButton = {
                    Button(onClick = { viewModel.updateEngine(context) }) {
                        Text("Update")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.resetEngineUpdateState() }) {
                        Text("Later")
                    }
                }
            )
        }
        is com.videofetcher.feature.settings.viewmodel.EngineUpdateState.Updating -> {
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
        is com.videofetcher.feature.settings.viewmodel.EngineUpdateState.Success -> {
            AlertDialog(
                onDismissRequest = { viewModel.resetEngineUpdateState() },
                title = { Text("Update Successful") },
                text = { Text("The engine was updated and rebooted successfully.") },
                confirmButton = {
                    Button(onClick = { viewModel.resetEngineUpdateState() }) {
                        Text("OK")
                    }
                }
            )
        }
        is com.videofetcher.feature.settings.viewmodel.EngineUpdateState.Error -> {
            AlertDialog(
                onDismissRequest = { viewModel.resetEngineUpdateState() },
                title = { Text("Update Failed") },
                text = { Text(state.message) },
                confirmButton = {
                    Button(onClick = { viewModel.resetEngineUpdateState() }) {
                        Text("Close")
                    }
                }
            )
        }
        else -> {}
    }
}