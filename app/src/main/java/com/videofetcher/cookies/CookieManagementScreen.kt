package com.videofetcher.cookies
import com.videofetcher.manager.UserAgentManager
import com.videofetcher.manager.CookieDomainInfo
import com.videofetcher.manager.PermissionManager

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videofetcher.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookieManagementScreen(
    onBack: () -> Unit,
    onOpenBrowserForDomain: (String) -> Unit
) {
    val context = LocalContext.current
    var refreshTrigger by remember { mutableStateOf(0) }
    val savedDomains = remember(refreshTrigger) {
        com.videofetcher.manager.CookieManager.getAllSavedCookieDomains(context)
    }

    var domainToDelete by remember { mutableStateOf<CookieDomainInfo?>(null) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var isWarningVisible by remember { mutableStateOf(true) }

    val permissionManager = remember { PermissionManager(context) }
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            permissionManager.saveCustomDownloadFolder(uri)
            com.videofetcher.manager.CookieManager.restoreFromSafTreeUri(context, uri)
            refreshTrigger++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Saved Cookies",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${savedDomains.size} websites authenticated",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_back),
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    if (savedDomains.isEmpty()) {
                        TextButton(onClick = { folderPickerLauncher.launch(null) }) {
                            Text(
                                text = "Restore",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    } else {
                        TextButton(onClick = {
                            val (pulled, pushed) = com.videofetcher.manager.CookieManager.smartSyncCookies(context)
                            val msg = when {
                                pulled > 0 && pushed > 0 -> "Synced! Merged $pulled restored & backed up $pushed active cookies"
                                pulled > 0 -> "Synced! Restored $pulled missing cookies"
                                pushed > 0 -> "Synced! Backed up $pushed active cookies"
                                else -> "Cookies synced"
                            }
                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                            refreshTrigger++
                        }) {
                            Text(
                                text = "Sync",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        TextButton(onClick = { showClearAllDialog = true }) {
                            Text(
                                text = "Clear All",
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Security Warning Banner Card right below header row
            if (isWarningVisible) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_warning),
                                    contentDescription = "Note",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Note",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(
                                onClick = { isWarningVisible = false },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Text(
                                    text = "✕",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Please reduce using Instagram and Facebook cookies as they belong to Meta. This app effectively downloads most videos without cookies — only use cookies when necessary. If you want to deactivate cookie usage for a platform, simply delete its cookie session below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            if (savedDomains.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_cookie),
                        contentDescription = "No Cookies",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No Saved Cookies",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Use the In-App Browser in Settings to sign into any platform and save session cookies.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(savedDomains, key = { it.domainKey }) { item ->
                        CookieDomainRow(
                            info = item,
                            onOpenBrowser = {
                                onOpenBrowserForDomain("https://www.${item.domainKey}.com")
                            },
                            onDelete = {
                                domainToDelete = item
                            }
                        )
                    }
                }
            }
        }
    }

    // Delete Single Domain Dialog
    if (domainToDelete != null) {
        AlertDialog(
            onDismissRequest = { domainToDelete = null },
            title = { Text("Delete Cookie Session?") },
            text = { 
                Text("Are you sure you want to remove active cookies for ${domainToDelete?.displayName} from app storage?\n\nNote: Backup files in your Download folder (.cookies/ & .useragent/) are preserved and not destroyed. If you wish to delete backup files, you must manually delete them using a File Manager.") 
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val key = domainToDelete!!.domainKey
                        com.videofetcher.manager.CookieManager.deleteCookieFile(context, key)
                        Toast.makeText(context, "Deleted cookies for $key", Toast.LENGTH_SHORT).show()
                        domainToDelete = null
                        refreshTrigger++
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { domainToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Clear All Cookies Dialog
    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Clear All Saved Cookies?") },
            text = { 
                Text("Are you sure you want to clear all active cookie sessions from app storage?\n\nNote: Backup files in your Download folder (.cookies/ & .useragent/) are preserved and not destroyed. If you wish to delete backup files, you must manually delete them using a File Manager.") 
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        com.videofetcher.manager.CookieManager.clearAllCookies(context)
                        Toast.makeText(context, "All saved cookies cleared", Toast.LENGTH_SHORT).show()
                        showClearAllDialog = false
                        refreshTrigger++
                    }
                ) {
                    Text("Clear All", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun CookieDomainRow(
    info: CookieDomainInfo,
    onOpenBrowser: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault()) }
    val formattedDate = remember(info.lastModified) { dateFormat.format(Date(info.lastModified)) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_cookie),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = info.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${info.cookieCount} cookies",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Text(
                        text = "Saved $formattedDate",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenBrowser) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_open_in_new),
                        contentDescription = "Open in Browser",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_delete),
                        contentDescription = "Delete Cookies",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    }
}
