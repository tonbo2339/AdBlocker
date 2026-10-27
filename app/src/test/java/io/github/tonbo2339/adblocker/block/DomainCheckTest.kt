package io.github.tonbo2339.adblocker.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainCheckTest {
    private val candidates = setOf("ads.example.com", "example.com", "com")

    @Test
    fun findsSelfAndParentRules() {
        val lines = sequenceOf("# comment", "", "other.net", "example.com", "@@ads.example.com", "x.example.com")
        val hits = DomainCheck.scan(lines, candidates, "List")
        assertEquals(listOf("example.com" to false, "ads.example.com" to true), hits.map { it.domain to it.allow })
        assertTrue(hits.all { it.source == "List" })
    }

    @Test
    fun noHitsForUnrelatedDomain() {
        assertTrue(DomainCheck.scan(sequenceOf("other.net", "@@other.org"), candidates, "List").isEmpty())
    }
}
