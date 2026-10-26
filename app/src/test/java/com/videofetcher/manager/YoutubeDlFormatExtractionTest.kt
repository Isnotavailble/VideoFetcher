package com.videofetcher.manager

import com.yausername.youtubedl_android.mapper.VideoFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class YoutubeDlFormatExtractionTest {

    private fun createFormat(width: Int, height: Int, vcodec: String?): VideoFormat {
        val format = VideoFormat()
        VideoFormat::class.java.getDeclaredField("width").apply {
            isAccessible = true
            set(format, width)
        }
        VideoFormat::class.java.getDeclaredField("height").apply {
            isAccessible = true
            set(format, height)
        }
        VideoFormat::class.java.getDeclaredField("vcodec").apply {
            isAccessible = true
            set(format, vcodec)
        }
        return format
    }

    @Test
    fun extractAvailableFormats_for4kResolution_includesAllTiersAndAudio() {
        val formats = YoutubeDlManager.extractAvailableFormats(2160)
        assertEquals(
            listOf(
                "4K", "2K", "1080p", "720p", "480p",
                "Best Quality (M4A)",
                "Audio (MP3) - High Quality",
                "Audio (MP3) - Standard",
                "Audio (MP3) - Fast"
            ),
            formats
        )
    }

    @Test
    fun extractAvailableFormats_for2kResolution_startsAt2k() {
        val formats = YoutubeDlManager.extractAvailableFormats(1440)
        assertEquals(
            listOf(
                "2K", "1080p", "720p", "480p",
                "Best Quality (M4A)",
                "Audio (MP3) - High Quality",
                "Audio (MP3) - Standard",
                "Audio (MP3) - Fast"
            ),
            formats
        )
    }

    @Test
    fun extractAvailableFormats_for1080pResolution_startsAt1080p() {
        val formats = YoutubeDlManager.extractAvailableFormats(1080)
        assertEquals(
            listOf(
                "1080p", "720p", "480p", "360p",
                "Best Quality (M4A)",
                "Audio (MP3) - High Quality",
                "Audio (MP3) - Standard",
                "Audio (MP3) - Fast"
            ),
            formats
        )
    }

    @Test
    fun extractAvailableFormats_for720pResolution_startsAt720p() {
        val formats = YoutubeDlManager.extractAvailableFormats(720)
        assertEquals(
            listOf(
                "720p", "480p", "360p",
                "Best Quality (M4A)",
                "Audio (MP3) - High Quality",
                "Audio (MP3) - Standard",
                "Audio (MP3) - Fast"
            ),
            formats
        )
    }

    @Test
    fun extractAvailableFormats_for480pResolution_startsAt480p() {
        val formats = YoutubeDlManager.extractAvailableFormats(480)
        assertEquals(
            listOf(
                "480p", "360p",
                "Best Quality (M4A)",
                "Audio (MP3) - High Quality",
                "Audio (MP3) - Standard",
                "Audio (MP3) - Fast"
            ),
            formats
        )
    }

    @Test
    fun extractAvailableFormats_for360pResolution_startsAt360p() {
        val formats = YoutubeDlManager.extractAvailableFormats(360)
        assertEquals(
            listOf(
                "360p",
                "Best Quality (M4A)",
                "Audio (MP3) - High Quality",
                "Audio (MP3) - Standard",
                "Audio (MP3) - Fast"
            ),
            formats
        )
    }

    @Test
    fun extractAvailableFormats_forUnknownOrZeroHeight_fallsBackToBestQuality() {
        val formats = YoutubeDlManager.extractAvailableFormats(0)
        assertEquals(
            listOf(
                "Best Quality",
                "Best Quality (M4A)",
                "Audio (MP3) - High Quality",
                "Audio (MP3) - Standard",
                "Audio (MP3) - Fast"
            ),
            formats
        )
    }

    @Test
    fun calculateMaxResolutionHeight_handlesNullAndEmptyList() {
        assertEquals(0, YoutubeDlManager.calculateMaxResolutionHeight(null))
        assertEquals(0, YoutubeDlManager.calculateMaxResolutionHeight(emptyList()))
    }

    @Test
    fun calculateMaxResolutionHeight_filtersOutAudioOnlyStreams() {
        val audioOnly = createFormat(width = 0, height = 0, vcodec = "none")
        assertEquals(0, YoutubeDlManager.calculateMaxResolutionHeight(listOf(audioOnly)))
    }

    @Test
    fun calculateMaxResolutionHeight_handlesVerticalVideosUsingMinDimension() {
        val vertical1080p = createFormat(width = 1080, height = 1920, vcodec = "avc1")
        // Math.min(1080, 1920) = 1080 -> should be bucketted as 1080p rather than 1920
        assertEquals(1080, YoutubeDlManager.calculateMaxResolutionHeight(listOf(vertical1080p)))
    }

    @Test
    fun calculateMaxResolutionHeight_selectsMaxAcrossMultipleStreams() {
        val f720 = createFormat(width = 1280, height = 720, vcodec = "avc1")
        val f1080 = createFormat(width = 1920, height = 1080, vcodec = "avc1")
        val audioOnly = createFormat(width = 0, height = 0, vcodec = "none")
        assertEquals(1080, YoutubeDlManager.calculateMaxResolutionHeight(listOf(f720, f1080, audioOnly)))
    }

    @Test
    fun formatDuration_formatsMinutesAndSecondsCorrectly() {
        assertEquals("00:00", YoutubeDlManager.formatDuration(0L))
        assertEquals("00:45", YoutubeDlManager.formatDuration(45_000L))
        assertEquals("01:05", YoutubeDlManager.formatDuration(65_000L))
        assertEquals("59:59", YoutubeDlManager.formatDuration(3_599_000L))
    }

    @Test
    fun formatDuration_formatsHoursCorrectly() {
        assertEquals("01:00:00", YoutubeDlManager.formatDuration(3_600_000L))
        assertEquals("01:01:05", YoutubeDlManager.formatDuration(3_665_000L))
        assertEquals("10:00:00", YoutubeDlManager.formatDuration(36_000_000L))
    }
}
