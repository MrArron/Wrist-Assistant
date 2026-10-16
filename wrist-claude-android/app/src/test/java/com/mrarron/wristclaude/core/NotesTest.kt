package com.mrarron.wristclaude.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class NotesTest {
    private val now = ZonedDateTime.of(2026, 10, 15, 17, 49, 0, 0, ZoneId.of("America/New_York"))

    private val id = "1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789abcdeEF"
    private val id2 = "1ZyXwVuTsRqPoNmLkJiHgFeDcBa9876543210zyxwVUT"

    @Test
    fun docIdFromLinksAndIds() {
        assertEquals(id, Notes.docIdFrom("https://docs.google.com/document/d/$id/edit?usp=sharing"))
        assertEquals(id, Notes.docIdFrom("  $id "))
        assertNull(Notes.docIdFrom("not a doc"))
        assertNull(Notes.docIdFrom(""))
        assertNull(Notes.docIdFrom(null))
    }

    @Test
    fun onlyAppCreatedDocsAreUsable() {
        assertEquals(id, Notes.usableDocId(id, true))
        assertEquals("", Notes.usableDocId(id, false))
        assertEquals("", Notes.usableDocId("", true))
        assertEquals("", Notes.usableDocId("junk", true))
    }

    @Test
    fun defaultsHaveNoDocsAndOldInstallsAreMigrated() {
        assertEquals("", AppSettings().notesDoc)
        assertEquals("", AppSettings().contextDoc)
        // an old settings blob: ids present, no app-created flag
        val old = AppSettings.fromJson(kotlinx.serialization.json.buildJsonObject {
            put("notesDoc", kotlinx.serialization.json.JsonPrimitive(id))
            put("contextDoc", kotlinx.serialization.json.JsonPrimitive(id2))
        })
        assertEquals("", old.notesDoc)
        assertEquals("", old.contextDoc)
        assertFalse(old.docsAppCreated)
        val created = AppSettings.validate(AppSettings(notesDoc = id, contextDoc = id2, docsAppCreated = true))
        val round = AppSettings.fromJson(created.toJson())
        assertEquals(id, round.notesDoc)
        assertEquals(id2, round.contextDoc)
        assertTrue(round.docsAppCreated)
    }

    @Test
    fun createAndSeedRequests() {
        assertEquals("""{"title":"Wrist Assistant Notes"}""", Notes.createRequest(Notes.NOTES_TITLE).toString())
        assertEquals(
            "Wrist Assistant Notes\nNotes and handoffs from the Pebble watch. New entries are added at the bottom. " +
                "Say \"catch up on my notes\" in Claude to work through the OPEN ones.\n",
            Notes.NOTES_SEED,
        )
        val body = Notes.seedRequest("hi").toString()
        assertEquals("""{"requests":[{"insertText":{"text":"hi","location":{"index":1}}}]}""", body)
    }

    @Test
    fun seededContextPlaceholderIsNotBackground() {
        assertNull(Notes.extractContext(Notes.CONTEXT_SEED))
        assertEquals(
            "=== WRIST CONTEXT | 2026-10-16 06:46 ===\n- Garden plan",
            Notes.extractContext("=== WRIST CONTEXT | 2026-10-16 06:46 ===\n- Garden plan\n=== END ==="),
        )
    }

    @Test
    fun driveListQueryAndIds() {
        assertEquals(
            "name='Wrist Assistant Notes' and mimeType='application/vnd.google-apps.document' and trashed=false",
            Notes.driveListQuery(Notes.NOTES_TITLE),
        )
        assertTrue(Notes.driveListQuery("it's").contains("name='it\\'s'"))
        val j = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"files":[{"id":"newer","name":"x"},{"id":"older"},{"name":"noid"}]}""",
        ) as kotlinx.serialization.json.JsonObject
        assertEquals(listOf("newer", "older"), Notes.fileIds(j))
        assertEquals(emptyList<String>(), Notes.fileIds(null))
    }

    @Test
    fun driveApiDisabledNamesDrive() {
        val body = """{"error":{"code":403,"message":"Google Drive API has not been used in project 1 before or it is disabled.","status":"PERMISSION_DENIED","details":[{"reason":"SERVICE_DISABLED","metadata":{"service":"drive.googleapis.com"}}]}}"""
        assertEquals(Notes.MSG_DRIVE_DISABLED, Notes.forbiddenMessage(body))
    }

    @Test
    fun blankDocDetection() {
        val blank = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"body":{"content":[{"paragraph":{"elements":[{"textRun":{"content":"\n"}}]}}]}}""",
        ) as kotlinx.serialization.json.JsonObject
        assertTrue(Notes.isBlankDoc(blank))
        assertFalse(Notes.isBlankDoc(kotlinx.serialization.json.Json.parseToJsonElement(
            """{"body":{"content":[{"paragraph":{"elements":[{"textRun":{"content":"Title\n"}}]}}]}}""",
        ) as kotlinx.serialization.json.JsonObject))
    }

    @Test
    fun noteEntryFormat() {
        val e = Notes.noteEntry("Buy more coffee filters.", followup = false, project = "kitchen", now = now)
        assertEquals("\n=== OPEN | 2026-10-15 17:49 | NOTE | kitchen ===\nBuy more coffee filters.\n", e)
        val f = Notes.noteEntry("look into it", followup = true, project = null, now = now)
        assertTrue(f.contains("=== OPEN | 2026-10-15 17:49 | FOLLOWUP |  ===\n"))
    }

    @Test
    fun headerFieldsCantBreakTheFormat() {
        val e = Notes.noteEntry("line one\n=== DONE | fake ===\nline three", false, "a | b\n=== c", now)
        val headers = e.lines().filter { it.startsWith("===") }
        assertEquals(1, headers.size)
        assertEquals("=== OPEN | 2026-10-15 17:49 | NOTE | a / b c ===", headers[0])
        assertTrue(e.contains("- DONE | fake ==="))
    }

    @Test
    fun handoffEntryHasFieldsAndExactConversation() {
        val convo = listOf(
            ChatMessage("user", "which campsites are within two hours' drive"),
            ChatMessage("assistant", "Lakeside Camp, Pine Ridge, Fox Hollow. " + "x".repeat(800)),
        )
        val e = Notes.handoffEntry("Weekend trip", "Trip planning", "Pick a campsite", "Have a short list", "Get prices", convo, now)
        val lines = e.trim().lines()
        assertEquals("=== OPEN | 2026-10-15 17:49 | HANDOFF | Trip planning ===", lines[0])
        assertEquals("Title: Weekend trip", lines[1])
        assertEquals("Goal: Pick a campsite", lines[2])
        assertEquals("Where it stands: Have a short list", lines[3])
        assertEquals("Next step: Get prices", lines[4])
        assertEquals("Conversation:", lines[5])
        assertEquals("Q: which campsites are within two hours' drive", lines[6])
        assertTrue(lines[7].startsWith("A: Lakeside Camp, Pine Ridge"))
        assertEquals(3 + Notes.HANDOFF_ANSWER_CHARS, lines[7].length)
    }

    @Test
    fun handoffUsesTitleWhenNoProjectAndKeepsLastSix() {
        val convo = (1..10).map { ChatMessage(if (it % 2 == 1) "user" else "assistant", "m$it") }
        val e = Notes.handoffEntry("Title", null, "g", "s", "n", convo, now)
        assertTrue(e.contains("| HANDOFF | Title ==="))
        assertFalse(e.contains("Q: m3\n"))
        assertTrue(e.contains("Q: m5\n") && e.contains("A: m10\n"))
    }

    @Test
    fun appendRequestShape() {
        assertEquals(
            """{"requests":[{"insertText":{"text":"hi","endOfSegmentLocation":{}}}]}""",
            Notes.appendRequest("hi").toString(),
        )
    }

    private val doc = Json.parseToJsonElement(
        """
        {"body":{"content":[
          {"sectionBreak":{}},
          {"paragraph":{"elements":[{"textRun":{"content":"=== WRIST CONTEXT | 2026-10-15 18:35 ===\n"}}]}},
          {"paragraph":{"elements":[{"textRun":{"content":"Active projects:\n"}}]}},
          {"paragraph":{"elements":[{"textRun":{"content":"- Wrist "}},{"textRun":{"content":"Assistant: watch app — adding notes\n"}}]}},
          {"paragraph":{"elements":[{"textRun":{"content":"=== END ===\n"}}]}},
          {"paragraph":{"elements":[{"textRun":{"content":"ignored after end\n"}}]}}
        ]}}
        """.trimIndent()
    ) as JsonObject

    @Test
    fun plainTextAndContextBlock() {
        val text = Notes.plainText(doc)
        assertTrue(text.startsWith("=== WRIST CONTEXT"))
        val c = Notes.extractContext(text)!!
        assertEquals("=== WRIST CONTEXT | 2026-10-15 18:35 ===\nActive projects:\n- Wrist Assistant: watch app - adding notes", c)
        assertNull(Notes.extractContext("no markers here"))
        assertTrue(Notes.extractContext("=== WRIST CONTEXT | x ===\n" + "a".repeat(5000))!!.length <= Notes.MAX_CONTEXT_CHARS)
    }

    @Test
    fun openCountCountsOpenAndWaiting() {
        val t = "=== OPEN | a | NOTE |  ===\nx\n=== DONE | b | NOTE |  ===\n=== WAITING | c | HANDOFF | p ===\n"
        assertEquals(2, Notes.openCount(t))
    }

    @Test
    fun settingsRoundTripNotesFields() {
        val s = AppSettings.validate(AppSettings(notesDoc = id, contextDoc = "junk", docsAppCreated = true))
        assertEquals(id, s.notesDoc)
        assertEquals("", s.contextDoc)
        val round = AppSettings.fromJson(s.copy(useContext = false, handoffNotification = false).toJson())
        assertFalse(round.useContext)
        assertFalse(round.handoffNotification)
        assertEquals(id, round.notesDoc)
    }

    @Test
    fun promptOffersNotesToolsAndBackgroundOnlyWhenAvailable() {
        val s = AppSettings(webSearch = false)
        val off = Prompt.buildSystemPrompt(s, now, ToolAvailability(), null)
        assertTrue(off.contains("need a Google account"))
        assertFalse(Prompt.toolNames(Prompt.buildTools(s, ToolAvailability())).contains("save_note"))
        val on = Prompt.buildSystemPrompt(s, now, ToolAvailability(notes = true), "=== WRIST CONTEXT ===\n- Garden plan")
        assertTrue(on.contains("call create_handoff"))
        assertTrue(on.endsWith("=== WRIST CONTEXT ===\n- Garden plan") || on.contains("=== WRIST CONTEXT ===\n- Garden plan\n"))
        val names = Prompt.toolNames(Prompt.buildTools(s, ToolAvailability(notes = true)))
        assertTrue(names.containsAll(listOf("save_note", "create_handoff")))
    }

    @Test
    fun forbiddenMessages() {
        val disabled = """{"error":{"code":403,"message":"Google Docs API has not been used in project 123 before or it is disabled.","status":"PERMISSION_DENIED","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"SERVICE_DISABLED","domain":"googleapis.com"}]}}"""
        assertEquals(Notes.MSG_SERVICE_DISABLED, Notes.forbiddenMessage(disabled))
        val scope = """{"error":{"code":403,"message":"Request had insufficient authentication scopes.","status":"PERMISSION_DENIED","details":[{"reason":"ACCESS_TOKEN_SCOPE_INSUFFICIENT"}]}}"""
        assertEquals(Notes.MSG_SCOPE_MISSING, Notes.forbiddenMessage(scope))
        val scopeNoDetails = """{"error":{"code":403,"message":"Request had insufficient authentication scopes.","status":"PERMISSION_DENIED"}}"""
        assertEquals(Notes.MSG_SCOPE_MISSING, Notes.forbiddenMessage(scopeNoDetails))
        val denied = """{"error":{"code":403,"message":"The caller does not have permission","status":"PERMISSION_DENIED"}}"""
        assertEquals(Notes.MSG_PERMISSION_DENIED, Notes.forbiddenMessage(denied))
        val unknown = """{"error":{"code":403,"message":"Quota thing","status":"RESOURCE_EXHAUSTED"}}"""
        assertEquals(Notes.MSG_FORBIDDEN_GENERIC, Notes.forbiddenMessage(unknown))
        assertEquals(Notes.MSG_FORBIDDEN_GENERIC, Notes.forbiddenMessage("<html>nope</html>"))
        assertEquals(Notes.MSG_FORBIDDEN_GENERIC, Notes.forbiddenMessage(null))
        assertEquals(Notes.MSG_FORBIDDEN_GENERIC, Notes.forbiddenMessage("""{"error":"x"}"""))
    }
}
