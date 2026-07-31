package com.videofetcher.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.videofetcher.R

@Composable
fun VideoThumbnailBox(
    imageData: Any?,
    isAudio: Boolean = false,
    size: Dp = 80.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
        contentAlignment = Alignment.Center
    ) {
        if (isAudio) {
            Icon(
                painter = painterResource(id = R.drawable.ic_earphone),
                contentDescription = "No Thumbnail",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.size((size.value * 0.4f).dp)
            )
        } else {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "No Thumbnail",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.size((size.value * 0.4f).dp)
            )
        }

        val hasData = when (imageData) {
            is String -> imageData.isNotBlank()
            is Uri -> imageData != Uri.EMPTY
            else -> imageData != null
        }

        if (hasData) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageData)
                    .crossfade(true)
                    .build(),
                contentDescription = "Video Thumbnail",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
