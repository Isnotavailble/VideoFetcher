package com.videofetcher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    sharedUrl: String = ""
) {
    val context = LocalContext.current
    var url by remember { mutableStateOf("") }
    var selectedFormat by remember { mutableStateOf("1080p") }
    val state by viewModel.downloadState.collectAsState()

    // Update URL field automatically when intent brings a new link
    LaunchedEffect(sharedUrl) {
        if (sharedUrl.isNotEmpty()) {
            url = sharedUrl
        }
    }

    LaunchedEffect(Unit) {
        viewModel.initializeEngine(context.applicationContext)
    }

    // Determine if inputs should be interactive
    val isReady = state !is DownloadState.Initializing

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(24.dp)
    ) {
        // HEADER
        Text(
            text = "VIDEO\nFETCHER",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = 32.sp,
            color = Color.Black
        )

        Spacer(modifier = Modifier.height(40.dp))

        // URL INPUT
        Text("Video URL", fontWeight = FontWeight.Bold, color = if (isReady) Color.Black else Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))
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
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                focusedBorderColor = Color.Black,
                unfocusedBorderColor = Color.Transparent,
                disabledContainerColor = Color(0xFFEEEEEE),
                disabledBorderColor = Color.Transparent
            ),
            shape = RoundedCornerShape(8.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // FORMAT SELECTION
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

        // This Spacer pushes the status and buttons to the bottom of the screen
        Spacer(modifier = Modifier.weight(1f))

        // COMPACT STATUS AREA
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (state) {
                is DownloadState.Initializing -> {
                    CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Initializing engine...", color = Color.Gray, fontWeight = FontWeight.Medium)
                }
                is DownloadState.Downloading -> {
                    val downloadState = state as DownloadState.Downloading
                    Text(downloadState.status, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = downloadState.progress,
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = Color.Black,
                        trackColor = Color.LightGray
                    )
                }
                is DownloadState.Success -> {
                    Text("Success! Video saved.", color = Color(0xFF00C853), fontWeight = FontWeight.Bold)
                }
                is DownloadState.Error -> {
                    Text((state as DownloadState.Error).message, color = Color.Red, textAlign = TextAlign.Center)
                }
                is DownloadState.Cancelled -> {
                    Text("Download Cancelled", color = Color.Gray, fontWeight = FontWeight.Bold)
                }
                is DownloadState.Idle -> {
                    // Empty space when waiting for user input
                }
            }
        }

        // ACTION BUTTONS
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
                onClick = { viewModel.startDownload(url, selectedFormat) },
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