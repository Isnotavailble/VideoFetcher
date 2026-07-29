package com.videofetcher.manager

data class YoutubeVideoMetadata(
    val title: String,
    val durationStr: String,
    val thumbnailUrl: String,
    val formats: List<String>
)
