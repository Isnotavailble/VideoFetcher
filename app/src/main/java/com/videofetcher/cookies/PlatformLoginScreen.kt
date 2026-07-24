package com.videofetcher.cookies

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.videofetcher.PermissionManager
import com.videofetcher.R

enum class LoginPlatform(
    val title: String,
    val loginUrl: String,
    val domain: String,
    val iconResId: Int
) {
    YOUTUBE("YouTube", "https://m.youtube.com", ".youtube.com", R.drawable.ic_youtube),
    INSTAGRAM("Instagram", "https://www.instagram.com", ".instagram.com", R.drawable.ic_instagram),
    FACEBOOK("Facebook", "https://m.facebook.com", ".facebook.com", R.drawable.ic_facebook),
    TIKTOK("TikTok", "https://www.tiktok.com", ".tiktok.com", R.drawable.ic_tiktok)
}

@Composable
fun PlatformLoginScreen(
    platform: LoginPlatform,
    onBack: () -> Unit,
    onCookiesSaved: () -> Unit
) {
    val context = LocalContext.current
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(platform.loginUrl) }
    var isLoading by remember { mutableStateOf(true) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Icon(
                            painter = painterResource(id = platform.iconResId),
                            contentDescription = platform.title,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Login to ${platform.title}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    IconButton(onClick = { webViewInstance?.reload() }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Refresh",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Sign in to your ${platform.title} account",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Tap Save Cookies after successful sign-in.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }

                    Button(
                        onClick = {
                            val cookieManager = CookieManager.getInstance()
                            val cleanDomain = platform.domain.removePrefix(".")
                            val domainsToQuery = listOf(
                                currentUrl,
                                "https://$cleanDomain",
                                "https://www.$cleanDomain",
                                "https://m.$cleanDomain",
                                "https://accounts.google.com"
                            )
                            val combinedCookies = domainsToQuery.mapNotNull { cookieManager.getCookie(it) }.joinToString("; ")
                            val platformKey = NetscapeCookieWriter.getPlatformKey(platform.domain) ?: "youtube"

                            val hasAuth = NetscapeCookieWriter.hasAuthTokens(platformKey, combinedCookies)
                            if (hasAuth) {
                                val success = NetscapeCookieWriter.writeCookies(context, platform.domain, combinedCookies)
                                if (success) {
                                    Toast.makeText(context, "${platform.title} session saved!", Toast.LENGTH_SHORT).show()
                                    onCookiesSaved()
                                    onBack()
                                } else {
                                    Toast.makeText(context, "Failed to format cookies.", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "Login incomplete. Please sign into your account first.", Toast.LENGTH_LONG).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text("Save Cookies", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewInstance = this
                            configureWebView(this, ctx, platform.loginUrl) { url, loading ->
                                currentUrl = url
                                isLoading = loading
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun configureWebView(
    webView: WebView,
    context: Context,
    initialUrl: String,
    onStateChange: (String, Boolean) -> Unit
) {
    val permissionManager = PermissionManager(context)

    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        useWideViewPort = true
        loadWithOverviewMode = true
        mediaPlaybackRequiresUserGesture = false
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    }

    // Extract User-Agent ONCE as soon as WebView opens and store in SharedPreferences
    val extractedUserAgent = webView.settings.userAgentString
    if (!extractedUserAgent.isNullOrBlank()) {
        permissionManager.saveUserAgent(extractedUserAgent)
    }

    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(webView, true)
    }

    webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString() ?: return false
            return handleUrlOverride(url)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            return handleUrlOverride(url ?: "")
        }

        private fun handleUrlOverride(url: String): Boolean {
            // EDGE CASE BYPASS: Prevent Android OS from opening native installed apps/APKs via intent://, market://, custom schemes
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                // Cancel navigation so external app isn't opened!
                return true
            }
            return false // Keep inside WebView
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            url?.let { onStateChange(it, true) }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            url?.let { onStateChange(it, false) }
        }
    }

    webView.loadUrl(initialUrl)
}
