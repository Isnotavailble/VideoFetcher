package com.videofetcher

import android.Manifest
import android.os.Build
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.transform.RoundedCornersTransformation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDownloaderUI(
    viewModel: DownloaderViewModel = viewModel(),
    sharedUrl: String = "",
    isDarkTheme: Boolean,
    onThemeToggle: () -> Unit
) {
    val context = LocalContext.current
    var url by remember { mutableStateOf("") }
    var selectedFormat by remember { mutableStateOf("1080p") }
    val state by viewModel.downloadState.collectAsState()
    val filesListState by viewModel.filesListState.collectAsState()
    val pausedDownloads by viewModel.pausedDownloads.collectAsState()

    // Auto-refresh file list when download succeeds
    LaunchedEffect(state) {
        viewModel.fetchPausedDownloads(context.applicationContext)
        if (state is DownloadState.Success) {
            viewModel.fetchDownloadedFiles(context.applicationContext)
        }
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
        // Trigger the popup
        permissionLauncher.launch(permissions)
    }

    val isReady = state !is DownloadState.Initializing

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        // --- HEADER WITH THEME TOGGLE ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "VIDEO\nFETCHER",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 32.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            IconButton(onClick = onThemeToggle) {
                Icon(
                    painter = painterResource(id = if (isDarkTheme) R.drawable.ic_light_mode else R.drawable.ic_dark_mode),
                    contentDescription = "Switch Theme",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- RESTORED EXACT URL INPUT ---
        Text("Video URL", fontWeight = FontWeight.Bold, color = if (isReady) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(8.dp))
        
        val inputContainerColor = if (isReady) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
        
        OutlinedTextField(
            value = url,
            onValueChange = { 
                url = it
                if (state is DownloadState.Error || state is DownloadState.Cancelled) viewModel.resetState()
            },
            placeholder = { Text("https://...", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) },
            textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = isReady,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = inputContainerColor,
                unfocusedContainerColor = inputContainerColor,
                disabledContainerColor = inputContainerColor,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent
            ),
            shape = RoundedCornerShape(8.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // --- RESTORED EXACT FORMAT SELECTION ---
        Text("Format Selection", fontWeight = FontWeight.Bold, color = if (isReady) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("1080p", "720p", "480p").forEach { format ->
                val isSelected = selectedFormat == format
                Surface(
                    onClick = { selectedFormat = format },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected && isReady) MaterialTheme.colorScheme.primary else if (!isReady) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, if (!isReady) Color.Transparent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)),
                    modifier = Modifier.weight(1f),
                    enabled = isReady
                ) {
                    Text(
                        text = format,
                        color = if (isSelected && isReady) MaterialTheme.colorScheme.onPrimary else if (!isReady) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- LAZY COLUMN PLACED IN THE EMPTY MIDDLE SPACE ---
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            if (state is DownloadState.Downloading) {
                item {
                    DownloadingVideoCard(state as DownloadState.Downloading)
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
            
            if (state is DownloadState.Initializing) {
                item {
                    InitializingCard()
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            if (state is DownloadState.Success || state is DownloadState.Error || state is DownloadState.Cancelled) {
                item {
                    StatusCard(state)
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            if (pausedDownloads.isNotEmpty()) {
                item {
                    Text(
                        text = "Paused Videos",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                    )
                }
                items(pausedDownloads) { paused ->
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
                        item {
                            Text(
                                text = "Downloaded Videos",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                            )
                        }
                    }
                    items(filesList.files) { file ->
                        DownloadedVideoCard(file)
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
                is FilesListState.Error -> {
                    item {
                        ErrorCard(filesList.message)
                    }
                }
                else -> {}
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- RESTORED EXACT ACTION BUTTONS ---
        if (state is DownloadState.Downloading) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = { viewModel.pauseDownload(context.applicationContext) },
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("PAUSE", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                }
                
                Button(
                    onClick = { viewModel.cancelDownload(context.applicationContext) },
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = "Cancel")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("CANCEL", fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Button(
                onClick = { viewModel.startDownload(url, selectedFormat, context.applicationContext) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                ),
                shape = RoundedCornerShape(8.dp),
                enabled = url.isNotBlank() && isReady
            ) {
                Icon(painter = painterResource(id = R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "DOWNLOAD VIDEO",
                    fontWeight = FontWeight.Bold, fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
fun DownloadingVideoCard(downloadState: DownloadState.Downloading) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = downloadState.status,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = downloadState.progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        }
    }
}

@Composable
fun DownloadedVideoCard(file: DownloadedFileDetails) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (file.thumbnailUri != Uri.EMPTY) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(file.thumbnailUri)
                        .transformations(RoundedCornersTransformation(16f))
                        .build(),
                    contentDescription = "Video Thumbnail",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "No Thumbnail",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 2,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = file.signature,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${file.duration} | ${file.size}",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    fontSize = 12.sp
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
fun StatusCard(state: DownloadState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            when (state) {
                is DownloadState.Success -> {
                    Text(
                        (state as DownloadState.Success).fileName,
                        color = Color(0xFF00C853),
                        fontWeight = FontWeight.Bold
                    )
                }
                is DownloadState.Cancelled -> {
                    Text(
                        "Download Cancelled",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        fontWeight = FontWeight.Bold
                    )
                }
                is DownloadState.Error -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = "Error",
                            tint = Color.Red
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            (state as DownloadState.Error).message,
                            color = Color.Red,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                else -> {}
            }
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
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Video (${paused.quality})", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = paused.url,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = (paused.progress / 100f).coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            IconButton(onClick = onResume) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Resume", tint = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = Color.Red)
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