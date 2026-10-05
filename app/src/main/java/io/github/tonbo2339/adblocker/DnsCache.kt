package io.github.tonbo2339.adblocker

/**
 * 転送先の応答を TTL の間だけ覚えておく小さなキャッシュ。同じ問い合わせを転送せずにすぐ答える。
 *
 * ブロックの判定は覚えない (毎回ルールで判定してから引く) ので、ルールを変えてもすぐ効く。
 * 回線が変わったら clear() する (社内やルーターの中だけで使える名前の答えを、別の回線で返さないため)。
 * 時間は端末の時計ではなく単調に進む時計 (nanoTime) で測る (時計を合わせ直しても、答えを長く覚えすぎたりすぐ忘れたりしない)。
 */
class DnsCache(private val maxEntries: Int = 1000, private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {

    companion object {
        /** TTL がこれより長くても、この秒数で忘れる (行き先が変わったときに古い答えを返し続けないため)。 */
        const val MAX_TTL_SECONDS = 3600L
    }

    private class Entry(val response: ByteArray, val storedAt: Long, val expiresAt: Long)

    // アクセス順の LinkedHashMap で、古いものから捨てる
    private val entries = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > maxEntries
    }

    /** 覚えている応答を、この問い合わせ向けに直して返す (無い・期限切れなら null)。 */
    fun get(query: ByteArray, q: Question): ByteArray? {
        val key = Dns.cacheKey(query, q)
        val t = now()
        val entry = synchronized(entries) {
            val e = entries[key] ?: return null
            if (t >= e.expiresAt) {
                entries.remove(key)
                return null
            }
            e
        }
        return Dns.reuse(entry.response, query, q, (t - entry.storedAt) / 1000)
    }

    fun put(query: ByteArray, q: Question, response: ByteArray) {
        val ttl = Dns.cacheTtl(response).coerceAtMost(MAX_TTL_SECONDS)
        if (ttl <= 0) return
        val t = now()
        synchronized(entries) { entries[Dns.cacheKey(query, q)] = Entry(response, t, t + ttl * 1000) }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    val size: Int get() = synchronized(entries) { entries.size }
}
