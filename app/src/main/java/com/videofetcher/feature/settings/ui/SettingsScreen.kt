package com.videofetcher.feature.settings.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.videofetcher.R
import com.videofetcher.feature.settings.viewmodel.EngineUpdateState
import com.videofetcher.feature.settings.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    isDarkTheme: Boolean, 
    onThemeToggle: () -> Unit,
    viewModel: SettingsViewModel,
    onPathChange: () -> Unit,
    onOpenBrowser: (String) -> Unit,
    onOpenCookieManager: () -> Unit,
    onAboutClick: () -> Unit,
    cookieRefreshTrigger: Int
) {
    val context = LocalContext.current
    val permissionManager = remember { (context.applicationContext as com.videofetcher.VideoFetcherApp).container.permissionManager }
    val engineUpdateState by viewModel.engineUpdateState.collectAsState()
    
    var isResolutionSelectionEnabled by remember { mutableStateOf(viewModel.isResolutionSelectionEnabled()) }
    var isBypassSslEnabled by remember { mutableStateOf(viewModel.isBypassSslEnabled()) }
    var isBypassExtractorEnabled by remember { mutableStateOf(viewModel.isBypassExtractorEnabled()) }
    
    val onResolutionSelectionChange = { enabled: Boolean ->
        isResolutionSelectionEnabled = enabled
        viewModel.setResolutionSelectionEnabled(enabled)
    }

    val scrollState = rememberScrollState()
    var currentPath by remember { mutableStateOf(permissionManager.getCustomDownloadFolderPath()) }
    
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val success = permissionManager.saveCustomDownloadFolder(uri)
            if (success) {
                currentPath = permissionManager.getCustomDownloadFolderPath()
                Toast.makeText(context, "Download folder updated!", Toast.LENGTH_SHORT).show()
                onPathChange()
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
                            onPathChange()
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
