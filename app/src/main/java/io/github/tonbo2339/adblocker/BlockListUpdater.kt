package io.github.tonbo2339.adblocker

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** ブロックリストの取得元。id は保存ファイル名に使う。 */
data class BlockListSource(val id: String, val name: String, val url: String)

/**
 * ブロックリストをダウンロードして filesDir/blocklists/<id>.txt に保存する。
 *
 * 取得元ごとに前回分を残すので、1 つが取得できなくなっても他は更新され、取得できないものは前回の内容を使い続ける。
 * 中身が少なすぎる (取得元の障害でほぼ空のファイルが返った等) 場合は保存しない。
 */
object BlockListUpdater {
    private const val TAG = "BlockListUpdater"
    private const val MIN_RULES = 1000
    private const val TIMEOUT_MS = 30_000

    val SOURCES = listOf(
        BlockListSource(
            id = BlockList.BUNDLED_SOURCE_ID,
            name = "StevenBlack hosts",
            url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        ),
        BlockListSource(
            id = "adguard-dns",
            name = "AdGuard DNS filter",
            url = "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
        ),
    )

    class Result(val updated: Int, val unchanged: Int, val failed: List<String>) {
        val success: Boolean get() = failed.isEmpty()
    }

    /** すべての取得元を更新し、変更があれば BlockList に反映する。ネットワークを使うのでメインスレッドで呼ばない。 */
    @Synchronized
    fun update(context: Context): Result {
        val dir = BlockList.dir(context).apply { mkdirs() }
        var updated = 0
        var unchanged = 0
        val failed = mutableListOf<String>()
        for (source in SOURCES) {
            try {
                if (fetch(context, source, dir)) updated++ else unchanged++
            } catch (e: Exception) {
                Log.w(TAG, "update failed: ${source.id}", e)
                failed += source.name
            }
        }
        // 取得元の一覧から外したリストのファイルは消す
        val ids = SOURCES.map { "${it.id}.txt" }.toSet()
        dir.listFiles()?.filter { it.name !in ids }?.forEach { it.delete() }

        if (updated > 0) BlockList.reload(context)
        if (failed.isEmpty()) Prefs.setBlocklistCheckedAt(context, System.currentTimeMillis())
        return Result(updated, unchanged, failed)
    }

    /** 1 つの取得元を取得する。内容が変わって保存したら true、変更なし (304) なら false。 */
    private fun fetch(context: Context, source: BlockListSource, dir: File): Boolean {
        val target = File(dir, "${source.id}.txt")
        val conn = URL(source.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "AdBlocker-Android")
            // 保存済みのときだけ条件付きリクエストにする (ファイルが消えていたら取り直す)
            val cached = Prefs.sourceCache(context, source.id)
            if (target.exists() && cached != null) {
                cached.etag?.let { conn.setRequestProperty("If-None-Match", it) }
                cached.lastModified?.let { conn.setRequestProperty("If-Modified-Since", it) }
            }

            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> return false
                HttpURLConnection.HTTP_OK -> {}
                else -> throw IOException("HTTP $code")
            }

            val tmp = File(dir, "${source.id}.tmp")
            var count = 0
            tmp.bufferedWriter().use { out ->
                conn.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val rule = RuleParser.parse(line) ?: continue
                        out.write(RuleParser.format(rule))
                        out.write('\n'.code)
                        count++
                    }
                }
            }
            if (count < MIN_RULES) {
                tmp.delete()
                throw IOException("too few rules ($count)")
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("rename failed")
            }
            Prefs.setSourceCache(
                context, source.id,
                Prefs.SourceCache(conn.getHeaderField("ETag"), conn.getHeaderField("Last-Modified")),
            )
            Log.i(TAG, "${source.id}: $count rules")
            return true
        } finally {
            conn.disconnect()
        }
    }
}
