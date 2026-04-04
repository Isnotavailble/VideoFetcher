package com.videofetcher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDownloaderUI(
    viewModel: DownloaderViewModel = viewModel(),
    initialUrl: String = ""
) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(initialUrl) }
    var selectedFormat by remember { mutableStateOf("1080p") }
    val state by viewModel.downloadState.collectAsState()

    // Update URL if shared intent brings a new one
    LaunchedEffect(initialUrl) {
        if (initialUrl.isNotEmpty()) url = initialUrl
    }

    LaunchedEffect(Unit) {
        viewModel.initializeEngine(context.applicationContext)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(24.dp)
    ) {
        // HEADER
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "YOUTUBE\nDOWNLOADER",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 32.sp,
                color = Color.Black
            )
            // Placeholder for an icon/logo
            Icon(Icons.Default.PlayArrow, contentDescription = "Logo", modifier = Modifier.size(32.dp))
        }

        Spacer(modifier = Modifier.height(32.dp))

        // URL INPUT
        Text("Enter YouTube URL", fontWeight = FontWeight.Bold, color = Color.Black)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { 
                url = it
                if (state is DownloadState.Error || state is DownloadState.Cancelled) viewModel.resetState()
            },
            placeholder = { Text("https://youtube.com/watch?v=...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = TextFieldDefaults.outlinedTextFieldColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                focusedBorderColor = Color.Black,
                unfocusedBorderColor = Color.Transparent
            ),
            shape = RoundedCornerShape(8.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // FORMAT SELECTION
        Text("Format Selection", fontWeight = FontWeight.Bold, color = Color.Black)
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
                    color = if (isSelected) Color.Black else Color.White,
                    border = BorderStroke(1.dp, Color.Black),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = format,
                        color = if (isSelected) Color.White else Color.Black,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // STATUS / PROGRESS AREA
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(2.dp, Color(0xFFEEEEEE), RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFFAFAFA)),
            contentAlignment = Alignment.Center
        ) {
            when (state) {
                is DownloadState.Idle, is DownloadState.Cancelled -> {
                    Text(
                        if (state is DownloadState.Cancelled) "Download Cancelled" else "Ready to download",
                        color = Color.Gray
                    )
                }
                is DownloadState.Downloading -> {
                    val downloadState = state as DownloadState.Downloading
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(downloadState.status, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = downloadState.progress,
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = Color.Black,
                            trackColor = Color.LightGray
                        )
                    }
                }
                is DownloadState.Success -> {
                    Text("Success!", color = Color(0xFF00C853), fontWeight = FontWeight.Bold)
                }
                is DownloadState.Error -> {
                    Text((state as DownloadState.Error).message, color = Color.Red, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
                }
                is DownloadState.Initializing -> {
                    CircularProgressIndicator(color = Color.Black)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ACTION BUTTONS (DOWNLOAD / PAUSE / CANCEL)
        if (state is DownloadState.Downloading) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Pause (Mapped to cancel/interruption for now as true pause requires chunking)
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
                onClick = { viewModel.startDownload(url, selectedFormat) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black,
                    contentColor = Color.White,
                    disabledContainerColor = Color.LightGray
                ),
                shape = RoundedCornerShape(8.dp),
                enabled = url.isNotBlank() && state !is DownloadState.Initializing
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