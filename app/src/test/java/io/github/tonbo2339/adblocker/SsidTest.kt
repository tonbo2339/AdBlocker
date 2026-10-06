package io.github.tonbo2339.adblocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SsidTest {

    @Test
    fun quotesAreRemoved() {
        assertEquals("MyWifi", SsidMonitor.clean("\"MyWifi\""))
        assertEquals("自宅 5G", SsidMonitor.clean("\"自宅 5G\""))
    }

    @Test
    fun hexNamesAreKept() {
        // UTF-8 でない名前は引用符なしの 16 進数で来る
        assertEquals("e6b8a9", SsidMonitor.clean("e6b8a9"))
    }

    @Test
    fun unknownIsNull() {
        assertNull(SsidMonitor.clean(null))
        assertNull(SsidMonitor.clean("<unknown ssid>"))
        assertNull(SsidMonitor.clean("\"\""))
    }
}
