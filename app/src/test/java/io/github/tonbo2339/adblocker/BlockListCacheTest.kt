package io.github.tonbo2339.adblocker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 組み立てたブロックリストの保存 (BlockListCache)。壊れると、ブロック全体が効かなくなる。 */
class BlockListCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun setOf(vararg domains: String) = DomainSet.Builder().apply { domains.forEach(::add) }.build()

    private fun file(): File = File(tmp.root, "blocklist.cache")

    @Test
    fun roundTripKeepsBothSets() {
        val f = file()
        BlockListCache.write(f, "key-1", setOf("ads.example.com", "tracker.net"), setOf("ok.example.org"))
        val (blocked, allowed) = BlockListCache.read(f, "key-1")!!
        assertTrue(blocked.matchesSelfOrParent("x.ads.example.com"))
        assertTrue(blocked.matchesSelfOrParent("tracker.net"))
        assertFalse(blocked.matchesSelfOrParent("example.org"))
        assertTrue(allowed.matchesSelfOrParent("ok.example.org"))
        assertEquals(2, blocked.size)
        assertEquals(1, allowed.size)
    }

    @Test
    fun emptySetsRoundTrip() {
        val f = file()
        BlockListCache.write(f, "k", DomainSet.EMPTY, DomainSet.EMPTY)
        val (blocked, allowed) = BlockListCache.read(f, "k")!!
        assertEquals(0, blocked.size)
        assertEquals(0, allowed.size)
    }

    @Test
    fun differentKeyIsNotUsed() {
        // 元のリストのファイルや版が変わるとキーが変わる。古い集合を使ってはいけない
        val f = file()
        BlockListCache.write(f, "key-1", setOf("a.example.com"), DomainSet.EMPTY)
        assertNull(BlockListCache.read(f, "key-2"))
        assertNotNull(BlockListCache.read(f, "key-1"))
    }

    @Test
    fun missingOrBrokenFileIsNull() {
        assertNull(BlockListCache.read(file(), "k")) // まだ無い
        val f = file()
        f.writeBytes(ByteArray(0))
        assertNull(BlockListCache.read(f, "k")) // 空
        f.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)) // 形式が違う
        assertNull(BlockListCache.read(f, "k"))
    }

    @Test
    fun truncatedFileIsNull() {
        val f = file()
        BlockListCache.write(f, "k", setOf("a.example.com", "b.example.com", "c.example.com"), setOf("d.example.com"))
        val whole = f.readBytes()
        // 書き込み中に電源が落ちた・容量が足りなかった、などで途中で切れたファイル
        for (cut in listOf(3, 8, whole.size / 2, whole.size - 1)) {
            f.writeBytes(whole.copyOf(cut))
            assertNull("cut at $cut", BlockListCache.read(f, "k"))
        }
    }

    @Test
    fun writeLeavesNoTemporaryFile() {
        val f = file()
        BlockListCache.write(f, "k", setOf("a.example.com"), DomainSet.EMPTY)
        assertTrue(f.exists())
        assertFalse(File(f.path + ".tmp").exists())
    }
}
