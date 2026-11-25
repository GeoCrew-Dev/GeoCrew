package org.geocrew.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppReleaseTest {
    private val feed = """
        {"tag_name":"v0.3.0","name":"同勘 v0.3.0","draft":false,"prerelease":false,
         "body":"### 新功能\r\n\r\n- **map** 下载整幅底图\r\n\r\n完整改动：v0.2.0...v0.3.0",
         "html_url":"https://github.com/x/y/releases/tag/v0.3.0","published_at":"2026-11-25T03:00:00Z",
         "assets":[{"name":"geocrew-android-0.3.0.apk","browser_download_url":"https://example.org/a.apk"},
                   {"name":"SHA256SUMS","browser_download_url":"https://example.org/sums"}]}
    """.trimIndent()

    @Test fun parsesFeed() {
        val r = AppReleases.parse(feed)!!
        assertEquals("0.3.0", r.version)
        assertEquals("https://example.org/a.apk", r.apk)
        assertEquals("https://example.org/sums", r.sums)
        assertEquals(1795575600L, r.publishedAt!!.epochSecond)
        assertTrue(r.notes.startsWith("### 新功能\n"))
        assertEquals("新功能\n\n- map 下载整幅底图", AppReleases.shortNotes(r.notes))
        assertNull(AppReleases.parse("""{"tag_name":"v1","draft":true}"""))
        assertNull(AppReleases.parse("not json"))
    }

    @Test fun comparesVersions() {
        assertTrue(AppReleases.newer("0.3.0", "0.2.9"))
        assertTrue(AppReleases.newer("1.0.0", "1.0.0-rc.2"))
        assertFalse(AppReleases.newer("1.0.0-rc.2", "1.0.0"))
        assertFalse(AppReleases.newer("0.3.0", "0.3.0"))
        assertFalse(AppReleases.newer("9.0.0", "dev"))
        assertEquals(1, AppReleases.compare("1.2.10", "1.2.9"))
        assertEquals(0, AppReleases.compare("v1.2", "1.2.0"))
    }

    @Test fun versionCodesFollowVersions() {
        assertEquals(1020399, AppReleases.versionCode("1.2.3"))
        assertEquals(1020302, AppReleases.versionCode("1.2.3-rc.2"))
        assertEquals(399, AppReleases.versionCode("0.0.3"))
        assertNull(AppReleases.versionCode("dev"))
        val order = listOf("0.1.0", "0.1.1-rc.1", "0.1.1", "0.2.0-beta.3", "0.2.0", "1.0.0")
        val codes = order.map { AppReleases.versionCode(it)!! }
        assertEquals(codes.sorted(), codes)
    }

    @Test fun findsSums() {
        val h = "ab".repeat(32)
        val sums = "$h  geocrew-android-0.3.0.apk\n${"cd".repeat(32)} *SHA256SUMS.sig\n"
        assertEquals(h, AppReleases.sumFor(sums, "geocrew-android-0.3.0.apk"))
        assertNull(AppReleases.sumFor(sums, "geocrew-android-0.2.0.apk"))
        assertNull(AppReleases.sumFor("xyz  a.apk", "a.apk"))
    }
}
