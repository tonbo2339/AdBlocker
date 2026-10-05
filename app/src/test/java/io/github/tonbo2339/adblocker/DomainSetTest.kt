package io.github.tonbo2339.adblocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainSetTest {

    private fun setOf(vararg domains: String) =
        DomainSet.Builder().apply { domains.forEach(::add) }.build()

    @Test
    fun matchesSelfAndParents() {
        val set = setOf("doubleclick.net", "ads.example.com")
        assertTrue(set.matchesSelfOrParent("doubleclick.net"))
        assertTrue(set.matchesSelfOrParent("stats.g.doubleclick.net"))
        assertTrue(set.matchesSelfOrParent("ads.example.com."))
        assertTrue(set.matchesSelfOrParent("x.ads.example.com"))
        assertFalse(set.matchesSelfOrParent("example.com"))
        assertFalse(set.matchesSelfOrParent("notdoubleclick.net"))
        assertFalse(set.matchesSelfOrParent("doubleclick.net.evil.com"))
        assertFalse(set.matchesSelfOrParent(""))
    }

    @Test
    fun containsIsExact() {
        val set = setOf("example.com")
        assertTrue("example.com" in set)
        assertFalse("www.example.com" in set)
    }

    @Test
    fun deduplicatesAndGrows() {
        val builder = DomainSet.Builder()
        repeat(5000) { builder.add("host$it.example.com") }
        repeat(5000) { builder.add("host$it.example.com") }
        val set = builder.build()
        assertEquals(5000, set.size)
        assertTrue(set.matchesSelfOrParent("a.host4999.example.com"))
        assertFalse(set.matchesSelfOrParent("host5000.example.com"))
    }

    @Test
    fun loadsBundledBlocklist() {
        // 単体テストはモジュールのディレクトリで実行される
        val lines = java.io.File("src/main/assets/blocklist.txt").readLines()
        val domains = lines.filter { it.isNotBlank() && !it.startsWith("#") }
        val set = DomainSet.Builder().apply { domains.forEach(::add) }.build()
        assertEquals(domains.toSet().size, set.size)
        assertTrue(set.size > 50_000)
        // 同梱リストのドメインはどれも RuleParser で読める形式
        assertTrue(domains.all { RuleParser.parse(it) is Rule.Block })
        assertTrue(set.matchesSelfOrParent("sub." + domains.first()))
    }

    @Test
    fun emptySetMatchesNothing() {
        assertFalse(DomainSet.EMPTY.matchesSelfOrParent("example.com"))
        assertEquals(0, DomainSet.EMPTY.size)
    }
}
