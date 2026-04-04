package com.videofetcher

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
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
    sharedUrl: String = ""
) {
    val context = LocalContext.current
    var url by remember { mutableStateOf("") }
    var selectedQuality by remember { mutableStateOf("1080p") }
    val state by viewModel.downloadState.collectAsState()
    val filesListState by viewModel.filesListState.collectAsState()

    LaunchedEffect(sharedUrl) {
        if (sharedUrl.isNotEmpty()) {
            url = sharedUrl
        }
    }

    LaunchedEffect(Unit) {
        viewModel.initializeEngine(context.applicationContext)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    if (state is DownloadState.Error || state is DownloadState.Cancelled) viewModel.resetState()
                },
                label = { Text("Video URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = state is DownloadState.Idle || state is DownloadState.Success || state is DownloadState.Error || state is DownloadState.Cancelled
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                listOf("1080p", "720p", "480p").forEach { quality ->
                    FilterChip(
                        selected = selectedQuality == quality,
                        onClick = { selectedQuality = quality },
                        label = { Text(quality) },
                        enabled = state is DownloadState.Idle || state is DownloadState.Success || state is DownloadState.Error || state is DownloadState.Cancelled
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { viewModel.startDownload(url, selectedQuality) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = (state is DownloadState.Idle || state is DownloadState.Success || state is DownloadState.Error || state is DownloadState.Cancelled) && url.isNotBlank()
            ) {
                Text(
                    when (state) {
                        is DownloadState.Downloading -> "DOWNLOADING..."
                        else -> "DOWNLOAD"
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        
        Divider(color = Color.LightGray, thickness = 1.dp)
        
        Spacer(modifier = Modifier.height(24.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            if (state is DownloadState.Downloading) {
                item {
                    DownloadingVideoCard(
                        downloadState = state as DownloadState.Downloading,
                        onCancelClick = { viewModel.cancelDownload() }
                    )
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

            when (val filesList = filesListState) {
                is FilesListState.Success -> {
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
                is FilesListState.Fetching -> {
                    // Show a simple placeholder or nothing while fetching files
                }
                else -> {}
            }
        }
    }
}

@Composable
fun DownloadingVideoCard(
    downloadState: DownloadState.Downloading,
    onCancelClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = downloadState.status,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = downloadState.progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = Color.Black,
                trackColor = Color.LightGray
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onCancelClick,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Cancel")
                Spacer(modifier = Modifier.width(8.dp))
                Text("CANCEL", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun DownloadedVideoCard(file: DownloadedFileDetails) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(8.dp)
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
                        .background(Color.Gray),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_menu_slideshow),
                        contentDescription = "No Thumbnail",
                        tint = Color.White,
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
                    maxLines = 2
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = file.signature,
                    color = Color.DarkGray,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${file.duration} | ${file.size}",
                    color = Color.Gray,
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
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.Black)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Extracting libraries...", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun StatusCard(state: DownloadState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            when (state) {
                is DownloadState.Success -> {
                    Text(
                        (state as DownloadState.Success).fileName,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                is DownloadState.Cancelled -> {
                    Text(
                        "Download Cancelled",
                        color = Color.Gray,
                        fontWeight = FontWeight.Bold
                    )
                }
                is DownloadState.Error -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            (state as DownloadState.Error).message,
                            color = MaterialTheme.colorScheme.error,
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
fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = "Error",
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}