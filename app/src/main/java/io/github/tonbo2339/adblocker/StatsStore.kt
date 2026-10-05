package io.github.tonbo2339.adblocker

import android.content.Context
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 日ごとの件数 (ブロック・問い合わせ) の履歴。**件数だけ** を保存し、ドメインは保存しない。
 *
 * 問い合わせのたびに書き込まないよう、件数はメモリにためて flush() でまとめて保存する
 * (VPN サービスが定期的に呼ぶ)。保存していない分も読み出しには含める。
 */
object StatsStore {
    private const val NAME = "stats"
    private const val DAY_PREFIX = "d"
    private const val KEY_TOTAL_BLOCKED = "total_blocked"
    private const val KEY_TOTAL_QUERIES = "total_queries"
    private const val KEY_SINCE = "since"
    private const val KEEP_DAYS = 90

    class Day(val date: Date, val blocked: Long, val queries: Long)
    class Totals(val blocked: Long, val queries: Long, val since: Long)

    private val pendingBlocked = AtomicLong()
    private val pendingQueries = AtomicLong()

    fun record(blocked: Boolean) {
        pendingQueries.incrementAndGet()
        if (blocked) pendingBlocked.incrementAndGet()
    }

    @Synchronized
    fun flush(context: Context) {
        val blocked = pendingBlocked.getAndSet(0)
        val queries = pendingQueries.getAndSet(0)
        if (blocked == 0L && queries == 0L) return
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        val key = dayKey(now)
        val (b, q) = parse(prefs.getString(key, null))
        prefs.edit {
            putString(key, "${b + blocked},${q + queries}")
            putLong(KEY_TOTAL_BLOCKED, prefs.getLong(KEY_TOTAL_BLOCKED, 0) + blocked)
            putLong(KEY_TOTAL_QUERIES, prefs.getLong(KEY_TOTAL_QUERIES, 0) + queries)
            if (!prefs.contains(KEY_SINCE)) putLong(KEY_SINCE, now)
            // 古い日を消す (キーは dYYYYMMDD なので文字列の比較で前後が分かる)
            val oldest = dayKey(daysAgo(now, KEEP_DAYS))
            for (k in prefs.all.keys) if (k.startsWith(DAY_PREFIX) && k < oldest) remove(k)
        }
    }

    /** 今日を最後とする n 日分 (古い順)。 */
    fun days(context: Context, n: Int): List<Day> {
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        return (n - 1 downTo 0).map { ago ->
            val time = daysAgo(now, ago)
            var (b, q) = parse(prefs.getString(dayKey(time), null))
            if (ago == 0) {
                b += pendingBlocked.get()
                q += pendingQueries.get()
            }
            Day(Date(time), b, q)
        }
    }

    fun today(context: Context): Day = days(context, 1).first()

    fun totals(context: Context): Totals {
        val prefs = prefs(context)
        val queries = prefs.getLong(KEY_TOTAL_QUERIES, 0) + pendingQueries.get()
        // まだ保存していない分しか無ければ、集計の開始は今日
        val since = prefs.getLong(KEY_SINCE, 0).takeIf { it != 0L } ?: if (queries > 0) System.currentTimeMillis() else 0L
        return Totals(prefs.getLong(KEY_TOTAL_BLOCKED, 0) + pendingBlocked.get(), queries, since)
    }

    @Synchronized
    fun clear(context: Context) {
        pendingBlocked.set(0)
        pendingQueries.set(0)
        prefs(context).edit { clear() }
    }

    private fun parse(value: String?): Pair<Long, Long> {
        val parts = value?.split(',') ?: return 0L to 0L
        return (parts.getOrNull(0)?.toLongOrNull() ?: 0L) to (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
    }

    private fun dayKey(time: Long): String =
        DAY_PREFIX + SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date(time))

    private fun daysAgo(now: Long, days: Int): Long =
        Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -days)
        }.timeInMillis

    private fun prefs(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
