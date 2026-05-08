package com.videofetcher

import android.Manifest
import android.os.Build
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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

    // Determine which storage permissions to ask for based on Android version
    val storagePermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
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
        viewModel.initializeEngine(context.applicationContext)
        
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
                    context = context,
                    hasStoragePermission = hasStoragePermission,
                    onRequestPermission = requestStoragePermission
                )
                AppTab.FILES -> FilesContent(pausedDownloads, filesListState, viewModel, context, hasStoragePermission, requestStoragePermission)
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
    context: android.content.Context,
    hasStoragePermission: Boolean,
    onRequestPermission: () -> Unit
) {
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
                    IconButton(onClick = {
                        val clipText = clipboardManager.getText()?.text
                        if (!clipText.isNullOrBlank()) onUrlChange(clipText)
                    }) {
                        Icon(painterResource(id = R.drawable.ic_content_paste), contentDescription = "Paste URL", tint = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    IconButton(onClick = { onUrlChange("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear URL", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
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
                disabledBorderColor = Color.Transparent
            ),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("1080p", "720p", "480p").forEach { format ->
                val isSelected = selectedFormat == format
                Surface(
                    onClick = { onFormatChange(format) },
                    shape = RoundedCornerShape(16.dp),
                    color = if (isSelected && isReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                    modifier = Modifier.weight(1f),
                    enabled = isReady
                ) {
                    Text(
                        text = format,
                        color = if (isSelected && isReady) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center, 
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { 
                if (hasStoragePermission) {
                    viewModel.startDownload(url, selectedFormat, context.applicationContext)
                    onUrlChange("") 
                } else {
                    onRequestPermission()
                    Toast.makeText(context, "Storage permission required to download", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary, 
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            ),
            shape = RoundedCornerShape(12.dp), 
            enabled = url.isNotBlank() && isReady
        ) {
            Icon(painter = painterResource(id = R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Download", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "Active Queue",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (engineState is EngineState.Initializing) {
                item { InitializingCard(); Spacer(modifier = Modifier.height(16.dp)) }
            } else if (engineState is EngineState.Error) {
                val err = engineState as EngineState.Error
                item { ErrorCard(err.message); Spacer(modifier = Modifier.height(16.dp)) }
            }

            if (isReady && activeDownloads.isEmpty() && engineState !is EngineState.Initializing && engineState !is EngineState.Error) {
                item {
                    Text(
                        text = "No downloads yet.",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        fontSize = 14.sp,
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

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                val treeDocumentId = DocumentsContract.getTreeDocumentId(uri)
                // Verify they selected our specific folder
                if (treeDocumentId.endsWith("VideoFetcher", ignoreCase = true)) {
                    PermissionManager(context).saveFolderPermission(uri)
                    
                    // Permission saved! Automatically retry the deletion silently
                    fileAwaitingPermission?.let { fileToDelete ->
                        viewModel.deleteVideo(
                            context = context.applicationContext,
                            fileDetails = fileToDelete,
                            onSuccess = {
                                Toast.makeText(context, "Video deleted", Toast.LENGTH_SHORT).show()
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
            text = { Text("To delete videos downloaded from a previous installation, we need access to the VideoFetcher folder.\n\nPlease tap 'Continue', select the 'VideoFetcher' folder, and tap 'Use this folder'.") },
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
        AlertDialog(
            onDismissRequest = { fileToConfirmDelete = null },
            title = { Text("Delete Video", fontWeight = FontWeight.Bold) },
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
                                Toast.makeText(context, "Video deleted", Toast.LENGTH_SHORT).show()
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
                    Text("Delete", color = Color.Red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToConfirmDelete = null }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }

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
                            Text("We need access to your storage to display and manage your downloaded videos.", textAlign = TextAlign.Center, fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer)
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
                            context = context,
                            onDelete = { fileToDelete ->
                                fileToConfirmDelete = fileToDelete
                            }
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
    val permissionManager = remember { PermissionManager(context) }
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

        Spacer(modifier = Modifier.weight(1f))
        Text("VideoFetcher v1.0.0", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.align(Alignment.CenterHorizontally), fontSize = 12.sp)
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun ActiveDownloadCard(downloadState: DownloadState, url: String, viewModel: DownloaderViewModel, context: android.content.Context) {
    val isFinished = downloadState is DownloadState.Success || downloadState is DownloadState.Error || downloadState is DownloadState.Cancelled
    val isDownloading = downloadState is DownloadState.Downloading

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
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
                
                Text(text = titleText, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp, color = titleColor)
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = url, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                
                if (isDownloading) {
                    val state = downloadState as DownloadState.Downloading
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = state.status, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                } else if (downloadState is DownloadState.Error) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = downloadState.message, fontSize = 12.sp, lineHeight = 16.sp, color = Color.Red.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            
            if (isFinished) {
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = { viewModel.resetState(url) }) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onSurface)
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
                        Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = Color.Red)
                    }
                }
            }
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
                    DropdownMenuItem(
                        text = { 
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete", modifier = Modifier.size(20.dp), tint = Color.Red)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Delete", color = Color.Red) 
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
            Box(
                modifier = Modifier
                    .size(80.dp)
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
                    Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = Color.Red)
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