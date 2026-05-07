package com.videofetcher

import android.Manifest
import android.os.Build
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import coil.transform.RoundedCornersTransformation
import kotlinx.coroutines.delay

enum class AppTab { HOME, FILES, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDownloaderUI(
    viewModel: DownloaderViewModel = viewModel(),
    sharedUrl: String = "",
    isDarkTheme: Boolean,
    onThemeToggle: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var url by remember { mutableStateOf("") }
    var selectedFormat by remember { mutableStateOf("1080p") }
    val engineState by viewModel.engineState.collectAsState()
    val activeDownloads by viewModel.activeDownloads.collectAsState()
    val filesListState by viewModel.filesListState.collectAsState()
    val pausedDownloads by viewModel.pausedDownloads.collectAsState()

    var currentTab by remember { mutableStateOf(AppTab.HOME) }

    // Only refresh when the number of items or successes changes, avoiding progress-tick loops
    val successCount = activeDownloads.values.count { it is DownloadState.Success }
    val activeCount = activeDownloads.size
    
    LaunchedEffect(successCount, activeCount) {
        viewModel.fetchPausedDownloads(context.applicationContext)
        viewModel.fetchDownloadedFiles(context.applicationContext)
    }

    // Update URL field automatically when intent brings a new link
    LaunchedEffect(sharedUrl) {
        if (sharedUrl.isNotEmpty()) {
            url = sharedUrl
        }
    }

    // Permission Launcher to request access to existing files
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Once the user answers the permission prompt, fetch the files
        viewModel.fetchDownloadedFiles(context.applicationContext)
    }

    LaunchedEffect(Unit) {
        viewModel.initializeEngine(context.applicationContext)
        
        // Determine which permissions to ask for based on Android version
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
        
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (missingPermissions.isNotEmpty()) {
            delay(300) // Ensure Activity is fully resumed before requesting
            permissionLauncher.launch(missingPermissions)
        } else {
            viewModel.fetchDownloadedFiles(context.applicationContext)
        }
    }

    val isReady = engineState is EngineState.Idle

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
                    onUrlChange = { url = it },
                    selectedFormat = selectedFormat,
                    onFormatChange = { selectedFormat = it },
                    engineState = engineState,
                    activeDownloads = activeDownloads,
                    isReady = isReady,
                    viewModel = viewModel,
                    clipboardManager = clipboardManager,
                    context = context
                )
                AppTab.FILES -> FilesContent(pausedDownloads, filesListState, viewModel, context)
                AppTab.SETTINGS -> SettingsContent(isDarkTheme, onThemeToggle, viewModel, context)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    url: String,
    onUrlChange: (String) -> Unit,
    selectedFormat: String,
    onFormatChange: (String) -> Unit,
    engineState: EngineState,
    activeDownloads: Map<String, DownloadState>,
    isReady: Boolean,
    viewModel: DownloaderViewModel,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
    context: android.content.Context
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "VIDEO\nFETCHER",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = 32.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Spacer(modifier = Modifier.height(24.dp))

        Text("Video URL", fontWeight = FontWeight.Bold, color = if (isReady) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(8.dp))
        
        val inputContainerColor = if (isReady) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
        
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            placeholder = { Text("https://...") },
            trailingIcon = {
                if (url.isEmpty()) {
                    IconButton(onClick = {
                        val clipText = clipboardManager.getText()?.text
                        if (!clipText.isNullOrBlank()) onUrlChange(clipText)
                    }) {
                        Icon(painterResource(id = R.drawable.ic_content_paste), contentDescription = "Paste URL", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    }
                } else {
                    IconButton(onClick = { onUrlChange("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear URL", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                }
            },
            textStyle = LocalTextStyle.current,
            modifier = Modifier.fillMaxWidth().background(inputContainerColor, RoundedCornerShape(8.dp)),
            singleLine = true,
            enabled = isReady,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, disabledBorderColor = Color.Transparent
            ),
            shape = RoundedCornerShape(8.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text("Format Selection", fontWeight = FontWeight.Bold, color = if (isReady) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("1080p", "720p", "480p").forEach { format ->
                val isSelected = selectedFormat == format
                Surface(
                    onClick = { onFormatChange(format) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected && isReady) MaterialTheme.colorScheme.primary else if (!isReady) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, if (!isReady) Color.Transparent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)),
                    modifier = Modifier.weight(1f),
                    enabled = isReady
                ) {
                    Text(
                        text = format,
                        color = if (isSelected && isReady) MaterialTheme.colorScheme.onPrimary else if (!isReady) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (engineState is EngineState.Initializing) {
                item { InitializingCard(); Spacer(modifier = Modifier.height(16.dp)) }
            } else if (engineState is EngineState.Error) {
                val err = engineState as EngineState.Error
                item { ErrorCard(err.message); Spacer(modifier = Modifier.height(16.dp)) }
            }

            items(activeDownloads.entries.toList()) { (downloadUrl, downloadState) ->
                ActiveDownloadCard(
                    downloadState = downloadState,
                    url = downloadUrl,
                    viewModel = viewModel,
                    context = context
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { 
                viewModel.startDownload(url, selectedFormat, context.applicationContext)
                onUrlChange("") // Clean input on success to be ready for next URL
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            ),
            shape = RoundedCornerShape(8.dp), enabled = url.isNotBlank() && isReady
        ) {
            Icon(painter = painterResource(id = R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("DOWNLOAD VIDEO", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesContent(
    pausedDownloads: List<PausedDownload>,
    filesListState: FilesListState,
    viewModel: DownloaderViewModel,
    context: android.content.Context
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text("My Files", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(24.dp))
        
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (pausedDownloads.isNotEmpty()) {
                item { Text("Paused Videos", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(bottom = 8.dp)) }
                items(items = pausedDownloads, key = { it.url }) { paused ->
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
                    if (filesList.files.isNotEmpty()) {
                        stickyHeader {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.background)
                                    .padding(vertical = 8.dp)
                            ) {
                                Text("Downloaded Videos", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    } else if (pausedDownloads.isEmpty()) {
                        item { Text("No downloads yet.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) }
                    }
                    items(items = filesList.files, key = { it.path }) { file ->
                        DownloadedVideoCard(
                            file = file,
                            viewModel = viewModel,
                            context = context
                        )
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
    viewModel: DownloaderViewModel,
    context: android.content.Context
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(24.dp))

        Text("Appearance", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
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
        
        Text("Storage & Cache", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Delete, contentDescription = "Clear Cache", tint = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Clear Thumbnail", color = MaterialTheme.colorScheme.onSurface)
                Text("Frees up storage space", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Button(
                onClick = { viewModel.clearThumbnailCache(context) }, 
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            ) { Text("CLEAR") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 8.dp))

        Spacer(modifier = Modifier.weight(1f))
        Text("VideoFetcher v1.0.0", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.align(Alignment.CenterHorizontally), fontSize = 12.sp)
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun ActiveDownloadCard(downloadState: DownloadState, url: String, viewModel: DownloaderViewModel, context: android.content.Context) {
    val isFinished = downloadState is DownloadState.Success || downloadState is DownloadState.Error || downloadState is DownloadState.Cancelled
    val isDownloading = downloadState is DownloadState.Downloading

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = if (isFinished) 16.dp else 0.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Thumbnail Placeholder",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    val (titleText, titleColor) = when (downloadState) {
                        is DownloadState.Queued -> "Queued" to MaterialTheme.colorScheme.onSurface
                        is DownloadState.Downloading -> "Downloading..." to MaterialTheme.colorScheme.primary
                        is DownloadState.Success -> "Completed" to Color(0xFF00C853) // Green
                        is DownloadState.Error -> "Failed" to Color.Red
                        is DownloadState.Cancelled -> "Cancelled" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    }
                    
                    Text(text = titleText, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = titleColor)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = url, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    
                    if (isDownloading) {
                        val state = downloadState as DownloadState.Downloading
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = state.status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    } else if (downloadState is DownloadState.Error) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = downloadState.message, fontSize = 12.sp, color = Color.Red.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                
                if (isFinished) {
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = { viewModel.resetState(url) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            
            if (!isFinished) {
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    if (isDownloading) {
                        TextButton(onClick = { viewModel.pauseDownload(context.applicationContext, url) }) { Text("PAUSE", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) }
                    }
                    TextButton(onClick = { viewModel.cancelDownload(context.applicationContext, url) }) { Text("CANCEL", fontWeight = FontWeight.Bold, color = Color.Red) }
                }
            }
        }
    }
}

@Composable
fun DownloadedVideoCard(
    file: DownloadedFileDetails,
    viewModel: DownloaderViewModel,
    context: android.content.Context
) {
    var menuExpanded by remember { mutableStateOf(false) }

        Row(
            modifier = Modifier
                .clickable { viewModel.playVideo(context, file) }
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.Center) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(if (file.thumbnailUri == Uri.EMPTY) null else file.thumbnailUri)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Video Thumbnail",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    loading = {
                        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "No Thumbnail", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(32.dp))
                        }
                    },
                    error = {
                        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "No Thumbnail", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(32.dp))
                        }
                    }
                )
            }

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
                }
            }
        }
}

@Composable
fun InitializingCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Extracting libraries...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
    }
}


@Composable
fun PausedVideoCard(paused: PausedDownload, onResume: () -> Unit, onCancel: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Paused",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Paused (${paused.quality})", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = paused.url, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (paused.progress / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "${String.format("%.1f", paused.progress)}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
            }
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                TextButton(onClick = onResume) {
                    Text("RESUME", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = onCancel) {
                    Text("CANCEL", fontWeight = FontWeight.Bold, color = Color.Red)
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
                    tint = Color.Red
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(message, color = Color.Red, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}