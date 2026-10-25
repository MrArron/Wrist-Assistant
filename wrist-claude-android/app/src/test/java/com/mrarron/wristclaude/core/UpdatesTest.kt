package com.mrarron.wristclaude.core

import com.mrarron.wristclaude.ui.mismatchText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    private fun release(
        tag: String = "v2.3.0",
        apkName: String = "wrist-assistant-companion-2.3.0.apk",
        url: String = "https://github.com/MrArron/Wrist-Assistant/releases/download/v2.3.0/wrist-assistant-companion-2.3.0.apk",
        draft: Boolean = false,
        prerelease: Boolean = false,
    ) = """{"tag_name":"$tag","html_url":"https://github.com/MrArron/Wrist-Assistant/releases/tag/$tag","draft":$draft,"prerelease":$prerelease,
        "assets":[{"name":"wrist-assistant-2.3.0.pbw","browser_download_url":"https://github.com/MrArron/Wrist-Assistant/releases/download/v2.3.0/wrist-assistant-2.3.0.pbw"},
                  {"name":"$apkName","browser_download_url":"$url"}]}"""

    @Test
    fun parsesTheCompanionApk() {
        val r = Updates.parseLatest(release())!!
        assertEquals("2.3.0", r.version)
        assertTrue(r.apkUrl.endsWith("/wrist-assistant-companion-2.3.0.apk"))
        assertEquals("https://github.com/MrArron/Wrist-Assistant/releases/tag/v2.3.0", r.pageUrl)
    }

    @Test
    fun rejectsDraftsPrereleasesOddTagsAndForeignUrls() {
        assertNull(Updates.parseLatest(release(draft = true)))
        assertNull(Updates.parseLatest(release(prerelease = true)))
        assertNull(Updates.parseLatest(release(tag = "v2.3.0-beta")))
        assertNull(Updates.parseLatest(release(apkName = "something-else.apk")))
        assertNull(Updates.parseLatest(release(url = "https://evil.example/wrist-assistant-companion-2.3.0.apk")))
        assertNull(Updates.parseLatest(release(url = "https://github.com/someone/fork/releases/download/v2.3.0/wrist-assistant-companion-2.3.0.apk")))
        assertNull(Updates.parseLatest("not json"))
        assertNull(Updates.parseLatest("""{"message":"Not Found"}"""))
    }

    @Test
    fun comparesVersionsNumerically() {
        assertTrue(Updates.isNewer("2.3.0", "2.2.1"))
        assertTrue(Updates.isNewer("v2.10.0", "2.9.9"))
        assertTrue(Updates.isNewer("3.0.0", "2.99.99"))
        assertFalse(Updates.isNewer("2.2.1", "2.2.1"))
        assertFalse(Updates.isNewer("2.2.0", "2.2.1"))
        assertFalse(Updates.isNewer("garbage", "2.2.1"))
        assertTrue(Updates.isNewer("2.2.1", "")) // unknown installed version
    }

    @Test
    fun versionCodesMatchTheGradleScheme() {
        assertEquals(221, Updates.versionCode("2.2.1"))
        assertEquals("2.2.1", Updates.versionName(221))
        assertNull(Updates.versionCode("2.2"))
    }

    @Test
    fun checksAtMostDaily() {
        val day = Updates.CHECK_EVERY_MS
        assertTrue(Updates.dueForCheck(now = 1_000, lastCheck = 0))
        assertFalse(Updates.dueForCheck(now = day, lastCheck = 1))
        assertTrue(Updates.dueForCheck(now = day + 1, lastCheck = 1))
        assertTrue(Updates.dueForCheck(now = 5, lastCheck = 10)) // clock went backwards
    }

    @Test
    fun mismatchMessages() {
        assertNull(mismatchText(221, 221))
        assertNull(mismatchText(221, null))
        assertTrue(mismatchText(221, 230)!!.contains("Update the phone app"))
        assertTrue(mismatchText(230, 221)!!.contains("Pebble app"))
    }
}
