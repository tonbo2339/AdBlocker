package io.github.tonbo2339.adblocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleParserTest {

    private fun block(d: String) = Rule.Block(d)
    private fun allow(d: String) = Rule.Allow(d)

    @Test
    fun hostsFormat() {
        assertEquals(block("ads.example.com"), RuleParser.parse("0.0.0.0 ads.example.com"))
        assertEquals(block("ads.example.com"), RuleParser.parse("127.0.0.1\tAds.Example.com  # comment"))
        assertNull(RuleParser.parse("0.0.0.0 0.0.0.0"))
        assertNull(RuleParser.parse("127.0.0.1 localhost"))
        assertNull(RuleParser.parse("192.168.1.1 router.example.com")) // ブロック用アドレス以外は無視
    }

    @Test
    fun plainDomains() {
        assertEquals(block("tracker.example.net"), RuleParser.parse("tracker.example.net"))
        assertEquals(block("example.com"), RuleParser.parse("example.com."))
        assertNull(RuleParser.parse("nodot"))
        assertNull(RuleParser.parse("bad..example.com"))
        assertNull(RuleParser.parse("-bad.example.com"))
    }

    @Test
    fun adblockFormat() {
        assertEquals(block("ads.example.com"), RuleParser.parse("||ads.example.com^"))
        assertEquals(block("ads.example.com"), RuleParser.parse("||ads.example.com^\$important"))
        assertEquals(block("ads.example.com"), RuleParser.parse("||ads.example.com^|"))
        assertEquals(allow("cdn.example.com"), RuleParser.parse("@@||cdn.example.com^"))
        assertEquals(allow("cdn.example.com"), RuleParser.parse("@@||cdn.example.com^|"))
        // DNS 単位で判定できないルールは無視
        assertNull(RuleParser.parse("||ads.example.com^\$client=192.168.1.2"))
        assertNull(RuleParser.parse("||*.example.com^"))
        assertNull(RuleParser.parse("/^ad[0-9]+\\.example\\.com$/"))
        assertNull(RuleParser.parse("||example.com/path"))
    }

    @Test
    fun commentsAndBlanks() {
        assertNull(RuleParser.parse(""))
        assertNull(RuleParser.parse("   "))
        assertNull(RuleParser.parse("# comment"))
        assertNull(RuleParser.parse("! Title: AdGuard DNS filter"))
        assertNull(RuleParser.parse("[Adblock Plus 2.0]"))
    }

    @Test
    fun formatRoundTrips() {
        for (rule in listOf(block("a.example.com"), allow("b.example.com"))) {
            assertEquals(rule, RuleParser.parse(RuleParser.format(rule)))
        }
    }
}
