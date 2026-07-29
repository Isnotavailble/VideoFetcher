package com.videofetcher.feature.files.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.DocumentsContract
import androidx.compose.ui.text.style.TextAlign
import com.videofetcher.R

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import com.videofetcher.manager.PausedDownload
import com.videofetcher.feature.files.viewmodel.FilesListState
import com.videofetcher.feature.files.viewmodel.DownloadedFileDetails
import com.videofetcher.feature.files.viewmodel.FilesViewModel
import com.videofetcher.VideoThumbnailBox

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    pausedDownloads: List<PausedDownload>,
    filesListState: FilesListState,
    viewModel: FilesViewModel,
    context: android.content.Context,
    hasStoragePermission: Boolean,
    onRequestPermission: () -> Unit
) {
    var fileToConfirmDelete by remember { mutableStateOf<DownloadedFileDetails?>(null) }

    var fileAwaitingPermission by remember { mutableStateOf<DownloadedFileDetails?>(null) }
    var showFolderInstructionDialog by remember { mutableStateOf(false) }
    var expandedSection by remember { mutableStateOf("VIDEO") }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                val treeDocumentId = DocumentsContract.getTreeDocumentId(uri)
                // Verify they selected our specific folder
                if (treeDocumentId.endsWith("VideoFetcher", ignoreCase = true)) {
                    (context.applicationContext as com.videofetcher.VideoFetcherApp).container.permissionManager.saveFolderPermission(uri)
                    
                    // Permission saved! Automatically retry the deletion silently
                    fileAwaitingPermission?.let { fileToDelete ->
                        val isAudio = fileToDelete.path.contains("(MP3", ignoreCase = true) || fileToDelete.path.contains("(M4A", ignoreCase = true) || fileToDelete.path.endsWith(".mp3", ignoreCase = true) || fileToDelete.path.endsWith(".m4a", ignoreCase = true)
                        val typeLabel = if (isAudio) "Audio" else "Video"
                        viewModel.deleteVideo(
                            context = context.applicationContext,
                            fileDetails = fileToDelete,
                            onSuccess = {
                                Toast.makeText(context, "$typeLabel deleted", Toast.LENGTH_SHORT).show()
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
            text = { Text("To delete files downloaded from a previous installation, we need access to the VideoFetcher folder.\n\nPlease tap 'Continue', select the 'VideoFetcher' folder, and tap 'Use this folder'.") },
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
        val isAudio = fileToConfirmDelete?.path?.let {
            it.contains("(MP3", ignoreCase = true) || it.contains("(M4A", ignoreCase = true) || it.endsWith(".mp3", ignoreCase = true) || it.endsWith(".m4a", ignoreCase = true)
        } == true
        val typeLabel = if (isAudio) "Audio" else "Video"

        AlertDialog(
            onDismissRequest = { fileToConfirmDelete = null },
            title = { Text("Delete $typeLabel", fontWeight = FontWeight.Bold) },
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
                                Toast.makeText(context, "$typeLabel deleted", Toast.LENGTH_SHORT).show()
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
                    Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToConfirmDelete = null }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        )
    }

    val videoFiles by viewModel.videoFiles.collectAsState()
    val audioFiles by viewModel.audioFiles.collectAsState()

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
                            Text("We need access to your storage to display and manage your downloaded videos.", textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
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
                items(items = pausedDownloads, key = { it.url }, contentType = { "paused_card" }) { paused ->
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
                    if (filesList.files.isEmpty() && pausedDownloads.isEmpty()) {
                        item { Text("No downloads yet.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) }
                    } else {
                        // Video Downloads Header
                        stickyHeader {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.background)
                                    .clickable { expandedSection = if (expandedSection == "VIDEO") "NONE" else "VIDEO" }
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Video Downloads", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Icon(
                                    imageVector = if (expandedSection == "VIDEO") Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                    contentDescription = "Expand/Collapse",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        if (expandedSection == "VIDEO") {
                            if (videoFiles.isEmpty()) {
                                item {
                                    Text("No video downloads yet.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(bottom = 16.dp))
                                }
                            } else {
                                items(items = videoFiles, key = { it.path }, contentType = { "downloaded_video" }) { file ->
                                    DownloadedVideoCard(
                                        file = file,
                                        viewModel = viewModel,
                                        context = context,
                                        onDelete = { fileToDelete -> fileToConfirmDelete = fileToDelete }
                                    )
                                }
                            }
                        }

                        // Audio Downloads Header
                        stickyHeader {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.background)
                                    .clickable { expandedSection = if (expandedSection == "AUDIO") "NONE" else "AUDIO" }
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Audio Downloads", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Icon(
                                    imageVector = if (expandedSection == "AUDIO") Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                    contentDescription = "Expand/Collapse",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        if (expandedSection == "AUDIO") {
                            if (audioFiles.isEmpty()) {
                                item {
                                    Text("No audio downloads yet.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(bottom = 16.dp))
                                }
                            } else {
                                items(items = audioFiles, key = { it.path }, contentType = { "downloaded_audio" }) { file ->
                                    DownloadedVideoCard(
                                        file = file,
                                        viewModel = viewModel,
                                        context = context,
                                        onDelete = { fileToDelete -> fileToConfirmDelete = fileToDelete }
                                    )
                                }
                            }
                        }
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
fun DownloadedVideoCard(
    file: DownloadedFileDetails,
    viewModel: FilesViewModel,
    context: android.content.Context,
    onDelete: (DownloadedFileDetails) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isAudio = file.isAudio
    val onCardClick = remember(file, context) { { viewModel.playVideo(context, file) } }

        Row(
            modifier = Modifier
                .clickable(onClick = onCardClick)
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VideoThumbnailBox(
                imageData = file.thumbnailUriStr.ifEmpty { null },
                isAudio = isAudio,
                size = 80.dp
            )

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
                                Icon(Icons.Filled.Delete, contentDescription = "Delete", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Delete", color = MaterialTheme.colorScheme.error) 
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
            VideoThumbnailBox(
                imageData = paused.thumbnailUrl.ifBlank { null },
                isAudio = paused.quality.contains("MP3", ignoreCase = true) || paused.quality.contains("M4A", ignoreCase = true),
                size = 80.dp
            )
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
                    Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.error)
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
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}




