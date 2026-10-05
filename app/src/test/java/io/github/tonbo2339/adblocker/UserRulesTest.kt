package io.github.tonbo2339.adblocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserRulesTest {

    @Test
    fun normalizeAcceptsCommonForms() {
        assertEquals("example.com", UserRules.normalize("example.com"))
        assertEquals("example.com", UserRules.normalize("  Example.COM. "))
        assertEquals("ads.example.com", UserRules.normalize("https://ads.example.com/path?q=1#x"))
        assertEquals("ads.example.com", UserRules.normalize("http://user@ads.example.com:8080/"))
        assertEquals("example.com", UserRules.normalize("||example.com^"))
        assertEquals("example.com", UserRules.normalize("@@||example.com^"))
        assertEquals("example.com", UserRules.normalize("*.example.com"))
        assertEquals("_dmarc.example.com", UserRules.normalize("_dmarc.example.com"))
    }

    @Test
    fun normalizeRejectsInvalid() {
        assertNull(UserRules.normalize(""))
        assertNull(UserRules.normalize("localhost"))
        assertNull(UserRules.normalize("bad..example.com"))
        assertNull(UserRules.normalize("-bad.example.com"))
        assertNull(UserRules.normalize("bad-.example.com"))
        assertNull(UserRules.normalize("exa mple.com"))
        assertNull(UserRules.normalize("a".repeat(64) + ".com"))
        assertNull(UserRules.normalize("a.".repeat(127) + "com")) // 253 文字を超える
    }

    @Test
    fun wildcards() {
        assertEquals("ads.*", UserRules.normalize("ads.*"))
        assertEquals("*tracker*", UserRules.normalize("*TRACKER*"))
        assertEquals("*.ads.*", UserRules.normalize("*.ads.*"))
        // 何にでも当たるものは受け付けない
        assertNull(UserRules.normalize("*"))
        assertNull(UserRules.normalize("*.*"))
        assertNull(UserRules.normalize("ads..*"))
    }

    @Test
    fun ipAddresses() {
        assertEquals("203.0.113.7", UserRules.normalize("203.0.113.7"))
        assertEquals("203.0.113.0/24", UserRules.normalize("203.0.113.0/24"))
        assertEquals("203.0.113.7", UserRules.normalize("https://203.0.113.7/path"))
        assertNotNull(UserRules.normalize("2001:db8::1"))
        assertNotNull(UserRules.normalize("[2001:db8::1]"))
        assertNull(UserRules.normalize("203.0.113.7", allowIp = false))
        assertNull(UserRules.normalize("256.1.1.1/8"))
        assertNull(UserRules.normalize("203.0.113.0/33"))
    }

    @Test
    fun ipRuleMatchesRange() {
        val range = IpRule.parse("203.0.113.0/24")!!
        assertTrue(range.contains(byteArrayOf(203.toByte(), 0, 113, 99)))
        assertFalse(range.contains(byteArrayOf(203.toByte(), 0, 114, 1)))
        val one = IpRule.parse("203.0.113.7")!!
        assertTrue(one.contains(byteArrayOf(203.toByte(), 0, 113, 7)))
        assertFalse(one.contains(byteArrayOf(203.toByte(), 0, 113, 8)))
        // IPv4 のルールは IPv6 のアドレスには当たらない
        assertFalse(one.contains(ByteArray(16)))
        val odd = IpRule.parse("10.0.0.0/9")!!
        assertTrue(odd.contains(byteArrayOf(10, 127, 0, 1)))
        assertFalse(odd.contains(byteArrayOf(10, (-128).toByte(), 0, 1)))
    }

    @Test
    fun wildcardMatching() {
        assertTrue(UserRules.wildcardMatches("ads.*", "ads.example.com"))
        assertFalse(UserRules.wildcardMatches("ads.*", "x.ads.example.com"))
        assertTrue(UserRules.wildcardMatches("*tracker*", "cdn.tracker-1.example"))
        assertTrue(UserRules.wildcardMatches("*.ads.*", "x.ads.example.com"))
        assertFalse(UserRules.wildcardMatches("*.ads.*", "ads.example.com"))
        assertTrue(UserRules.wildcardMatches("a*b*c", "a.b.c"))
        assertFalse(UserRules.wildcardMatches("a*b*c", "a.b.d"))
        // * の多いパターンでもすぐ終わる (正規表現だと極端に遅くなる形)
        val started = System.nanoTime()
        assertFalse(UserRules.wildcardMatches("*a".repeat(60) + "*b", "a".repeat(250)))
        assertTrue(System.nanoTime() - started < 1_000_000_000L)
    }
}
