package io.github.tonbo2339.adblocker.dns

/** DNS メッセージの最初の質問。type は QTYPE (A = 1, AAAA = 28 など)、end は質問セクションの終わりの位置。 */
class Question(val name: String, val type: Int, val end: Int)

/** 応答のリソースレコード 1 つの位置。section は 1 = 回答、2 = 権威、3 = 追加。 */
class DnsRecord(val type: Int, val section: Int, val ttlAt: Int, val rdAt: Int, val rdLength: Int)

object Dns {
    const val TYPE_A = 1
    const val TYPE_CNAME = 5
    const val TYPE_SOA = 6
    const val TYPE_AAAA = 28
    private const val TYPE_OPT = 41

    const val RCODE_NOERROR = 0
    const val RCODE_FORMERR = 1
    const val RCODE_SERVFAIL = 2
    const val RCODE_NXDOMAIN = 3

    /** EDNS を使わない問い合わせが受け取れる UDP の大きさ (RFC 1035)。 */
    private const val CLASSIC_UDP_SIZE = 512

    /** ブロックした応答に付ける SOA の有効期間 (秒)。短くして、許可に変えたらすぐ効くようにする。 */
    private const val BLOCKED_TTL = 5

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
        return Question(sb.toString().lowercase(), u16(msg, i), i + 4)
    }

    fun rcode(msg: ByteArray): Int = msg[3].toInt() and 0x0F

    /** 先頭に 2 バイトの長さを付けた形 (RFC 1035 4.2.2。DNS over TCP / TLS で使う)。 */
    fun tcpFrame(dns: ByteArray): ByteArray {
        val frame = ByteArray(2 + dns.size)
        put16(frame, 0, dns.size)
        System.arraycopy(dns, 0, frame, 2, dns.size)
        return frame
    }

    /** 応答が途中で切り詰められている (TC=1)。 */
    fun isTruncated(msg: ByteArray): Boolean = msg[2].toInt() and 0x02 != 0

    /** 質問セクションより後のリソースレコード。壊れていれば null。 */
    fun records(msg: ByteArray): List<DnsRecord>? {
        if (msg.size < 12) return null
        var i = 12
        repeat(u16(msg, 4)) {
            i = skipName(msg, i) ?: return null
            i += 4 // QTYPE + QCLASS
        }
        val result = ArrayList<DnsRecord>()
        // 回答 (1)・権威 (2)・追加 (3) の順。件数の欄は 6・8・10 バイト目
        for (section in 1..3) {
            repeat(u16(msg, 4 + 2 * section)) {
                i = skipName(msg, i) ?: return null
                if (i + 10 > msg.size) return null
                val rdLength = u16(msg, i + 8)
                if (i + 10 + rdLength > msg.size) return null
                result += DnsRecord(u16(msg, i), section, i + 4, i + 10, rdLength)
                i += 10 + rdLength
            }
        }
        return result
    }

    /** 応答の回答セクションにある CNAME の行き先 (連鎖の順)。壊れた応答なら空。records は解析済みのものがあれば渡す。 */
    fun cnameTargets(msg: ByteArray, records: List<DnsRecord> = records(msg).orEmpty()): List<String> =
        records
            .filter { it.section == 1 && it.type == TYPE_CNAME }
            .mapNotNull { readName(msg, it.rdAt) }

    /** 応答の回答セクションにある IP アドレス (A は 4 バイト、AAAA は 16 バイト)。records は解析済みのものがあれば渡す。 */
    fun answerAddresses(msg: ByteArray, records: List<DnsRecord> = records(msg).orEmpty()): List<ByteArray> =
        records
            .filter { it.section == 1 && (it.type == TYPE_A && it.rdLength == 4 || it.type == TYPE_AAAA && it.rdLength == 16) }
            .map { msg.copyOfRange(it.rdAt, it.rdAt + it.rdLength) }

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

    /**
     * ブロックしたときの応答。NXDOMAIN (名前が無い) ではなく、「名前はあるが、その種類の答えは無い」(NOERROR + 回答なし) に
     * 短い有効期間の SOA を付ける (AdAway と同じ形)。NXDOMAIN だと別の経路で再試行するアプリがあり、
     * SOA が無いと OS がこの結果を覚えておけず、同じ問い合わせが続く。
     */
    fun blocked(query: ByteArray, q: Question): ByteArray {
        val header = reply(query, q, RCODE_NOERROR, authority = 1)
        // 持ち主・mname・rname はすべて質問の名前 (12 バイト目) への圧縮ポインタ
        val soa = ByteArray(2 + 10 + 2 + 2 + 20)
        put16(soa, 0, 0xC00C)
        put16(soa, 2, TYPE_SOA)
        put16(soa, 4, 1) // IN
        put32(soa, 6, BLOCKED_TTL)
        put16(soa, 10, 24) // RDLENGTH
        put16(soa, 12, 0xC00C) // MNAME
        put16(soa, 14, 0xC00C) // RNAME
        // SERIAL / REFRESH / RETRY / EXPIRE は 0、MINIMUM (否定応答を覚えておく秒数)
        put32(soa, 32, BLOCKED_TTL)
        return header + soa
    }

    /** 転送先が 1 つも答えなかったときの応答。アプリがタイムアウトを待たずに、すぐ次の手 (再試行など) に移れる。 */
    fun servfail(query: ByteArray, q: Question): ByteArray = reply(query, q, RCODE_SERVFAIL, authority = 0)

    /**
     * 質問を読めなかった問い合わせへの応答 (FORMERR、ヘッダーだけ)。黙って捨てるとアプリがタイムアウトまで待つ。
     * 応答 (QR=1) や 12 バイトに満たないものには返さない (null)。
     */
    fun formerr(query: ByteArray): ByteArray? {
        if (query.size < 12 || query[2].toInt() and 0x80 != 0) return null
        val r = query.copyOf(12)
        r[2] = ((query[2].toInt() and 0x79) or 0x80).toByte()
        r[3] = (0x80 or RCODE_FORMERR).toByte()
        for (at in intArrayOf(4, 6, 8, 10)) put16(r, at, 0)
        return r
    }

    /**
     * 問い合わせの送り主が受け取れる UDP の大きさ (バイト)。EDNS (OPT) の UDP payload size で、無ければ 512。
     * 暗号化 DNS (TCP) の答えは、これより大きいとそのまま返せない。
     */
    fun maxUdpSize(query: ByteArray): Int = maxUdpSize(query, optOf(query))

    /** 問い合わせの EDNS (OPT) レコード (無ければ null)。 */
    private fun optOf(query: ByteArray): DnsRecord? = records(query).orEmpty().firstOrNull { it.type == TYPE_OPT }

    private fun maxUdpSize(query: ByteArray, opt: DnsRecord?): Int {
        if (opt == null) return CLASSIC_UDP_SIZE
        // OPT では CLASS 欄 (TTL 欄の 2 バイト前) が UDP payload size。512 未満は 512 として扱う (RFC 6891)
        return maxOf(u16(query, opt.ttlAt - 2), CLASSIC_UDP_SIZE)
    }

    /** 最初の質問だけを返す応答の骨組み。 */
    private fun reply(query: ByteArray, q: Question, rcode: Int, authority: Int): ByteArray {
        val r = query.copyOf(q.end)
        r[2] = ((query[2].toInt() and 0x79) or 0x80).toByte() // QR=1、Opcode と RD は維持、AA/TC は 0
        r[3] = (0x80 or rcode).toByte() // RA=1
        put16(r, 4, 1) // QDCOUNT
        put16(r, 6, 0) // ANCOUNT
        put16(r, 8, authority) // NSCOUNT
        put16(r, 10, 0) // ARCOUNT
        return r
    }

    /**
     * 応答を覚えておける秒数 (覚えないなら 0)。
     * 答えがあれば回答の TTL の最小、名前が無い・答えが無い (否定応答) なら SOA の TTL と MINIMUM の小さい方 (RFC 2308)。
     */
    fun cacheTtl(msg: ByteArray): Long {
        if (msg.size < 12 || isTruncated(msg)) return 0
        val rcode = rcode(msg)
        if (rcode != RCODE_NOERROR && rcode != RCODE_NXDOMAIN) return 0
        val records = records(msg) ?: return 0
        val answers = records.filter { it.section == 1 }
        if (rcode == RCODE_NOERROR && answers.isNotEmpty()) return answers.minOf { u32(msg, it.ttlAt) }
        val soa = records.firstOrNull { it.section == 2 && it.type == TYPE_SOA && it.rdLength >= 22 } ?: return 0
        return minOf(u32(msg, soa.ttlAt), u32(msg, soa.rdAt + soa.rdLength - 4))
    }

    /**
     * 覚えておいた応答を、今の問い合わせへの答えに直す。ID と質問 (大文字小文字も) を問い合わせに合わせ、
     * 経過した秒数だけ TTL を減らす。
     */
    fun reuse(cached: ByteArray, query: ByteArray, q: Question, ageSeconds: Long): ByteArray {
        val r = cached.copyOf()
        r[0] = query[0]
        r[1] = query[1]
        // 質問の長さは同じ (同じ名前・種類で覚えている)。大文字小文字を混ぜて確かめるアプリのため、そのまま写す
        if (q.end <= r.size) System.arraycopy(query, 12, r, 12, q.end - 12)
        if (ageSeconds > 0) {
            for (rec in records(r).orEmpty()) {
                if (rec.type == TYPE_OPT) continue // OPT の TTL 欄は TTL ではない
                put32(r, rec.ttlAt, (u32(r, rec.ttlAt) - ageSeconds).coerceAtLeast(0).toInt())
            }
        }
        return r
    }

    /**
     * キャッシュのキー。名前・種類・クラスに加えて、応答の中身が変わるもの (CD、EDNS の有無と DO、受け取れる UDP の大きさ) を含める。
     * 大きさを分けるのは、大きな答えを、小さくしか受け取れない相手にキャッシュから返さないため。
     */
    fun cacheKey(query: ByteArray, q: Question): String {
        val qclass = u16(query, q.end - 2)
        val cd = query[3].toInt() and 0x10 != 0
        // EDNS (OPT) を使わない問い合わせに、OPT 付きの答えを返さないよう、EDNS の有無も分ける
        val opt = optOf(query) // 1 回だけ解析する (大きさも同じ OPT から読む)
        val edns = when {
            opt == null -> 0
            u32(query, opt.ttlAt) and 0x8000L != 0L -> 2 // DO (DNSSEC の情報も欲しい)
            else -> 1
        }
        return "${q.name}/${q.type}/$qclass/${if (cd) 1 else 0}$edns/${maxUdpSize(query, opt)}"
    }
}

internal fun u32(b: ByteArray, off: Int): Long =
    (u16(b, off).toLong() shl 16) or u16(b, off + 2).toLong()

internal fun put32(b: ByteArray, off: Int, v: Int) {
    put16(b, off, v ushr 16)
    put16(b, off + 2, v)
}
