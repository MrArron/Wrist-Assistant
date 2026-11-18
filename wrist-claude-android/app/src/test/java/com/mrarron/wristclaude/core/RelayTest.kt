package com.mrarron.wristclaude.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayTest {
    private val own = "com.mrarron.wristclaude"

    @Test fun offByDefaultAndOnlyPickedApps() {
        val d = AppSettings()
        assertFalse(d.relayEnabled)
        assertTrue(d.relayApps.isEmpty())
        assertFalse(Relay.shouldRelay(false, listOf("com.example.duet"), "com.example.duet", own))
        assertTrue(Relay.shouldRelay(true, listOf("com.example.duet"), "com.example.duet", own))
        assertFalse(Relay.shouldRelay(true, listOf("com.example.duet"), "com.example.chat", own))
        assertFalse(Relay.shouldRelay(true, listOf(own), own, own))
    }

    @Test fun cleanAppsDropsJunkAndCaps() {
        val many = (1..12).map { "com.example.app$it" }
        assertEquals(Relay.MAX_APPS, Relay.cleanApps(many).size)
        assertEquals(listOf("com.example.a"), Relay.cleanApps(listOf(" com.example.a ", "com.example.a", "not a package", "nodots", "")))
    }

    @Test fun settingsRoundTrip() {
        val s = AppSettings(relayEnabled = true, relayApps = listOf("com.example.duet", "bad value"))
        val back = AppSettings.fromJson(s.toJson())
        assertTrue(back.relayEnabled)
        assertEquals(listOf("com.example.duet"), back.relayApps)
        assertFalse(AppSettings.fromJson(AppSettings().toJson()).relayEnabled)
    }

    @Test fun titleAndText() {
        assertEquals("Duet: Needs you", Relay.title("Duet", "Needs you"))
        assertEquals("Duet", Relay.title("Duet", "duet"))
        assertEquals("Duet", Relay.title("Duet", ""))
        assertEquals("Notification", Relay.title("", ""))
        assertTrue(Relay.title("App", "x".repeat(200)).length <= Relay.MAX_TITLE)
        assertEquals("a\nb", Relay.text("  a \r\n\r\n b  "))
        val long = Relay.text("y".repeat(1000))
        assertEquals(Relay.MAX_TEXT, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test fun repeatsBuzzOnce() {
        val seen = Relay.Seen(max = 2)
        val a = Relay.fingerprint("k1", "t", "hello")
        assertFalse(seen.seenBefore(a))
        assertTrue(seen.seenBefore(a))
        assertFalse(seen.seenBefore(Relay.fingerprint("k1", "t", "hello again")))
        assertFalse(seen.seenBefore(Relay.fingerprint("k2", "t", "x")))
        // capacity 2: the first fingerprint has been forgotten
        assertFalse(seen.seenBefore(a))
    }

    @Test fun idsStayInTheirRange() {
        listOf("", "0|com.example|1|null|10001", "x".repeat(500)).forEach {
            val id = Relay.notificationId(it)
            assertTrue(id in 6_000_000 until 7_000_000)
            assertEquals(id, Relay.notificationId(it))
        }
    }

    @Test fun noteTextNamesTheApp() {
        assertEquals("From Duet: Needs you\nPick a time", Relay.noteText("Duet", "Needs you", " Pick a time "))
        assertEquals("From a phone notification: hi", Relay.noteText("", "", "hi"))
    }
}
