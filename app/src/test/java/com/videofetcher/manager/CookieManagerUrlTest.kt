package com.videofetcher.manager

import org.junit.Assert.assertEquals
import org.junit.Test

class CookieManagerUrlTest {

    private val cookieManager = CookieManager()

    @Test
    fun getDomainKey_resolvesYouTubeUrlsWithQueries() {
        assertEquals("youtube", cookieManager.getDomainKey("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("youtube", cookieManager.getDomainKey("https://m.youtube.com/watch?v=dQw4w9WgXcQ&feature=youtu.be"))
        assertEquals("youtube", cookieManager.getDomainKey("https://youtu.be/dQw4w9WgXcQ?si=abcdef12345"))
        assertEquals("youtube", cookieManager.getDomainKey("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("youtube", cookieManager.getDomainKey("http://youtube.com/watch?v=123"))
    }

    @Test
    fun getDomainKey_resolvesFacebookUrlsWithQueries() {
        assertEquals("facebook", cookieManager.getDomainKey("https://www.facebook.com/watch/?v=12345678"))
        assertEquals("facebook", cookieManager.getDomainKey("https://web.facebook.com/reel/12345678?mibextid=xyz"))
        assertEquals("facebook", cookieManager.getDomainKey("https://fb.watch/abcdef123/"))
        assertEquals("facebook", cookieManager.getDomainKey("https://m.facebook.com/story.php?story_fbid=123&id=456"))
        assertEquals("facebook", cookieManager.getDomainKey("https://fb.com/page"))
    }

    @Test
    fun getDomainKey_resolvesInstagramUrlsWithQueries() {
        assertEquals("instagram", cookieManager.getDomainKey("https://www.instagram.com/reel/C12345/?utm_source=ig_web_copy_link"))
        assertEquals("instagram", cookieManager.getDomainKey("https://instagram.com/p/C12345/"))
        assertEquals("instagram", cookieManager.getDomainKey("https://instagr.am/p/C12345/"))
    }

    @Test
    fun getDomainKey_resolvesOtherMajorPlatforms() {
        assertEquals("tiktok", cookieManager.getDomainKey("https://www.tiktok.com/@creator/video/1234567890123456789?is_from_webapp=1"))
        assertEquals("twitter", cookieManager.getDomainKey("https://twitter.com/user/status/1234567890"))
        assertEquals("twitter", cookieManager.getDomainKey("https://x.com/user/status/1234567890?s=20"))
        assertEquals("bilibili", cookieManager.getDomainKey("https://www.bilibili.com/video/BV1xx411c7mD/?spm_id_from=333.337"))
        assertEquals("vimeo", cookieManager.getDomainKey("https://vimeo.com/123456789"))
        assertEquals("reddit", cookieManager.getDomainKey("https://www.reddit.com/r/videos/comments/xyz/sample_video/"))
        assertEquals("dailymotion", cookieManager.getDomainKey("https://www.dailymotion.com/video/x12345"))
    }

    @Test
    fun getDomainKey_handlesMixedCasingAndPaddedWhitespace() {
        assertEquals("youtube", cookieManager.getDomainKey("   HTTPS://WWW.YOUTUBE.COM/WATCH?V=dQw4w9WgXcQ \n "))
        assertEquals("facebook", cookieManager.getDomainKey("  HTTPS://FB.WATCH/XYZ/  "))
        assertEquals("instagram", cookieManager.getDomainKey(" HTTPS://INSTAGRAM.COM/REEL/123/ "))
    }

    @Test
    fun getDomainKey_fallsBackToGenericCandidateForUnlistedDomains() {
        assertEquals("example", cookieManager.getDomainKey("https://www.example.com/video/123"))
        assertEquals("archive", cookieManager.getDomainKey("https://archive.org/details/test_video"))
    }
}
