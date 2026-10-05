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
    private const val PROTO_TCP = 6

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

    /**
     * VPN に届いた TCP の接続要求 (SYN) を、すぐ断る応答 (RST) を作る。SYN でなければ null。
     * 公開 DNS 宛ての DNS over HTTPS / TLS を断って、アプリに端末の DNS へ戻ってもらうために使う
     * (何も返さないと、アプリは接続のタイムアウトまで待つ)。
     */
    fun tcpReset(buf: ByteArray, len: Int): ByteArray? {
        if (len < 1) return null
        val version = (buf[0].toInt() shr 4) and 0xF
        val ipHeader: Int
        val addrLen: Int
        when (version) {
            4 -> {
                if (len < 20) return null
                ipHeader = (buf[0].toInt() and 0xF) * 4
                if (ipHeader < 20 || buf[9].toInt() != PROTO_TCP) return null
                addrLen = 4
            }
            6 -> {
                if (len < 40 || buf[6].toInt() != PROTO_TCP) return null
                ipHeader = 40
                addrLen = 16
            }
            else -> return null
        }
        if (len < ipHeader + 20) return null
        val flags = buf[ipHeader + 13].toInt()
        // SYN だけ (ACK や RST が付いていない) に答える
        if (flags and 0x02 == 0 || flags and 0x14 != 0) return null
        val seq = u32(buf, ipHeader + 4)

        val p = ByteArray(ipHeader.coerceAtMost(if (version == 4) 20 else 40) + 20)
        val tcp = p.size - 20
        if (version == 4) {
            p[0] = 0x45
            put16(p, 2, p.size)
            p[6] = 0x40
            p[8] = 64
            p[9] = PROTO_TCP.toByte()
            System.arraycopy(buf, 16, p, 12, 4) // 送り元 = 問い合わせの宛先
            System.arraycopy(buf, 12, p, 16, 4)
            put16(p, 10, checksum(p, 0, 20, 0))
        } else {
            p[0] = 0x60
            put16(p, 4, 20)
            p[6] = PROTO_TCP.toByte()
            p[7] = 64
            System.arraycopy(buf, 24, p, 8, 16)
            System.arraycopy(buf, 8, p, 24, 16)
        }
        put16(p, tcp, u16(buf, ipHeader + 2)) // 送り元ポート = 相手の宛先ポート
        put16(p, tcp + 2, u16(buf, ipHeader))
        // SEQ は 0、ACK は相手の SEQ + 1 (SYN の分)
        put32(p, tcp + 8, (seq + 1).toInt())
        p[tcp + 12] = 0x50 // ヘッダー長 20 バイト
        p[tcp + 13] = 0x14 // RST + ACK
        var pseudo = 0L
        val src = tcp - 2 * addrLen
        for (i in src until tcp step 2) pseudo += u16(p, i)
        pseudo += 20 + PROTO_TCP
        put16(p, tcp + 16, checksum(p, tcp, 20, pseudo))
        return p
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
