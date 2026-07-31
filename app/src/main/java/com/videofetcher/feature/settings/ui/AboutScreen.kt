package com.videofetcher.feature.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videofetcher.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp)
                .verticalScroll(scrollState)
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "VideoFetcher",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "VideoFetcher was built by a small, dedicated team for the community of privacy seekers. In a world full of tracking and subscriptions, we believe tools like this should be free, safe, and truly yours.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(24.dp))

            ExpandableCautionItem()

            AboutFeatureItem(
                icon = { Icon(Icons.Filled.Lock, contentDescription = "Privacy", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
                title = "100% Private & Local",
                description = "No cookies. No tracking. No hidden web servers. Every download is processed entirely on your device."
            )
            
            AboutFeatureItem(
                icon = { Icon(painterResource(id = R.drawable.ic_coffee), contentDescription = "Coffee", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
                title = "Community Supported",
                description = "We made this app completely free and ad-free. If it has made your life easier, please consider buying us a coffee!",
            )
            
            AboutFeatureItem(
                icon = { Icon(painterResource(id = R.drawable.ic_rocket), contentDescription = "Hire Us", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
                title = "Hire Us",
                description = "Love the smooth experience? We are available for freelance work! Let our team build your next app idea.",
                onClick = { uriHandler.openUri("https://teleg.one/Tom_lit") } 
            )
            
            AboutFeatureItem(
                icon = { Icon(painterResource(id = R.drawable.ic_telegram), contentDescription = "Telegram", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
                title = "Get in Touch",
                description = "Whether you have feedback, found a bug, or want to discuss a project, let's chat directly on Telegram.",
                onClick = { uriHandler.openUri("https://teleg.one/Tom_lit") }
            )

            Spacer(modifier = Modifier.height(32.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                "VideoFetcher is made possible thanks to incredible open-source projects like YoutubeDL and FFmpeg.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                fontSize = 11.sp,
                lineHeight = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            Text("VideoFetcher v1.0.0", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.align(Alignment.CenterHorizontally), fontSize = 12.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Made with ❤️", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.align(Alignment.CenterHorizontally), fontSize = 12.sp)
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun AboutFeatureItem(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    onClick: (() -> Unit)? = null
) {
    val modifier = if (onClick != null) {
        Modifier.clickable { onClick() }.padding(vertical = 12.dp)
    } else {
        Modifier.padding(vertical = 12.dp)
    }

    Row(modifier = Modifier.fillMaxWidth().then(modifier), verticalAlignment = Alignment.Top) {
        Box(modifier = Modifier.padding(top = 2.dp)) {
            icon()
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(description, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun ExpandableCautionItem() {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(modifier = Modifier.padding(top = 2.dp)) {
            Icon(
                painter = painterResource(id = R.drawable.ic_warning),
                contentDescription = "Caution",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Caution About Cookies",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (expanded) {
                    "Using account cookies is entirely at your own risk. When cookies are attached, yt-dlp mimics your personal digital identity during requests. Social platforms (especially Meta / Facebook) actively detect automated traffic — testing showed Facebook issuing \"Automation Detected\" warnings within 3 to 4 days, which can lead to account bans. Please use cookies with caution and delete them when no longer needed."
                } else {
                    "Tap to read important account risk details regarding Meta & cookies..."
                },
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}
