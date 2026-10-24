package com.mrarron.wristclaude.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class QuickTopicsTest {
    @Test
    fun wordListIsBigAndUnique() {
        assertTrue(QuickTopics.WORDS.size >= 800)
        assertEquals(QuickTopics.WORDS.size, QuickTopics.WORDS.map { it.lowercase() }.toSet().size)
        assertTrue(QuickTopics.WORDS.none { it.isBlank() })
        assertTrue(QuickTopics.WORDS.size * QuickTopics.ANGLES.size >= 10_000)
    }

    @Test
    fun promptsWithoutTopicAreUnchanged() {
        assertEquals("Motivate me", QuickTopics.expand("Motivate me", listOf("x")))
        assertFalse(QuickTopics.hasTopic("Motivate me"))
    }

    @Test
    fun expandFillsWordAngleAndAvoidList() {
        val out = QuickTopics.expand("Fun fact about {topic}.", listOf("Honey\nnever spoils.", "z".repeat(300)), Random(1))
        assertFalse(out.contains("{topic}"))
        assertTrue(QuickTopics.ANGLES.any { out.contains(it) })
        assertTrue(QuickTopics.WORDS.any { out.startsWith("Fun fact about $it.") })
        assertTrue(out.contains("Do not repeat any of these: \"Honey never spoils.\""))
        assertTrue(out.length < 600)
        assertFalse(QuickTopics.expand("{topic}").contains("repeat"))
    }

    @Test
    fun expandVaries() {
        val r = Random(42)
        assertTrue((1..50).map { QuickTopics.expand("{topic}", random = r) }.toSet().size > 40)
    }

    @Test
    fun rememberKeepsNewestFive() {
        var r = emptyList<String>()
        for (i in 1..7) r = QuickTopics.remember(r, "fact $i")
        assertEquals(listOf("fact 3", "fact 4", "fact 5", "fact 6", "fact 7"), r)
    }

    @Test
    fun defaultFunFactUsesTopicAndOldDefaultIsUpgraded() {
        assertTrue(QuickTopics.hasTopic(AppSettings().quickPrompts[0].prompt))
        val s = AppSettings.validate(
            AppSettings(quickPrompts = listOf(QuickPrompt("Fun fact", "Tell me a surprising fun fact."), QuickPrompt("Mine", "Tell me a fun fact.")))
        )
        assertEquals("Tell me a surprising fun fact about {topic}.", s.quickPrompts[0].prompt)
        assertEquals("Tell me a fun fact.", s.quickPrompts[1].prompt)
    }

    /** The watch-app JS fallback must use the same lists. */
    @Test
    fun listsMatchTopicsJs() {
        val js = listOf("../../wrist-claude/src/pkjs/topics.js", "../wrist-claude/src/pkjs/topics.js").map(::File).firstOrNull { it.exists() }
        assumeTrue(js != null)
        val src = js!!.readText()
        val wordsBlock = src.substringAfter("var WORDS = (").substringBefore(").split")
        val words = Regex("'((?:[^'\\\\]|\\\\.)*)'").findAll(wordsBlock).joinToString("") { it.groupValues[1].replace("\\'", "'") }.split('|')
        assertEquals(QuickTopics.WORDS, words)
        val anglesBlock = src.substringAfter("var ANGLES = [").substringBefore("];")
        val angles = Regex("'((?:[^'\\\\]|\\\\.)*)'").findAll(anglesBlock).map { it.groupValues[1] }.toList()
        assertEquals(QuickTopics.ANGLES, angles)
    }
}
