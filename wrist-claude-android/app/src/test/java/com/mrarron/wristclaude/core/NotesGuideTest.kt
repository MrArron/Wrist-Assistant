package com.mrarron.wristclaude.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "How to handle entries" guide at the top of the notes doc and the Duet instruction. */
class NotesGuideTest {
    private fun doc(lines: List<String>): JsonObject {
        val sb = StringBuilder("""{"revisionId":"r9","body":{"content":[{"endIndex":1,"sectionBreak":{}}""")
        var idx = 1
        lines.forEach { l ->
            val text = "$l\n"
            val end = idx + text.length
            sb.append(""",{"startIndex":$idx,"endIndex":$end,"paragraph":{"elements":[{"startIndex":$idx,"endIndex":$end,"textRun":{"content":${Json.encodeToString(kotlinx.serialization.serializer<String>(), text)}}}]}}""")
            idx = end
        }
        return Json.parseToJsonElement(sb.append("]}}").toString()) as JsonObject
    }

    @Test fun guideIsNeverMistakenForAnEntry() {
        Notes.GUIDE.lines().forEach { assertFalse(it, it.trimStart().startsWith("===")) }
        assertEquals(0, Notes.openCount(Notes.NOTES_SEED))
        assertTrue(NotesTidy.doneRanges(Json.encodeToString(JsonObject.serializer(), doc(Notes.NOTES_SEED.lines()))).isEmpty())
        assertTrue(Notes.hasGuide(Notes.NOTES_SEED))
        assertFalse(Notes.hasGuide("Wrist Assistant Notes\nIntro\n"))
    }

    @Test fun guideKeepsTheTidyLayoutOfTitleAndIntro() {
        val lines = Notes.NOTES_SEED.lines()
        assertEquals(Notes.NOTES_TITLE, lines[0])
        assertEquals(Notes.NOTES_INTRO, lines[1])
        assertEquals("", lines[2])
    }

    @Test fun insertGoesBeforeTheFirstEntry() {
        val lines = listOf("Wrist Assistant Notes", "Intro.", "", "=== OPEN | 2026-10-15 17:49 | NOTE | x ===", "a", "")
        // 1 + "Wrist Assistant Notes\n" (22) + "Intro.\n" (7) + "\n" (1)
        assertEquals(31, Notes.guideInsertIndex(doc(lines)))
        val done = listOf("t", "=== DONE | 2026-10-15 18:02 | NOTE | x ===", "a")
        assertEquals(3, Notes.guideInsertIndex(doc(done)))
        assertNull(Notes.guideInsertIndex(doc(listOf("Wrist Assistant Notes", "Intro."))))
        assertNull(Notes.guideInsertIndex(null))
    }

    @Test fun guideRequestShapes() {
        val at = Notes.guideRequest(31, "r9")
        val ins = at["requests"]!!.jsonArray[0].jsonObject["insertText"]!!.jsonObject
        assertEquals("31", ins["location"]!!.jsonObject["index"]!!.jsonPrimitive.content)
        assertEquals(Notes.GUIDE + "\n\n", ins["text"]!!.jsonPrimitive.content)
        assertEquals("r9", at["writeControl"]!!.jsonObject["requiredRevisionId"]!!.jsonPrimitive.content)

        val end = Notes.guideRequest(null, "r9")
        val ins2 = end["requests"]!!.jsonArray[0].jsonObject["insertText"]!!.jsonObject
        assertTrue(ins2.containsKey("endOfSegmentLocation"))
        assertNull(end["writeControl"])
    }

    @Test fun instructionNamesTheDocAndTheRules() {
        val url = Notes.docUrl("1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789abcdeEF")
        val all = Notes.assistantInstruction(url, onlyAddressed = false)
        assertTrue(all.contains(url))
        assertTrue(all.contains(Notes.GUIDE_MARKER))
        assertTrue(all.contains("OPEN or WAITING"))
        assertFalse(all.contains("Only handle entries"))
        val only = Notes.assistantInstruction(null, onlyAddressed = true)
        assertTrue(only.contains("titled \"${Notes.NOTES_TITLE}\""))
        assertTrue(only.contains("project is \"${Notes.DUET}\""))
    }
}
