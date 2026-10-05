package io.github.tonbo2339.adblocker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketsTest {

    /** example.com の A レコード問い合わせ (ID=0x1234, RD=1)。 */
    private fun dnsQuery(name: String = "ads.example.com"): ByteArray {
        val out = mutableListOf<Byte>()
        out += listOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0).map { it.toByte() }
        for (label in name.split('.')) {
            out += label.length.toByte()
            out += label.toByteArray().toList()
        }
        out += 0
        out += listOf(0x00, 0x01, 0x00, 0x01).map { it.toByte() } // QTYPE=A, QCLASS=IN
        return out.toByteArray()
    }

    private fun ipv4Udp(dns: ByteArray, dstPort: Int = 53): ByteArray {
        val p = ByteArray(28 + dns.size)
        p[0] = 0x45
        put16(p, 2, p.size)
        p[8] = 64
        p[9] = 17
        byteArrayOf(10, 111, 222.toByte(), 1).copyInto(p, 12)
        byteArrayOf(10, 111, 222.toByte(), 2).copyInto(p, 16)
        put16(p, 20, 40000)
        put16(p, 22, dstPort)
        put16(p, 24, 8 + dns.size)
        dns.copyInto(p, 28)
        return p
    }

    private fun ipv6Udp(dns: ByteArray): ByteArray {
        val p = ByteArray(48 + dns.size)
        p[0] = 0x60
        put16(p, 4, 8 + dns.size)
        p[6] = 17
        p[7] = 64
        p[23] = 1 // src fd00::1 相当
        p[8] = 0xfd.toByte()
        p[24] = 0xfd.toByte()
        p[39] = 2
        put16(p, 40, 50000)
        put16(p, 42, 53)
        put16(p, 44, 8 + dns.size)
        dns.copyInto(p, 48)
        return p
    }

    /** 1 の補数の和。正しいチェックサム込みなら 0xFFFF になる。 */
    private fun onesSum(b: ByteArray, off: Int, len: Int, initial: Long = 0): Int {
        var sum = initial
        var i = off
        while (i + 1 < off + len) {
            sum += u16(b, i); i += 2
        }
        if (i < off + len) sum += (b[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.toInt()
    }

    @Test
    fun parsesIpv4DnsQuery() {
        val dns = dnsQuery()
        val p = ipv4Udp(dns)
        val q = Packets.parse(p, p.size)
        assertNotNull(q)
        q!!
        assertEquals(4, q.ipVersion)
        assertEquals(40000, q.srcPort)
        assertArrayEquals(dns, q.dns)
    }

    @Test
    fun ignoresNonDnsPort() {
        val p = ipv4Udp(dnsQuery(), dstPort = 853)
        assertNull(Packets.parse(p, p.size))
    }

    @Test
    fun ignoresTruncatedPacket() {
        val p = ipv4Udp(dnsQuery())
        assertNull(Packets.parse(p, p.size - 5))
    }

    @Test
    fun ipv4ResponseSwapsAddressesAndHasValidHeaderChecksum() {
        val p = ipv4Udp(dnsQuery())
        val q = Packets.parse(p, p.size)!!
        val payload = byteArrayOf(1, 2, 3)
        val r = Packets.buildResponse(q, payload)
        assertEquals(20 + 8 + 3, r.size)
        assertArrayEquals(q.dstAddr, r.copyOfRange(12, 16))
        assertArrayEquals(q.srcAddr, r.copyOfRange(16, 20))
        assertEquals(53, u16(r, 20))
        assertEquals(40000, u16(r, 22))
        assertEquals(0xFFFF, onesSum(r, 0, 20))
        assertArrayEquals(payload, r.copyOfRange(28, 31))
        // 応答をもう一度 parse すると宛先ポートが 53 でないので null (自分の応答を再処理しない)
        assertNull(Packets.parse(r, r.size))
    }

    @Test
    fun ipv6ResponseHasValidUdpChecksum() {
        val p = ipv6Udp(dnsQuery())
        val q = Packets.parse(p, p.size)!!
        assertEquals(6, q.ipVersion)
        val payload = byteArrayOf(9, 8, 7, 6, 5)
        val r = Packets.buildResponse(q, payload)
        val udpLen = 8 + payload.size
        var pseudo = 0L
        for (i in 8 until 40 step 2) pseudo += u16(r, i)
        pseudo += udpLen + 17
        assertEquals(0xFFFF, onesSum(r, 40, udpLen, pseudo))
        assertArrayEquals(q.dstAddr, r.copyOfRange(8, 24))
    }

    @Test
    fun parsesQuestionName() {
        val q = Dns.parseQuestion(dnsQuery("Ads.Example.COM"))!!
        assertEquals("ads.example.com", q.name)
    }

    @Test
    fun rejectsResponsesAndGarbage() {
        val resp = dnsQuery().also { it[2] = (it[2].toInt() or 0x80).toByte() }
        assertNull(Dns.parseQuestion(resp))
        assertNull(Dns.parseQuestion(ByteArray(5)))
        // ラベル長が範囲外
        val bad = dnsQuery().also { it[12] = 60 }
        assertNull(Dns.parseQuestion(bad))
    }

    @Test
    fun blockedIsNodataWithShortSoa() {
        val query = dnsQuery()
        val q = Dns.parseQuestion(query)!!
        assertEquals(Dns.TYPE_A, q.type)
        val r = Dns.blocked(query, q)
        assertEquals(0x1234, u16(r, 0))
        assertTrue(r[2].toInt() and 0x80 != 0) // QR
        assertTrue(r[2].toInt() and 0x01 != 0) // RD を維持
        assertEquals(Dns.RCODE_NOERROR, Dns.rcode(r))
        assertEquals(1, u16(r, 4))
        assertEquals(0, u16(r, 6))
        assertEquals(1, u16(r, 8)) // 権威セクションに SOA が 1 つ
        val records = Dns.records(r)!!
        assertEquals(1, records.size)
        assertEquals(Dns.TYPE_SOA, records[0].type)
        // OS が 5 秒だけ覚えておける否定応答
        assertEquals(5L, Dns.cacheTtl(r))
    }

    @Test
    fun servfailHasNoRecords() {
        val query = dnsQuery()
        val r = Dns.servfail(query, Dns.parseQuestion(query)!!)
        assertEquals(Dns.RCODE_SERVFAIL, Dns.rcode(r))
        assertEquals(query.size, r.size)
        assertEquals(0L, Dns.cacheTtl(r)) // 失敗は覚えない
    }

    private fun tcpSyn(): ByteArray {
        val p = ByteArray(40)
        p[0] = 0x45
        put16(p, 2, p.size)
        p[8] = 64
        p[9] = 6
        byteArrayOf(10, 111, 222.toByte(), 1).copyInto(p, 12)
        byteArrayOf(8, 8, 8, 8).copyInto(p, 16)
        put16(p, 20, 40000)
        put16(p, 22, 443)
        put32(p, 24, 1000) // SEQ
        p[32] = 0x50
        p[33] = 0x02 // SYN
        return p
    }

    @Test
    fun tcpSynGetsReset() {
        val syn = tcpSyn()
        val r = Packets.tcpReset(syn, syn.size)!!
        assertEquals(40, r.size)
        assertEquals(0xFFFF, onesSum(r, 0, 20)) // IP のチェックサム
        assertArrayEquals(byteArrayOf(8, 8, 8, 8), r.copyOfRange(12, 16))
        assertEquals(443, u16(r, 20))
        assertEquals(40000, u16(r, 22))
        assertEquals(1001L, u32(r, 28)) // ACK = SEQ + 1
        assertEquals(0x14, r[33].toInt()) // RST + ACK
        // TCP のチェックサム (疑似ヘッダー込み)
        var pseudo = 0L
        for (i in 12 until 20 step 2) pseudo += u16(r, i)
        pseudo += 20 + 6
        assertEquals(0xFFFF, onesSum(r, 20, 20, pseudo))
    }

    @Test
    fun onlySynIsReset() {
        val ack = tcpSyn().also { it[33] = 0x10 }
        assertNull(Packets.tcpReset(ack, ack.size))
        val udp = ipv4Udp(dnsQuery())
        assertNull(Packets.tcpReset(udp, udp.size))
    }
}
