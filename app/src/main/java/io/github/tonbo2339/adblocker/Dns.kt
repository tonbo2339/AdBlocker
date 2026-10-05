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

    private const val TYPE_CNAME = 5

    /** 応答の回答セクションにある CNAME の行き先 (連鎖の順)。壊れた応答なら読めたところまで。 */
    fun cnameTargets(msg: ByteArray): List<String> {
        if (msg.size < 12) return emptyList()
        val targets = mutableListOf<String>()
        var i = 12
        repeat(u16(msg, 4)) {
            i = skipName(msg, i) ?: return targets
            i += 4 // QTYPE + QCLASS
        }
        repeat(u16(msg, 6)) {
            i = skipName(msg, i) ?: return targets
            if (i + 10 > msg.size) return targets
            val type = u16(msg, i)
            val rdLength = u16(msg, i + 8)
            i += 10
            if (i + rdLength > msg.size) return targets
            if (type == TYPE_CNAME) readName(msg, i)?.let { targets += it }
            i += rdLength
        }
        return targets
    }

    /** 名前の次の位置。圧縮ポインタなら 2 バイトで終わる。 */
    private fun skipName(msg: ByteArray, start: Int): Int? {
        var i = start
        while (i < msg.size) {
            val len = msg[i].toInt() and 0xFF
            when {
                len == 0 -> return i + 1
                len and 0xC0 == 0xC0 -> return if (i + 2 <= msg.size) i + 2 else null
                len and 0xC0 != 0 -> return null
                else -> i += 1 + len
            }
        }
        return null
    }

    /** 圧縮ポインタをたどって名前を読む。ループする壊れた応答に備えて、たどる回数を制限する。 */
    private fun readName(msg: ByteArray, start: Int): String? {
        val sb = StringBuilder()
        var i = start
        var jumps = 0
        while (true) {
            if (i >= msg.size) return null
            val len = msg[i].toInt() and 0xFF
            when {
                len == 0 -> return sb.toString().lowercase()
                len and 0xC0 == 0xC0 -> {
                    if (i + 2 > msg.size || ++jumps > 16) return null
                    i = ((len and 0x3F) shl 8) or (msg[i + 1].toInt() and 0xFF)
                }
                len and 0xC0 != 0 -> return null
                else -> {
                    if (i + 1 + len > msg.size || sb.length + len > 253) return null
                    if (sb.isNotEmpty()) sb.append('.')
                    for (k in 1..len) sb.append((msg[i + k].toInt() and 0xFF).toChar())
                    i += 1 + len
                }
            }
        }
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
