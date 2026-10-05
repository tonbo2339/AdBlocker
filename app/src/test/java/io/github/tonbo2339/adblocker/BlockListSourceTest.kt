package io.github.tonbo2339.adblocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockListSourceTest {

    @Test
    fun customSourceIdIsStableAndSafe() {
        val a = BlockListUpdater.custom("https://example.com/list.txt")
        assertEquals(a.id, BlockListUpdater.custom("https://example.com/list.txt").id)
        assertNotEquals(a.id, BlockListUpdater.custom("https://example.com/other.txt").id)
        // ファイル名に使うので英数字とハイフンだけ。組み込みの id とも重ならない
        assertTrue(a.id.matches(Regex("custom-[0-9a-f]{12}")))
        assertTrue(BlockListUpdater.BUILT_IN.none { it.id == a.id })
        assertEquals("example.com", a.name)
        assertFalse(a.builtIn)
    }

    @Test
    fun onlyHttpsUrlsCanBeAdded() {
        assertEquals("https://example.com/list.txt", BlockListUpdater.normalizeUrl("  https://example.com/list.txt "))
        assertEquals("HTTPS://example.com/a", BlockListUpdater.normalizeUrl("HTTPS://example.com/a"))
        assertNull(BlockListUpdater.normalizeUrl("http://example.com/list.txt"))
        assertNull(BlockListUpdater.normalizeUrl("https:///list.txt"))
        assertNull(BlockListUpdater.normalizeUrl("example.com/list.txt"))
        assertNull(BlockListUpdater.normalizeUrl("https://exa mple.com/"))
    }

    @Test
    fun builtInIdsAreUnique() {
        val ids = BlockListUpdater.BUILT_IN.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(BlockList.BUNDLED_SOURCE_ID in ids)
    }
}
