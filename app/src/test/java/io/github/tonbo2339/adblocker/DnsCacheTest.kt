package io.github.tonbo2339.adblocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DnsCacheTest {

    private fun name(vararg labels: String): ByteArray =
        labels.flatMap { listOf(it.length.toByte()) + it.toByteArray().toList() }.toByteArray() + byteArrayOf(0)

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    private fun query(id: Int, type: Int = 1) =
        u16(id) + u16(0x0100) + u16(1) + u16(0) + u16(0) + u16(0) + name("a", "example", "com") + u16(type) + u16(1)

    /** TTL 60 の A を 1 つ返す応答。 */
    private fun response(type: Int = 1): ByteArray =
        u16(0x1111) + u16(0x8180) + u16(1) + u16(1) + u16(0) + u16(0) + name("a", "example", "com") + u16(type) + u16(1) +
            byteArrayOf(0xC0.toByte(), 12) + u16(type) + u16(1) + byteArrayOf(0, 0, 0, 60) + u16(4) + byteArrayOf(1, 2, 3, 4)

    @Test
    fun hitUntilTtlExpires() {
        var now = 1_000_000L
        val cache = DnsCache(now = { now })
        val q1 = query(0x2222)
        cache.put(q1, Dns.parseQuestion(q1)!!, response())

        now += 30_000
        val q2 = query(0x3333)
        val hit = cache.get(q2, Dns.parseQuestion(q2)!!)
        assertNotNull(hit)
        assertEquals(0x3333, u16(hit!!, 0))

        // 種類 (AAAA) が違う問い合わせには当たらない
        val q3 = query(0x4444, type = 28)
        assertNull(cache.get(q3, Dns.parseQuestion(q3)!!))

        now += 31_000
        assertNull(cache.get(q2, Dns.parseQuestion(q2)!!))
    }

    @Test
    fun oldestEntriesAreDropped() {
        val cache = DnsCache(maxEntries = 1)
        val q = query(1)
        cache.put(q, Dns.parseQuestion(q)!!, response())
        val other = query(2, type = 28)
        cache.put(other, Dns.parseQuestion(other)!!, response(type = 28))
        assertEquals(1, cache.size)
        assertNull(cache.get(q, Dns.parseQuestion(q)!!))
    }
}
