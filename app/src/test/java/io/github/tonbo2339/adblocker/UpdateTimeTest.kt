package io.github.tonbo2339.adblocker

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class UpdateTimeTest {

    private fun at(hour: Int, minute: Int) = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 5, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }

    @Test
    fun delayUntilNextOccurrence() {
        val hour = 60 * 60 * 1000L
        // 22:00 から見た 4:00 は翌日なので 6 時間後
        assertEquals(6 * hour, UpdateConstraints.delayUntil(4 * 60, at(22, 0)))
        // 3:30 から見た 4:00 は 30 分後
        assertEquals(hour / 2, UpdateConstraints.delayUntil(4 * 60, at(3, 30)))
        // ちょうどその時刻なら、次は 24 時間後
        assertEquals(24 * hour, UpdateConstraints.delayUntil(4 * 60, at(4, 0)))
    }
}
