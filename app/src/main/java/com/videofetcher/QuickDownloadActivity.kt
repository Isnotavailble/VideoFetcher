package com.videofetcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
class QuickDownloadActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        var sharedUrl = ""
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            // Instantly extract the URL from the shared text
            val urlRegex = """(?i)\b((?:https?://|www\d{0,3}[.]|[a-z0-9.\-]+[.][a-z]{2,4}/)(?:[^\s()<>]+|\(([^\s()<>]+|(\([^\s()<>]+\)))*\))+(?:\(([^\s()<>]+|(\([^\s()<>]+\)))*\)|[^\s`!()\[\]{};:'".,<>?«»“”‘’]))""".toRegex()
            val match = urlRegex.find(sharedText)
            sharedUrl = match?.value ?: sharedText
        }

        // If there is no URL, close silently without interrupting the user
        if (sharedUrl.isBlank()) {
            finish()
            return
        }

        setContent {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            var selectedFormat by remember { mutableStateOf("1080p") }
            val scope = rememberCoroutineScope()
            val context = LocalContext.current

            // Launcher to ask for permissions directly from the bottom sheet if it's their first time
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { permissions ->
                val allGranted = permissions.entries.all { it.value }
                if (allGranted) {
                    scope.launch {
                        val serviceIntent = Intent(context, DownloadService::class.java).apply {
                            putExtra("URL", sharedUrl)
                            putExtra("QUALITY", selectedFormat)
                        }
                        context.startService(serviceIntent)
                        sheetState.hide()
                        finish()
                    }
                } else {
                    Toast.makeText(context, "Permissions required to save videos.", Toast.LENGTH_SHORT).show()
                }
            }

            // The Bottom Sheet overlay
            ModalBottomSheet(
                onDismissRequest = { finish() }, // Close the transparent activity if they swipe down to cancel
                sheetState = sheetState,
                containerColor = Color.White,
                dragHandle = { BottomSheetDefaults.DragHandle(color = Color.LightGray) }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = "VIDEO FETCHER",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 20.sp,
                        color = Color.Black
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = sharedUrl,
                        color = Color.Gray,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("1080p", "720p", "480p").forEach { format ->
                            val isSelected = selectedFormat == format
                            Surface(
                                onClick = { selectedFormat = format },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) Color.Black else Color(0xFFEEEEEE),
                                border = BorderStroke(1.dp, Color.Transparent),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = format,
                                    color = if (isSelected) Color.White else Color.Black,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 12.dp),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    Button(
                        onClick = {
                            val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            }
                            
                            val hasPermissions = permissionsToRequest.all {
                                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                            }

                            if (hasPermissions) {
                                scope.launch {
                                    val serviceIntent = Intent(context, DownloadService::class.java).apply {
                                        putExtra("URL", sharedUrl)
                                        putExtra("QUALITY", selectedFormat)
                                    }
                                    context.startService(serviceIntent)
                                    sheetState.hide()
                                    finish()
                                }
                            } else {
                                // Ask for permissions right on top of the bottom sheet!
                                permissionLauncher.launch(permissionsToRequest)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("START DOWNLOAD", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}