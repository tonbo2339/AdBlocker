package io.github.tonbo2339.adblocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleTextTest {

    @Test
    fun formatThenParseGivesTheSameRules() {
        val block = setOf("ads.example.com", "*tracker*", "203.0.113.0/24")
        val allow = setOf("good.example.org")
        val parsed = RuleText.parse(RuleText.format(block, allow))
        assertEquals(block, parsed.block)
        assertEquals(allow, parsed.allow)
        assertEquals(0, parsed.skipped)
    }

    @Test
    fun readsHostsFilesAndComments() {
        val text = """
            # my hosts
            127.0.0.1 localhost
            ::1 localhost
            0.0.0.0 ads.example.com  # inline comment
            0.0.0.0 0.0.0.0
            ! adblock-style comment
            tracker.example.net
            @@Keep.Example.com
        """.trimIndent()
        val parsed = RuleText.parse(text)
        assertEquals(setOf("ads.example.com", "tracker.example.net"), parsed.block)
        assertEquals(setOf("keep.example.com"), parsed.allow)
        assertEquals(1, parsed.skipped) // "0.0.0.0 0.0.0.0"
    }

    @Test
    fun doesNotTurnHostsPlaceholdersIntoIpRules() {
        val parsed = RuleText.parse("0.0.0.0\n127.0.0.1\n::\n::1\n0.0.0.0 example.com")
        assertEquals(setOf("example.com"), parsed.block)
        assertEquals(0, parsed.skipped) // 読めたうえで、ルールにしない
    }

    @Test
    fun allowWinsWhenTheSameRuleIsInBoth() {
        val parsed = RuleText.parse("example.com\n@@example.com")
        assertTrue(parsed.block.isEmpty())
        assertEquals(setOf("example.com"), parsed.allow)
    }

    @Test
    fun countsUnreadableLinesAndStopsAtTheLimit() {
        val parsed = RuleText.parse("not a rule here\nbad_\nok.example.com")
        assertEquals(setOf("ok.example.com"), parsed.block)
        assertEquals(2, parsed.skipped)

        val many = (1..RuleText.MAX_RULES + 10).joinToString("\n") { "d$it.example.com" }
        val limited = RuleText.parse(many)
        assertEquals(RuleText.MAX_RULES, limited.block.size)
        assertTrue(limited.tooMany)
    }
}
