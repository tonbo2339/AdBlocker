package io.github.tonbo2339.adblocker

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 問い合わせのログをファイルに残す (設定「ログを残す期間」が 1 日以上のときだけ。既定はメモリ上だけ)。
 *
 * filesDir/querylog/yyyyMMdd.tsv に 1 日 1 ファイル、1 行 1 問い合わせ (時刻・結果・ドメイン・理由・アプリ) を追記する。
 * 書き込みは専用のスレッドで行い、続けて届いた分を書き終えるたびにディスクに出す。
 * 期間を過ぎた日のファイルは消す。端末の外には送らず、バックアップの対象からも外している。
 */
object QueryLogFiles {
    private const val TAG = "QueryLogFiles"
    private const val DIR = "querylog"
    private const val SUFFIX = ".tsv"

    /** 書き込み待ちの行の数。 */
    private val pending = java.util.concurrent.atomic.AtomicInteger()

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "QueryLogFiles").apply { isDaemon = true } }

    @Volatile
    private var dir: File? = null

    /** ファイルに残す日数 (0 なら残さない)。 */
    @Volatile
    var retentionDays = 0
        private set

    // 以下は executor のスレッドからだけ触る
    private var writer: BufferedWriter? = null
    private var writerDay: String? = null

    /** プロセスの開始時と設定を変えたときに呼ぶ。0 にしたら残したファイルを消す。 */
    fun configure(context: Context, days: Int) {
        val d = File(context.applicationContext.filesDir, DIR)
        dir = d
        retentionDays = days
        executor.execute {
            if (days == 0) {
                closeWriter()
                d.listFiles()?.forEach { it.delete() }
            } else {
                prune(d, days)
            }
        }
    }

    fun append(entry: QueryLog.Entry) {
        if (retentionDays == 0) return
        val d = dir ?: return
        pending.incrementAndGet()
        executor.execute {
            try {
                val day = dayName(entry.time)
                if (day != writerDay) {
                    closeWriter()
                    d.mkdirs()
                    prune(d, retentionDays)
                    writer = FileOutputStream(File(d, day + SUFFIX), true).bufferedWriter()
                    writerDay = day
                }
                writer?.apply {
                    write(format(entry))
                    write('\n'.code)
                }
            } catch (e: IOException) {
                Log.w(TAG, "write failed", e)
                closeWriter()
            }
            // 続けて届いた分を書き終えたら、まとめてディスクに出す (プロセスが終了させられても失わないように)
            if (pending.decrementAndGet() == 0) flushWriter()
        }
    }

    fun flush() {
        executor.execute { flushWriter() }
    }

    /** 保存しているログのファイルがあるか (消す前に確認するため)。 */
    fun hasSavedLog(): Boolean = dir?.let { files(it).isNotEmpty() } == true

    /** 新しい日のファイルから、直近 limit 件を古い順に読む (プロセスの開始時に画面のログへ戻す)。ディスクを読むのでメインスレッドで呼ばない。 */
    fun recent(limit: Int): List<QueryLog.Entry> {
        val d = dir ?: return emptyList()
        flushNow()
        val result = ArrayDeque<QueryLog.Entry>()
        for (file in files(d).asReversed()) {
            val lines = file.readLines().mapNotNull { parse(it) }
            for (e in lines.asReversed()) {
                result.addFirst(e)
                if (result.size >= limit) return result.toList()
            }
        }
        return result.toList()
    }

    /** 残しているログをすべて CSV で書き出す (古い順)。ディスクを読むのでメインスレッドで呼ばない。 */
    fun exportCsv(out: OutputStream, fallback: List<QueryLog.Entry>) {
        val d = dir
        flushNow()
        val time = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.ROOT)
        out.bufferedWriter().use { w ->
            w.write("time,result,domain,via,app\n")
            fun row(e: QueryLog.Entry) {
                val cells = listOf(time.format(Date(e.time)), e.verdict.name, e.domain, e.via.orEmpty(), e.app.orEmpty())
                w.write(cells.joinToString(",") { csv(it) })
                w.write("\n")
            }
            val saved = if (retentionDays > 0 && d != null) files(d) else emptyList()
            if (saved.isEmpty()) {
                // ファイルに残していなければ、メモリ上のログ (新しい順) を古い順にして書く
                fallback.asReversed().forEach { row(it) }
            } else {
                for (file in saved) file.forEachLine { line -> parse(line)?.let { row(it) } }
            }
        }
    }

    // ---- 以下は内部 ----

    /** 書きかけの分をディスクに出し、終わるまで待つ。 */
    private fun flushNow() {
        try {
            executor.submit { flushWriter() }.get()
        } catch (e: Exception) {
            Log.w(TAG, "flush failed", e)
        }
    }

    /** executor のスレッドから呼ぶ。 */
    private fun flushWriter() {
        try {
            writer?.flush()
        } catch (e: IOException) {
            Log.w(TAG, "flush failed", e)
        }
    }

    private fun closeWriter() {
        try {
            writer?.close()
        } catch (_: IOException) {
        }
        writer = null
        writerDay = null
    }

    /** 日付の古い順。 */
    private fun files(d: File): List<File> =
        d.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }.orEmpty().sortedBy { it.name }

    /** 残す日数を過ぎた日のファイルを消す (今日を含めて days 日分を残す)。 */
    private fun prune(d: File, days: Int) {
        val oldest = dayName(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -(days - 1)) }.timeInMillis)
        for (f in files(d)) if (f.name.removeSuffix(SUFFIX) < oldest) f.delete()
    }

    private fun dayName(time: Long): String = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date(time))

    /**
     * 1 行に書く。DNS の名前にはどんなバイトも入りうるので、タブや改行などの制御文字は「?」に置き換える
     * (そのままだと行や列が崩れ、偽の行を差し込まれる恐れがある)。
     */
    private fun format(e: QueryLog.Entry): String =
        listOf(e.time.toString(), e.verdict.name, clean(e.domain), clean(e.via.orEmpty()), clean(e.app.orEmpty()))
            .joinToString("\t")

    private fun clean(s: String): String =
        if (s.none { it.isISOControl() }) s else s.map { if (it.isISOControl()) '?' else it }.joinToString("")

    private fun parse(line: String): QueryLog.Entry? {
        val p = line.split('\t')
        if (p.size < 5) return null
        val time = p[0].toLongOrNull() ?: return null
        val verdict = Verdict.entries.firstOrNull { it.name == p[1] } ?: return null
        return QueryLog.Entry(p[2], verdict, time, 1, p[3].ifEmpty { null }, p[4].ifEmpty { null })
    }

    /** CSV の 1 つの値。表計算ソフトで数式として動かないよう、= + - @ で始まるものは先頭に ' を付ける。 */
    private fun csv(value: String): String {
        val s = if (value.isNotEmpty() && value[0] in "=+-@") "'$value" else value
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}

/** ログを残す期間の文中での書き方 (「7 日」など)。 */
fun Context.logPeriodText(days: Int): String = getString(
    when (days) {
        1 -> R.string.log_period_1
        7 -> R.string.log_period_7
        else -> R.string.log_period_30
    }
)
