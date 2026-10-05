package io.github.tonbo2339.adblocker

/** DNS メッセージの最初の質問。end は質問セクションの終わりの位置。 */
class Question(val name: String, val end: Int)

object Dns {
    /** クエリの最初の質問を読む。クエリでない・壊れている場合は null。 */
    fun parseQuestion(msg: ByteArray): Question? {
        if (msg.size < 12) return null
        if (msg[2].toInt() and 0x80 != 0) return null // 応答 (QR=1) は無視
        if (u16(msg, 4) < 1) return null
        val sb = StringBuilder()
        var i = 12
        while (true) {
            if (i >= msg.size) return null
            val len = msg[i].toInt() and 0xFF
            if (len == 0) {
                i++
                break
            }
            // クエリの名前に圧縮ポインタは来ない
            if (len and 0xC0 != 0 || i + 1 + len > msg.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (k in 1..len) sb.append((msg[i + k].toInt() and 0xFF).toChar())
            i += 1 + len
        }
        if (i + 4 > msg.size) return null // QTYPE + QCLASS
        return Question(sb.toString().lowercase(), i + 4)
    }

    /** 最初の質問をそのまま返す NXDOMAIN 応答。 */
    fun nxdomain(query: ByteArray, q: Question): ByteArray {
        val r = query.copyOf(q.end)
        r[2] = ((query[2].toInt() and 0x79) or 0x80).toByte() // QR=1、Opcode と RD は維持、AA/TC は 0
        r[3] = 0x83.toByte() // RA=1, RCODE=3 (NXDOMAIN)
        put16(r, 4, 1) // QDCOUNT
        put16(r, 6, 0) // ANCOUNT
        put16(r, 8, 0) // NSCOUNT
        put16(r, 10, 0) // ARCOUNT
        return r
    }
}
