package io.github.tonbo2339.adblocker

import org.junit.Assert.assertEquals
import org.junit.Test

class ListBreakdownTest {

    private val counts = mapOf("ads.example.com" to 3, "tracker.net" to 2, "sub.tracker.net" to 4, "other.org" to 5)

    @Test
    fun countsDomainsListedDirectlyOrByParent() {
        val lines = sequenceOf("# comment", "ads.example.com", "tracker.net", "", "unrelated.io")
        // ads.example.com (3) + tracker.net (2) + 親の tracker.net に当たる sub.tracker.net (4)
        assertEquals(9, ListBreakdown.count(lines, ListBreakdown.index(counts), counts))
    }

    @Test
    fun ignoresAllowLinesAndCountsEachNameOnce() {
        val lines = sequenceOf("@@other.org", "ads.example.com", "example.com")
        assertEquals(3, ListBreakdown.count(lines, ListBreakdown.index(counts), counts))
    }

    @Test
    fun emptyListCountsNothing() {
        assertEquals(0, ListBreakdown.count(emptySequence(), ListBreakdown.index(counts), counts))
    }
}
