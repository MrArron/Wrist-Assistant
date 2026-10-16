package com.mrarron.wristclaude.core

import com.mrarron.wristclaude.core.NotesTidy.DeleteRange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesTidyTest {
    /** Builds a Docs-shaped documents.get response; each string is one paragraph (without its newline). */
    private fun doc(lines: List<String>, table: Pair<Int, Int>? = null, rev: String = "rev1"): String {
        val sb = StringBuilder("""{"revisionId":"$rev","body":{"content":[{"endIndex":1,"sectionBreak":{}}""")
        var idx = 1
        lines.forEachIndexed { i, l ->
            if (table != null && table.first == i) {
                sb.append(""",{"startIndex":$idx,"endIndex":${idx + table.second},"table":{"rows":1}}""")
                idx += table.second
            }
            val text = "$l\n"
            val end = idx + text.length
            if (l == "<<IMG>>") {
                sb.append(""",{"startIndex":$idx,"endIndex":$end,"paragraph":{"elements":[{"startIndex":$idx,"endIndex":${end - 1},"inlineObjectElement":{}},{"startIndex":${end - 1},"endIndex":$end,"textRun":{"content":"\n"}}]}}""")
                idx = end
                return@forEachIndexed
            }
            sb.append(""",{"startIndex":$idx,"endIndex":$end,"paragraph":{"elements":[{"startIndex":$idx,"endIndex":$end,"textRun":{"content":${Json.encodeToString(kotlinx.serialization.serializer<String>(), text)}}}]}}""")
            idx = end
        }
        return sb.append("]}}").toString()
    }

    private fun start(lines: List<String>, i: Int) = 1 + lines.take(i).sumOf { it.length + 1 }
    private fun total(lines: List<String>) = 1 + lines.sumOf { it.length + 1 }

    private val title = listOf("Wrist Assistant Notes", "Intro text.", "")
    private val open = listOf("=== OPEN | 2026-10-15 17:49 | NOTE | kitchen ===", "buy coffee", "")
    private val done = listOf("=== DONE | 2026-10-15 18:02 | HANDOFF | Car ===", "Goal: x", "Result: done", "")
    private val waiting = listOf("=== WAITING | 2026-10-16 08:00 | FOLLOWUP | ===", "later", "")

    @Test fun noDoneMeansNothing() {
        assertTrue(NotesTidy.doneRanges(doc(title + open + waiting)).isEmpty())
    }

    @Test fun doneInTheMiddleTakesItsBlankLine() {
        val lines = title + open + done + waiting
        assertEquals(listOf(DeleteRange(start(lines, 6), start(lines, 10))), NotesTidy.doneRanges(doc(lines)))
    }

    @Test fun doneAsLastEntryStopsBeforeTheFinalNewline() {
        val lines = title + open + done
        val r = NotesTidy.doneRanges(doc(lines))
        // also takes the blank line written before the entry
        assertEquals(listOf(DeleteRange(start(lines, 5), total(lines) - 1)), r)
    }

    @Test fun severalDoneAreDescending() {
        val lines = title + done + open + done + waiting + done
        val r = NotesTidy.doneRanges(doc(lines))
        assertEquals(3, r.size)
        assertEquals(r.sortedByDescending { it.start }, r)
        assertEquals(DeleteRange(start(lines, 3), start(lines, 7)), r.last())
        assertEquals(total(lines) - 1, r.first().end)
        assertTrue(r.zipWithNext().all { (a, b) -> b.end <= a.start })
    }

    @Test fun openAndWaitingAreNeverTouched() {
        assertTrue(NotesTidy.doneRanges(doc(title + open + waiting + open)).isEmpty())
    }

    @Test fun textMentioningDoneIsNotAHeader() {
        val lines = listOf("Wrist Assistant Notes", "Claude marks entries === DONE | x === when finished. DONE.", "", "=== DONE not a header", "DONE | x", "") + open
        assertTrue(NotesTidy.doneRanges(doc(lines)).isEmpty())
        val lower = listOf("=== done | 2026-10-15 18:02 | NOTE | a ===", "x")
        assertTrue(NotesTidy.doneRanges(doc(title + lower)).isEmpty())
    }

    @Test fun headerWithExtraSpacesStillMatches() {
        val spaced = listOf("  === DONE |  2026-10-15 18:02 |  NOTE |   x  ===   ", "body", "")
        val lines = title + spaced + open
        assertEquals(listOf(DeleteRange(start(lines, 3), start(lines, 6))), NotesTidy.doneRanges(doc(lines)))
    }

    @Test fun emptyProjectHeaderMatches() {
        val lines = title + listOf("=== DONE | 2026-10-15 18:02 | NOTE |  ===", "b", "") + open
        assertEquals(1, NotesTidy.doneRanges(doc(lines)).size)
    }

    @Test fun entryWithATableIsSkipped() {
        val lines = title + open + done + waiting
        assertTrue(NotesTidy.doneRanges(doc(lines, table = 8 to 20)).isEmpty())
    }

    @Test fun rewordedHeaderAfterDoneIsKept() {
        val reworded = listOf("=== WAITING ON BOB | 2026-10-16 08:00 | NOTE | x ===", "later", "")
        val lines = title + done + reworded
        assertEquals(listOf(DeleteRange(start(lines, 3), start(lines, 7))), NotesTidy.doneRanges(doc(lines)))
        val noPipes = title + done + listOf("=== OPEN (snoozed)", "x", "")
        assertEquals(listOf(DeleteRange(start(noPipes, 3), start(noPipes, 7))), NotesTidy.doneRanges(doc(noPipes)))
    }

    @Test fun softBreakHeaderIsKept() {
        val soft = title + done + listOf("=== OPEN | 2026-10-16 08:00 | NOTE | x ===\u000bextra", "later", "")
        assertEquals(listOf(DeleteRange(start(soft, 3), start(soft, 7))), NotesTidy.doneRanges(doc(soft)))
        val doneSoft = title + open + listOf("=== DONE | 2026-10-16 08:00 | NOTE | x ===\u000bextra", "b", "")
        assertTrue(NotesTidy.doneRanges(doc(doneSoft)).isEmpty())
    }

    @Test fun freeTextAfterLastDoneEntryKeepsTheWholeEntry() {
        val lines = title + open + done.dropLast(1) + listOf("", "My own heading", "free text")
        assertTrue(NotesTidy.doneRanges(doc(lines)).isEmpty())
    }

    @Test fun multiParagraphDoneEntryIsSkippedWhereverItIs() {
        val multi = listOf("=== DONE | 2026-10-15 18:02 | NOTE | x ===", "a", "", "b", "")
        assertTrue(NotesTidy.doneRanges(doc(title + open + multi)).isEmpty())
        assertTrue(NotesTidy.doneRanges(doc(title + multi + open)).isEmpty())
    }

    @Test fun resultLineRightAfterBodyIsDeletedWhole() {
        val d = listOf("=== DONE | 2026-10-15 18:02 | NOTE | x ===", "a", "Result: ok", "")
        val lines = title + open + d
        assertEquals(listOf(DeleteRange(start(lines, 5), total(lines) - 1)), NotesTidy.doneRanges(doc(lines)))
    }

    @Test fun lastEntryEndingWithAnImageParagraphIsSkipped() {
        val lines = title + open + listOf("=== DONE | 2026-10-15 18:02 | NOTE | x ===", "a", "", "<<IMG>>", "")
        assertTrue(NotesTidy.doneRanges(doc(lines)).isEmpty())
    }

    @Test fun realLayoutSingleLastEntryLeavesIntroAndOneBlank() {
        val head = "=== DONE | 2026-10-15 18:02 | NOTE | " + "p".repeat(33) + " ==="
        // lengths match the real seed (Notes.NOTES_SEED): 21-char title, 146-char intro
        assertEquals(21, Notes.NOTES_TITLE.length)
        assertEquals(146, Notes.NOTES_SEED.lines()[1].length)
        val lines = listOf("t".repeat(21), "i".repeat(146), "", head, "b".repeat(640), "")
        val r = NotesTidy.doneRanges(doc(lines))
        assertEquals(listOf(DeleteRange(170, 887)), r)
    }

    @Test fun malformedInputGivesNothing() {
        assertTrue(NotesTidy.doneRanges("not json").isEmpty())
        assertTrue(NotesTidy.doneRanges("{}").isEmpty())
        assertTrue(NotesTidy.doneRanges("""{"body":{"content":"x"}}""").isEmpty())
        assertTrue(NotesTidy.doneRanges(null).isEmpty())
        assertTrue(NotesTidy.doneRanges("[1,2").isEmpty())
    }

    @Test fun deleteRequestIsDescendingAndGuardedByRevision() {
        val lines = title + done + open + done
        val d = doc(lines)
        val body = NotesTidy.deleteRequest(NotesTidy.doneRanges(d), NotesTidy.revisionId(d)!!)
        val reqs = body["requests"]!!.jsonArray.map { it.jsonObject["deleteContentRange"]!!.jsonObject["range"]!!.jsonObject }
        val starts = reqs.map { it["startIndex"]!!.jsonPrimitive.content.toInt() }
        assertEquals(starts.sortedDescending(), starts)
        assertEquals("rev1", body["writeControl"]!!.jsonObject["requiredRevisionId"]!!.jsonPrimitive.content)
    }

    @Test fun settingDefaultsOnAndRoundTrips() {
        assertTrue(AppSettings().tidyDone)
        assertEquals(false, AppSettings.fromJson(AppSettings(tidyDone = false).toJson()).tidyDone)
    }
}
