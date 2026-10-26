package io.github.tonbo2339.adblocker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 受け取れる UDP の大きさ・キャッシュのキー・読めない問い合わせへの応答。 */
class DnsLimitsTest {

    @Test
    fun tcpFrameStartsWithLength() {
        assertEquals(listOf<Byte>(0, 3, 1, 2, 3), Dns.tcpFrame(byteArrayOf(1, 2, 3)).toList())
    }

    private fun name(vararg labels: String): ByteArray =
        labels.flatMap { listOf(it.length.toByte()) + it.toByteArray().toList() }.toByteArray() + byteArrayOf(0)

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    /** EDNS なしの A の問い合わせ。 */
    private fun plain(id: Int = 0x1234): ByteArray =
        u16(id) + u16(0x0100) + u16(1) + u16(0) + u16(0) + u16(0) + name("a", "example", "com") + u16(1) + u16(1)

    /** EDNS (OPT) 付きの問い合わせ。udp = UDP payload size、dnssecOk = DO ビット。 */
    private fun withOpt(udp: Int, dnssecOk: Boolean = false, id: Int = 0x1234): ByteArray =
        u16(id) + u16(0x0100) + u16(1) + u16(0) + u16(0) + u16(1) + name("a", "example", "com") + u16(1) + u16(1) +
            byteArrayOf(0) + u16(41) + u16(udp) + byteArrayOf(0, 0, if (dnssecOk) 0x80.toByte() else 0, 0) + u16(0)

    @Test
    fun maxUdpSizeWithoutEdnsIs512() {
        assertEquals(512, Dns.maxUdpSize(plain()))
    }

    @Test
    fun maxUdpSizeReadsOptClass() {
        assertEquals(1232, Dns.maxUdpSize(withOpt(1232)))
        assertEquals(4096, Dns.maxUdpSize(withOpt(4096)))
    }

    @Test
    fun maxUdpSizeBelow512IsRaisedTo512() {
        assertEquals(512, Dns.maxUdpSize(withOpt(100)))
    }

    @Test
    fun maxUdpSizeOfBrokenQueryIs512() {
        assertEquals(512, Dns.maxUdpSize(byteArrayOf(1, 2, 3)))
    }

    private fun key(q: ByteArray) = Dns.cacheKey(q, Dns.parseQuestion(q)!!)

    @Test
    fun cacheKeySeparatesEdnsDoAndSize() {
        val keys = listOf(plain(), withOpt(1232), withOpt(4096), withOpt(1232, dnssecOk = true)).map(::key)
        assertEquals(keys.size, keys.toSet().size)
        // ID が違うだけの問い合わせは同じキー (キャッシュに当たる)
        assertEquals(key(withOpt(1232, id = 1)), key(withOpt(1232, id = 2)))
    }

    @Test
    fun formerrKeepsIdAndSetsRcode() {
        val q = plain(0xBEEF)
        val r = Dns.formerr(q)!!
        assertEquals(12, r.size)
        assertEquals(0xBE, r[0].toInt() and 0xFF)
        assertEquals(0xEF, r[1].toInt() and 0xFF)
        assertEquals(0x80, r[2].toInt() and 0x80) // QR=1
        assertEquals(Dns.RCODE_FORMERR, Dns.rcode(r))
        assertArrayEquals(ByteArray(8), r.copyOfRange(4, 12)) // 件数はすべて 0
        assertEquals(0x01, r[2].toInt() and 0x01) // RD は問い合わせのまま
    }

    @Test
    fun formerrIsNotSentForResponsesOrTooShort() {
        assertNull(Dns.formerr(byteArrayOf(1, 2, 3)))
        val response = plain().also { it[2] = (it[2].toInt() or 0x80).toByte() }
        assertNull(Dns.formerr(response)) // 応答に応答しない
    }

    @Test
    fun differentSizesGiveDifferentKeys() {
        assertNotEquals(key(withOpt(512)), key(withOpt(4096)))
    }
}
