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
import androidx.compose.ui.text.style.TextAlign
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
    var selectedFormat by remember { mutableStateOf("1080p") }
    val state by viewModel.downloadState.collectAsState()
    val filesListState by viewModel.filesListState.collectAsState()

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
            .background(Color.White)
            .padding(24.dp)
    ) {
        // --- RESTORED EXACT HEADER ---
        Text(
            text = "VIDEO\nFETCHER",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = 32.sp,
            color = Color.Black
        )

        Spacer(modifier = Modifier.height(40.dp))

        // --- RESTORED EXACT URL INPUT ---
        Text("Video URL", fontWeight = FontWeight.Bold, color = if (isReady) Color.Black else Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))
        
        val inputContainerColor = if (isReady) MaterialTheme.colorScheme.secondaryContainer else Color(0xFFEEEEEE)
        
        OutlinedTextField(
            value = url,
            onValueChange = { 
                url = it
                if (state is DownloadState.Error || state is DownloadState.Cancelled) viewModel.resetState()
            },
            placeholder = { Text("https://...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = isReady,
            colors = TextFieldDefaults.outlinedTextFieldColors(
                containerColor = inputContainerColor,
                focusedBorderColor = Color.Black,
                unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent
            ),
            shape = RoundedCornerShape(8.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // --- RESTORED EXACT FORMAT SELECTION ---
        Text("Format Selection", fontWeight = FontWeight.Bold, color = if (isReady) Color.Black else Color.Gray)
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
                    color = if (isSelected && isReady) Color.Black else if (!isReady) Color(0xFFEEEEEE) else Color.White,
                    border = BorderStroke(1.dp, if (!isReady) Color.Transparent else Color.Black),
                    modifier = Modifier.weight(1f),
                    enabled = isReady
                ) {
                    Text(
                        text = format,
                        color = if (isSelected && isReady) Color.White else if (!isReady) Color.Gray else Color.Black,
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
                    onClick = { viewModel.cancelDownload() },
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    border = BorderStroke(2.dp, Color.Black),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("PAUSE", fontWeight = FontWeight.Bold)
                }
                
                Button(
                    onClick = { viewModel.cancelDownload() },
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("CANCEL", fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Button(
                onClick = { viewModel.startDownload(url, selectedFormat, context.applicationContext) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black,
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFFCCCCCC),
                    disabledContentColor = Color.DarkGray
                ),
                shape = RoundedCornerShape(8.dp),
                enabled = url.isNotBlank() && isReady
            ) {
                Text(
                    text = "DOWNLOAD VIDEO",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
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
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFAFA)),
        border = BorderStroke(1.dp, Color(0xFFEEEEEE))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = downloadState.status,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = Color.Black
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
        }
    }
}

@Composable
fun DownloadedVideoCard(file: DownloadedFileDetails) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFEEEEEE))
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
                        .background(Color.LightGray),
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
                    maxLines = 2,
                    color = Color.Black
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
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFAFA))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.Black)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Extracting libraries...", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        }
    }
}

@Composable
fun StatusCard(state: DownloadState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFAFA))
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
                        color = Color.Gray,
                        fontWeight = FontWeight.Bold
                    )
                }
                is DownloadState.Error -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Warning,
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
fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = "Error",
                    tint = Color.Red
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(message, color = Color.Red, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}