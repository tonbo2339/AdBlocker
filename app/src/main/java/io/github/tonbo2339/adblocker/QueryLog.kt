package io.github.tonbo2339.adblocker

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 直近の問い合わせの履歴。画面に出すのはメモリ上の直近 CAPACITY 件。
 * 「ログを残す期間」を選んでいれば、同じ内容を QueryLogFiles がファイルにも書く (既定はメモリ上だけ)。
 * 同じアプリから同じドメインへの続けての問い合わせ (A と AAAA など) は 1 行にまとめる。
 */
object QueryLog {
    const val CAPACITY = 500
    private const val MERGE_WINDOW_MS = 2000

    /** via は答えの中身で止めたときの理由 (CNAME の行き先・IP のルール)、app は問い合わせたアプリのパッケージ名。 */
    class Entry(
        val domain: String,
        val verdict: Verdict,
        val time: Long,
        val count: Int,
        val via: String? = null,
        val app: String? = null,
    )

    @Volatile
    var enabled = true
        set(value) {
            field = value
            // よくブロックしたドメイン・アプリもドメイン名を残すので、ログと一緒に止める
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

    fun add(domain: String, verdict: Verdict, via: String? = null, app: String? = null, time: Long = System.currentTimeMillis()) {
        if (!enabled) return
        QueryLogFiles.append(Entry(domain, verdict, time, 1, via, app))
        synchronized(this) {
            val last = entries.lastOrNull()
            if (last != null && sameRow(last, domain, verdict, app, time)) {
                entries[entries.lastIndex] = Entry(domain, verdict, time, last.count + 1, via, app)
            } else {
                if (entries.size == CAPACITY) entries.removeFirst()
                entries.addLast(Entry(domain, verdict, time, 1, via, app))
            }
            version++
        }
    }

    /** ファイルに残したログを、今あるものより前に入れる (プロセスの開始時。古い順に渡す)。 */
    @Synchronized
    fun prepend(older: List<Entry>) {
        // 読み込んでいる間にログをオフにしていたら戻さない
        if (older.isEmpty() || !enabled) return
        val room = CAPACITY - entries.size
        if (room <= 0) return
        val oldestShown = entries.firstOrNull()?.time ?: Long.MAX_VALUE
        // 開始後にもう記録したものと重ならないよう、それより前のものだけ。ファイルは 1 問い合わせ 1 行なので、add() と同じくまとめる
        val merged = ArrayList<Entry>()
        for (e in older) {
            if (e.time >= oldestShown) continue
            val last = merged.lastOrNull()
            if (last != null && sameRow(last, e.domain, e.verdict, e.app, e.time)) {
                merged[merged.lastIndex] = Entry(e.domain, e.verdict, e.time, last.count + e.count, e.via, e.app)
            } else {
                merged += e
            }
        }
        merged.takeLast(room).asReversed().forEach { entries.addFirst(it) }
        version++
    }

    /** 直前の行にまとめるか (同じアプリ・同じドメイン・同じ結果が MERGE_WINDOW_MS 以内に続いた)。 */
    private fun sameRow(last: Entry, domain: String, verdict: Verdict, app: String?, time: Long): Boolean =
        last.domain == domain && last.verdict == verdict && last.app == app && time - last.time < MERGE_WINDOW_MS

    /** 新しい順。 */
    @Synchronized
    fun snapshot(): List<Entry> = entries.reversed()

    @Synchronized
    fun clear() {
        entries.clear()
        version++
    }
}

/** 今回 (VPN を開始してから) ブロックしたドメイン・アプリごとの回数。メモリ上だけ。 */
object SessionStats {
    /** 種類の多すぎるドメインでメモリを使いすぎないための上限。 */
    private const val MAX_KEYS = 5000

    private val domains = ConcurrentHashMap<String, AtomicInteger>()
    private val apps = ConcurrentHashMap<String, AtomicInteger>()

    fun recordBlocked(domain: String) = count(domains, domain)

    /** app はパッケージ名。 */
    fun recordBlockedApp(app: String) = count(apps, app)

    private fun count(map: ConcurrentHashMap<String, AtomicInteger>, key: String) {
        val counter = map[key] ?: run {
            if (map.size >= MAX_KEYS) return
            map.computeIfAbsent(key) { AtomicInteger() }
        }
        counter.incrementAndGet()
    }

    fun top(n: Int): List<Pair<String, Int>> = top(domains, n)

    fun topApps(n: Int): List<Pair<String, Int>> = top(apps, n)

    private fun top(map: ConcurrentHashMap<String, AtomicInteger>, n: Int) =
        map.entries.map { it.key to it.value.get() }.sortedByDescending { it.second }.take(n)

    fun reset() {
        domains.clear()
        apps.clear()
    }
}
