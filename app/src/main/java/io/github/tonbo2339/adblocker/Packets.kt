package io.github.tonbo2339.adblocker

/** tun から読んだ UDP/53 宛ての DNS クエリ (IPv4 / IPv6)。 */
class DnsQuery(
    val ipVersion: Int,
    val srcAddr: ByteArray,
    val dstAddr: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val dns: ByteArray,
)

/** IP/UDP パケットの解析と組み立て。VPN には DNS サーバー宛てしかルーティングしないので UDP だけ扱う。 */
object Packets {
    private const val PROTO_UDP = 17

    fun parse(buf: ByteArray, len: Int): DnsQuery? {
        if (len < 1) return null
        val version = (buf[0].toInt() shr 4) and 0xF
        val udpStart: Int
        val src: ByteArray
        val dst: ByteArray
        when (version) {
            4 -> {
                if (len < 20) return null
                val ihl = (buf[0].toInt() and 0xF) * 4
                if (ihl < 20 || buf[9].toInt() != PROTO_UDP) return null
                // フラグメントは扱わない (DNS クエリが分割されることはまず無い)
                val moreFragments = buf[6].toInt() and 0x20
                val fragOffset = u16(buf, 6) and 0x1FFF
                if (moreFragments != 0 || fragOffset != 0) return null
                src = buf.copyOfRange(12, 16)
                dst = buf.copyOfRange(16, 20)
                udpStart = ihl
            }
            6 -> {
                // 拡張ヘッダー付きは扱わない
                if (len < 40 || buf[6].toInt() != PROTO_UDP) return null
                src = buf.copyOfRange(8, 24)
                dst = buf.copyOfRange(24, 40)
                udpStart = 40
            }
            else -> return null
        }
        if (len < udpStart + 8) return null
        val srcPort = u16(buf, udpStart)
        val dstPort = u16(buf, udpStart + 2)
        val udpLen = u16(buf, udpStart + 4)
        if (dstPort != 53 || udpLen < 8 || udpStart + udpLen > len) return null
        val dns = buf.copyOfRange(udpStart + 8, udpStart + udpLen)
        return DnsQuery(version, src, dst, srcPort, dstPort, dns)
    }

    /** クエリの送信元に返す応答パケット (アドレスとポートを入れ替える)。 */
    fun buildResponse(q: DnsQuery, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        return if (q.ipVersion == 4) {
            val p = ByteArray(20 + udpLen)
            p[0] = 0x45
            put16(p, 2, p.size)
            p[6] = 0x40 // Don't Fragment
            p[8] = 64 // TTL
            p[9] = PROTO_UDP.toByte()
            System.arraycopy(q.dstAddr, 0, p, 12, 4)
            System.arraycopy(q.srcAddr, 0, p, 16, 4)
            put16(p, 10, checksum(p, 0, 20, 0))
            writeUdp(p, 20, q, payload)
            // IPv4 の UDP チェックサムは 0 (省略) でよい
            p
        } else {
            val p = ByteArray(40 + udpLen)
            p[0] = 0x60
            put16(p, 4, udpLen)
            p[6] = PROTO_UDP.toByte()
            p[7] = 64 // hop limit
            System.arraycopy(q.dstAddr, 0, p, 8, 16)
            System.arraycopy(q.srcAddr, 0, p, 24, 16)
            writeUdp(p, 40, q, payload)
            // IPv6 では UDP チェックサムが必須 (疑似ヘッダー込み)
            var pseudo = 0L
            for (i in 8 until 40 step 2) pseudo += u16(p, i)
            pseudo += udpLen + PROTO_UDP
            val sum = checksum(p, 40, udpLen, pseudo)
            put16(p, 46, if (sum == 0) 0xFFFF else sum)
            p
        }
    }

    private fun writeUdp(p: ByteArray, off: Int, q: DnsQuery, payload: ByteArray) {
        put16(p, off, q.dstPort)
        put16(p, off + 2, q.srcPort)
        put16(p, off + 4, 8 + payload.size)
        System.arraycopy(payload, 0, p, off + 8, payload.size)
    }

    private fun checksum(b: ByteArray, off: Int, len: Int, initial: Long): Int {
        var sum = initial
        var i = off
        val end = off + len
        while (i + 1 < end) {
            sum += u16(b, i)
            i += 2
        }
        if (i < end) sum += (b[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}

internal fun u16(b: ByteArray, off: Int): Int =
    ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

internal fun put16(b: ByteArray, off: Int, v: Int) {
    b[off] = (v shr 8).toByte()
    b[off + 1] = v.toByte()
}
