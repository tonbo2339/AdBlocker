package io.github.tonbo2339.adblocker

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 直近の問い合わせの履歴。画面に出すのはメモリ上の直近 CAPACITY 件。
 * 「ログを残す期間」を選んでいれば、同じ内容を QueryLogFiles がファイルにも書く (既定はメモリ上だけ)。
 * 同じアプリから同じドメインへの続けての問い合わせ (A と AAAA など) は 1 行にまとめる。
 */
object QueryLog {
    const val CAPACITY = 500
    private const val MERGE_WINDOW_MS = 2000

    /** ms の特別な値: 転送していない (ブロックなど)。 */
    const val NO_TIME = -1

    /** ms の特別な値: 覚えていた答えで返した。 */
    const val FROM_CACHE = -2

    /** ms の特別な値: 転送先のどこからも答えが無かった。 */
    const val NO_ANSWER = -3

    /**
     * via は答えの中身で止めたときの理由 (CNAME の行き先・IP のルール)、app は問い合わせたアプリのパッケージ名。
     * ms は転送先の答えが届くまでの時間 (ミリ秒)。転送していなければ NO_TIME・FROM_CACHE・NO_ANSWER のどれか。
     */
    class Entry(
        val domain: String,
        val verdict: Verdict,
        val time: Long,
        val count: Int,
        val via: String? = null,
        val app: String? = null,
        val ms: Int = NO_TIME,
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

    fun add(domain: String, verdict: Verdict, via: String? = null, app: String? = null, ms: Int = NO_TIME, time: Long = System.currentTimeMillis()) {
        if (!enabled) return
        QueryLogFiles.append(Entry(domain, verdict, time, 1, via, app, ms))
        synchronized(this) {
            val last = entries.lastOrNull()
            if (last != null && sameRow(last, domain, verdict, app, time)) {
                entries[entries.lastIndex] = Entry(domain, verdict, time, last.count + 1, via, app, ms)
            } else {
                if (entries.size == CAPACITY) entries.removeFirst()
                entries.addLast(Entry(domain, verdict, time, 1, via, app, ms))
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
                merged[merged.lastIndex] = Entry(e.domain, e.verdict, e.time, last.count + e.count, e.via, e.app, e.ms)
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

    private val forwardedCount = AtomicInteger()
    private val forwardedMs = AtomicLong()
    private val slowestMs = AtomicLong()
    private val cacheHits = AtomicInteger()
    private val noAnswers = AtomicInteger()

    /** 転送先の答えが届くまでの時間 (ミリ秒)。ログのオン / オフに関わらず数える (ドメインは含まない)。 */
    fun recordForwarded(ms: Long) {
        forwardedCount.incrementAndGet()
        forwardedMs.addAndGet(ms)
        slowestMs.accumulateAndGet(ms, ::maxOf)
    }

    fun recordCacheHit() {
        cacheHits.incrementAndGet()
    }

    fun recordNoAnswer() {
        noAnswers.incrementAndGet()
    }

    /** 転送した問い合わせの数、平均・最も遅かった応答時間 (ミリ秒)、覚えていた答えで返した数、答えが無かった数。 */
    class Latency(val forwarded: Int, val averageMs: Long, val slowestMs: Long, val cacheHits: Int, val noAnswers: Int) {
        /** 覚えていた答えで返した割合 (問い合わせが無ければ null)。 */
        val cacheRate: Double? get() = (forwarded + cacheHits).takeIf { it > 0 }?.let { cacheHits.toDouble() / it }
    }

    fun latency(): Latency {
        val n = forwardedCount.get()
        return Latency(n, if (n > 0) forwardedMs.get() / n else 0, slowestMs.get(), cacheHits.get(), noAnswers.get())
    }

    /** 数えるたびに増える (top() の結果を使い回せるかの判断に使う)。 */
    private val changes = AtomicLong()

    /** 直前の top() の結果。changes が同じ間は、並べ替えずにこれを返す。 */
    private class Top(val map: Map<String, AtomicInteger>, val n: Int, val at: Long, val result: List<Pair<String, Int>>)

    @Volatile
    private var lastTop: Top? = null

    @Volatile
    private var lastTopApps: Top? = null

    fun recordBlocked(domain: String) = count(domains, domain)

    /** app はパッケージ名。 */
    fun recordBlockedApp(app: String) = count(apps, app)

    private fun count(map: ConcurrentHashMap<String, AtomicInteger>, key: String) {
        val counter = map[key] ?: run {
            if (map.size >= MAX_KEYS) return
            map.computeIfAbsent(key) { AtomicInteger() }
        }
        counter.incrementAndGet()
        changes.incrementAndGet()
    }

    fun top(n: Int): List<Pair<String, Int>> = cachedTop(lastTop, domains, n).also { lastTop = it }.result

    /** ブロックしたドメインごとの回数 (内訳の集計用に、そのときの写し)。 */
    fun domainCounts(): Map<String, Int> = domains.mapValues { it.value.get() }

    fun topApps(n: Int): List<Pair<String, Int>> = cachedTop(lastTopApps, apps, n).also { lastTopApps = it }.result

    private fun cachedTop(last: Top?, map: ConcurrentHashMap<String, AtomicInteger>, n: Int): Top {
        val at = changes.get()
        if (last != null && last.map === map && last.n == n && last.at == at) return last
        return Top(map, n, at, map.entries.map { it.key to it.value.get() }.sortedByDescending { it.second }.take(n))
    }

    fun reset() {
        domains.clear()
        apps.clear()
        forwardedCount.set(0)
        forwardedMs.set(0)
        slowestMs.set(0)
        cacheHits.set(0)
        noAnswers.set(0)
        changes.incrementAndGet()
        ListBreakdown.clear()
    }
}
