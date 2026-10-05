package io.github.tonbo2339.adblocker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    @Test
    fun comparesNumerically() {
        assertTrue(Versions.isNewer("1.3", "1.2"))
        assertTrue(Versions.isNewer("1.10", "1.9"))
        assertTrue(Versions.isNewer("2.0", "1.99"))
        assertTrue(Versions.isNewer("1.2.1", "1.2"))
        assertTrue(Versions.isNewer("v1.4", "1.3"))
    }

    @Test
    fun sameOrOlderIsNotNewer() {
        assertFalse(Versions.isNewer("1.2", "1.2"))
        assertFalse(Versions.isNewer("1.2.0", "1.2"))
        assertFalse(Versions.isNewer("1.1", "1.2"))
        assertFalse(Versions.isNewer("1.9", "1.10"))
    }

    @Test
    fun toleratesSuffixes() {
        assertTrue(Versions.isNewer("1.3-beta", "1.2"))
        assertFalse(Versions.isNewer("garbage", "1.0"))
    }
}
