package io.github.tonbo2339.adblocker.block

import android.content.Context
import io.github.tonbo2339.adblocker.SessionStats

/**
 * 今回ブロックしたドメインが、どのリストに載っているかの内訳 (統計の「リスト別」)。
 * ブロックのたびには調べない。統計を開いたとき、ダウンロードしたリストのファイルを読み直して数える
 * (リストごとの集合を常に持つと、メモリが増えるため)。同じドメインが複数のリストに載っていれば、それぞれに数える。
 */
object ListBreakdown {
    /** 再計算の最短の間隔 (ファイルを読むので、統計を開いている間も頻繁には行わない)。 */
    private const val MIN_INTERVAL_MS = 15_000L

    class Row(val source: String, val blocked: Int)

    @Volatile
    var rows: List<Row> = emptyList()
        private set

    /** rows を作ったときのブロック数の合計 (増えていなければ作り直さない)。 */
    private var computedTotal = 0
    private var computedAt = 0L
    private var running = false

    /** clear() のたびに増える。数えている間に集計が消されたら、その結果は捨てる。 */
    private var epoch = 0

    /** 集計が消された (SessionStats.reset) とき、内訳も消す。 */
    @Synchronized
    fun clear() {
        epoch++
        rows = emptyList()
        computedTotal = 0
        computedAt = 0L
    }

    /** 必要なら別のスレッドで数え直して、終わったら onDone (そのスレッドで呼ぶ)。数え直すときだけ true を返す。 */
    @Synchronized
    fun refreshIfStale(context: Context, now: Long = System.currentTimeMillis(), onDone: () -> Unit): Boolean {
        // 統計の画面は 1 秒ごとに呼ぶので、軽い確認 (実行中・間隔) を先に済ませる
        if (running || now - computedAt < MIN_INTERVAL_MS) return false
        val counts = SessionStats.domainCounts()
        val total = counts.values.sum()
        if (total == computedTotal) return false
        running = true
        val startedEpoch = epoch
        val app = context.applicationContext
        Thread({
            try {
                val result = compute(app, counts)
                synchronized(this) {
                    if (epoch == startedEpoch) {
                        rows = result
                        computedTotal = total
                        computedAt = System.currentTimeMillis()
                    }
                }
                onDone()
            } finally {
                synchronized(this) { running = false }
            }
        }, "ListBreakdown").start()
        return true
    }

    private fun compute(context: Context, counts: Map<String, Int>): List<Row> {
        val index = index(counts)
        val totals = LinkedHashMap<String, Int>()
        DomainCheck.eachList(context) { source, lines -> totals[source] = count(lines, index, counts) }
        return totals.filter { it.value > 0 }.map { Row(it.key, it.value) }.sortedByDescending { it.blocked }
    }

    /** リストの行に書かれうるドメイン (問い合わせた名前そのものと、その親) → それで止まる問い合わせ名、を引けるようにする。 */
    internal fun index(counts: Map<String, Int>): Map<String, List<String>> {
        val byListed = HashMap<String, MutableList<String>>()
        for (name in counts.keys) for (listed in UserRules.selfAndParents(name)) byListed.getOrPut(listed) { mutableListOf() } += name
        return byListed
    }

    /** lines (正規化済みのリストの行) に載っているドメインについて、counts のブロック回数を合計する。同じ名前は 1 回だけ数える。 */
    internal fun count(lines: Sequence<String>, index: Map<String, List<String>>, counts: Map<String, Int>): Int {
        val matched = HashSet<String>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '#' || line.startsWith("@@")) continue
            index[line]?.let { matched += it }
        }
        return matched.sumOf { counts.getValue(it) }
    }
}
