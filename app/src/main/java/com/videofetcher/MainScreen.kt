package com.videofetcher

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.videofetcher.manager.DownloadManager.EngineState
import com.videofetcher.manager.DownloadManager.DownloadState
import com.videofetcher.feature.cookies.BrowserScreen
import com.videofetcher.feature.cookies.CookieManagementScreen
import com.videofetcher.feature.home.ui.HomeContent
import com.videofetcher.feature.home.viewmodel.HomeViewModel
import com.videofetcher.feature.settings.ui.AboutScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class AppTab { HOME, FILES, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDownloaderUI(
    viewModel: HomeViewModel = viewModel(factory = AppViewModelFactory((LocalContext.current.applicationContext as VideoFetcherApp).container)),
    filesViewModel: com.videofetcher.feature.files.viewmodel.FilesViewModel = viewModel(factory = AppViewModelFactory((LocalContext.current.applicationContext as VideoFetcherApp).container)),
    settingsViewModel: com.videofetcher.feature.settings.viewmodel.SettingsViewModel = viewModel(factory = AppViewModelFactory((LocalContext.current.applicationContext as VideoFetcherApp).container)),
    sharedUrl: String = "",
    isDarkTheme: Boolean,
    onThemeToggle: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
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
    
    val isResolutionSelectionEnabled by settingsViewModel.resolutionSelectionEnabled.collectAsState()

    com.videofetcher.feature.settings.ui.EngineUpdateDialog(
        state = engineUpdateState,
        viewModel = settingsViewModel,
        context = context
    )

    LaunchedEffect(Unit) {
        settingsViewModel.checkForEngineUpdate(context.applicationContext)
    }

    LaunchedEffect(sharedUrl) {
        if (sharedUrl.isNotEmpty() && sharedUrl != url) {
            url = sharedUrl
            viewModel.clearVideoInfo()
        }
    }

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
            filesViewModel.fetchDownloadedFiles()
        }
    }

    val storageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        hasStoragePermission = storagePermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && 
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (hasStoragePermission) {
            filesViewModel.fetchDownloadedFiles()
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
            delay(500)
            storageLauncher.launch(missingStorage)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && 
                   ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            delay(500)
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            filesViewModel.fetchDownloadedFiles()
        }
    }

    val isReady = engineState is EngineState.Idle

    var showAboutScreen by remember { mutableStateOf(false) }
    var activeBrowserUrl by remember { mutableStateOf<String?>(null) }
    var showCookieManager by remember { mutableStateOf(false) }

    if (showAboutScreen) {
        AboutScreen(onBack = { showAboutScreen = false })
    } else if (activeBrowserUrl != null) {
        BrowserScreen(
            initialUrl = activeBrowserUrl!!,
            onBack = { activeBrowserUrl = null }
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
                            indicatorColor = Color.Transparent,
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
                            filesViewModel.fetchDownloadedFiles()
                        },
                        onOpenBrowser = { browserUrl -> activeBrowserUrl = browserUrl },
                        onOpenCookieManager = { showCookieManager = true },
                        onAboutClick = { showAboutScreen = true }
                    )
                }
            }
        }
    }
}
