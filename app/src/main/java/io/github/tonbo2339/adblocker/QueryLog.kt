package io.github.tonbo2339.adblocker

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 直近の問い合わせの履歴。**メモリ上だけ** に持ち、ディスクには書かない (プロセスが終われば消える)。
 * 同じドメインの続けての問い合わせ (A と AAAA など) は 1 行にまとめる。
 */
object QueryLog {
    const val CAPACITY = 500
    private const val MERGE_WINDOW_MS = 2000

    class Entry(val domain: String, val verdict: Verdict, val time: Long, val count: Int)

    @Volatile
    var enabled = true
        set(value) {
            field = value
            // よくブロックしたドメインもドメイン名を残すので、ログと一緒に止める
            if (!value) {
                clear()
                SessionStats.reset()
            }
        }

    private val entries = ArrayDeque<Entry>(CAPACITY)

    /** 中身が変わるたびに増える (画面の再描画の判断に使う)。 */
    @Volatile
    var version = 0L
        private set

    fun add(domain: String, verdict: Verdict, time: Long = System.currentTimeMillis()) {
        if (!enabled) return
        synchronized(this) {
            val last = entries.lastOrNull()
            if (last != null && last.domain == domain && last.verdict == verdict && time - last.time < MERGE_WINDOW_MS) {
                entries[entries.lastIndex] = Entry(domain, verdict, time, last.count + 1)
            } else {
                if (entries.size == CAPACITY) entries.removeFirst()
                entries.addLast(Entry(domain, verdict, time, 1))
            }
            version++
        }
    }

    /** 新しい順。 */
    @Synchronized
    fun snapshot(): List<Entry> = entries.reversed()

    @Synchronized
    fun clear() {
        entries.clear()
        version++
    }
}

/** 今回 (VPN を開始してから) ブロックしたドメインごとの回数。メモリ上だけ。 */
object SessionStats {
    /** 種類の多すぎるドメインでメモリを使いすぎないための上限。 */
    private const val MAX_DOMAINS = 5000

    private val blocked = ConcurrentHashMap<String, AtomicInteger>()

    fun recordBlocked(domain: String) {
        val counter = blocked[domain] ?: run {
            if (blocked.size >= MAX_DOMAINS) return
            blocked.computeIfAbsent(domain) { AtomicInteger() }
        }
        counter.incrementAndGet()
    }

    fun top(n: Int): List<Pair<String, Int>> =
        blocked.entries.map { it.key to it.value.get() }.sortedByDescending { it.second }.take(n)

    fun reset() = blocked.clear()
}
