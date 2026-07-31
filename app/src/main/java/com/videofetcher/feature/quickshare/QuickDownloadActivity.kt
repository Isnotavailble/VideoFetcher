package com.videofetcher.feature.quickshare

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.videofetcher.AppViewModelFactory
import com.videofetcher.VideoFetcherApp
import com.videofetcher.feature.quickshare.viewmodel.QuickShareViewModel.VideoInfoState
import com.videofetcher.feature.quickshare.ui.QuickShareScreen
import com.videofetcher.feature.quickshare.viewmodel.QuickShareViewModel
import com.videofetcher.feature.settings.viewmodel.SettingsViewModel
import com.videofetcher.manager.SettingsManager
import com.videofetcher.theme.VideoFetcherTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.videofetcher.feature.settings.ui.EngineUpdateDialog

@OptIn(ExperimentalMaterial3Api::class)
class QuickDownloadActivity : ComponentActivity() {
    private lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        settingsManager = SettingsManager(applicationContext)

        var sharedUrl = ""
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            val urlRegex = """(?i)\b((?:https?://|www\d{0,3}[.]|[a-z0-9.\-]+[.][a-z]{2,4}/)(?:[^\s()<>]+|\(([^\s()<>]+|(\([^\s()<>]+\)))*\))+(?:\(([^\s()<>]+|(\([^\s()<>]+\)))*\)|[^\s`!()\[\]{};:'".,<>?«»“”‘’]))""".toRegex()
            val match = urlRegex.find(sharedText)
            sharedUrl = match?.value ?: sharedText
        }

        if (sharedUrl.isBlank()) {
            finish()
            return
        }

        val initialDarkTheme = runBlocking { settingsManager.isDark.first() }

        setContent {
            val isDarkTheme by settingsManager.isDark.collectAsState(initial = initialDarkTheme)

            VideoFetcherTheme(darkTheme = isDarkTheme) {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                val scope = rememberCoroutineScope()
                val context = LocalContext.current

                val permissionManager = remember { (context.applicationContext as VideoFetcherApp).container.permissionManager }
                val isResolutionSelectionEnabled = remember { permissionManager.isResolutionSelectionEnabled() }
                
                val factory = AppViewModelFactory((context.applicationContext as VideoFetcherApp).container)
                val viewModel: QuickShareViewModel = viewModel(factory = factory)
                val settingsViewModel: SettingsViewModel = viewModel(factory = factory)
                
                val videoInfoState by viewModel.videoInfoState.collectAsState()
                val engineUpdateState by settingsViewModel.engineUpdateState.collectAsState()

                LaunchedEffect(Unit) {
                    settingsViewModel.checkForEngineUpdate(context.applicationContext)
                }

                LaunchedEffect(sharedUrl) {
                    if (isResolutionSelectionEnabled && sharedUrl.isNotBlank()) {
                        viewModel.analyzeUrl(sharedUrl, context.applicationContext)
                    }
                }

                QuickShareScreen(
                    sharedUrl = sharedUrl,
                    sheetState = sheetState,
                    videoInfoState = videoInfoState,
                    isResolutionSelectionEnabled = isResolutionSelectionEnabled,
                    scope = scope,
                    context = context,
                    onFinish = { finish() }
                )

                EngineUpdateDialog(engineUpdateState, settingsViewModel, context)
            }
        }
    }
}
