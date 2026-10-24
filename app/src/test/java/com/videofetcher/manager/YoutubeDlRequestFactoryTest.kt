package com.videofetcher.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeDlRequestFactoryTest {

    @Test
    fun cleanUrl_preservesYouTubeVideoIdQueryParameter() {
        val rawUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val cleaned = YoutubeDlRequestFactory.cleanUrl(rawUrl)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", cleaned)
        assertTrue(cleaned.contains("v=dQw4w9WgXcQ"))
    }

    @Test
    fun cleanUrl_preservesYouTubeAdditionalQueryParameters() {
        val rawUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s&list=PLrEnWoR732-DENxpyFDX2m348M_tB6mU"
        val cleaned = YoutubeDlRequestFactory.cleanUrl(rawUrl)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s&list=PLrEnWoR732-DENxpyFDX2m348M_tB6mU", cleaned)
        assertTrue(cleaned.contains("v=dQw4w9WgXcQ"))
        assertTrue(cleaned.contains("t=42s"))
    }

    @Test
    fun cleanUrl_preservesFacebookWatchQueryParameters() {
        val rawUrl = "https://www.facebook.com/watch/?v=10158223456789012"
        val cleaned = YoutubeDlRequestFactory.cleanUrl(rawUrl)
        assertEquals("https://www.facebook.com/watch/?v=10158223456789012", cleaned)
        assertTrue(cleaned.contains("v=10158223456789012"))
    }

    @Test
    fun cleanUrl_preservesFacebookReelTrackingParameters() {
        val rawUrl = "https://www.facebook.com/reel/123456789012345/?mibextid=wwXIfr"
        val cleaned = YoutubeDlRequestFactory.cleanUrl(rawUrl)
        assertEquals("https://www.facebook.com/reel/123456789012345/?mibextid=wwXIfr", cleaned)
    }

    @Test
    fun cleanUrl_preservesInstagramQueryParameters() {
        val rawUrl = "https://www.instagram.com/reel/C1234567890/?igsh=MWQ1ZGUxMzBkMA=="
        val cleaned = YoutubeDlRequestFactory.cleanUrl(rawUrl)
        assertEquals("https://www.instagram.com/reel/C1234567890/?igsh=MWQ1ZGUxMzBkMA==", cleaned)
    }

    @Test
    fun cleanUrl_trimsLeadingAndTrailingWhitespaceAndNewlines() {
        val rawUrl = "  \n\t https://www.youtube.com/watch?v=dQw4w9WgXcQ \t\n "
        val cleaned = YoutubeDlRequestFactory.cleanUrl(rawUrl)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", cleaned)
    }

    @Test
    fun cleanUrl_handlesEmptyAndBlankStringsGracefully() {
        assertEquals("", YoutubeDlRequestFactory.cleanUrl(""))
        assertEquals("", YoutubeDlRequestFactory.cleanUrl("   "))
    }
}
