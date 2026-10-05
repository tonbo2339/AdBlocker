package io.github.tonbo2339.adblocker

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * ブロックリストの取得元。id は保存ファイル名に使う。
 * builtIn でないものは利用者が URL で追加したもの (件数の下限を緩める)。
 */
data class BlockListSource(
    val id: String,
    val name: String,
    val url: String,
    val builtIn: Boolean = true,
    val enabledByDefault: Boolean = true,
    val license: String? = null,
)

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

    /** アプリに組み込みの取得元。設定でオン / オフできる。 */
    val BUILT_IN = listOf(
        BlockListSource(
            id = BlockList.BUNDLED_SOURCE_ID,
            name = "StevenBlack hosts",
            url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            license = "MIT",
        ),
        BlockListSource(
            id = "adguard-dns",
            name = "AdGuard DNS filter",
            url = "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
            license = "GPL-3.0",
        ),
        // HaGeZi Pro: 日本のサイトを含む 30 サイトで調べると、上の 2 つが通す広告・トラッカーのドメインの 6 割ほどを止めた
        // (2026-10-06)。誤って止めることが少ないと作者が勧める強さなので、既定でオンにする (前の Multi NORMAL は Pro に含まれる)
        BlockListSource(
            id = "hagezi-pro",
            name = "HaGeZi Pro",
            url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/pro.txt",
            license = "GPL-3.0",
        ),
        // Pro++: さらに強い。必要なものまで止めることが増えるので既定はオフ
        BlockListSource(
            id = "hagezi-proplus",
            name = "HaGeZi Pro++",
            url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/pro.plus.txt",
            enabledByDefault = false,
            license = "GPL-3.0",
        ),
    )

    /** 利用者が追加した URL の取得元。id は URL から作る (同じ URL なら同じファイル)。 */
    fun custom(url: String): BlockListSource {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        val id = "custom-" + digest.take(6).joinToString("") { "%02x".format(it) }
        val name = runCatching { java.net.URI(url).host }.getOrNull() ?: url
        return BlockListSource(id, name, url, builtIn = false)
    }

    /**
     * 追加できる URL か確かめて整える。https だけを受け付ける (平文の http だと途中で書き換えられる恐れがある)。
     * 画面からの追加と設定の読み込みの両方で使う。
     */
    fun normalizeUrl(text: String): String? {
        val url = text.trim()
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrEmpty()) return null
        return url
    }

    /** 今使う取得元 (オンの組み込み + 追加した URL)。 */
    fun sources(context: Context): List<BlockListSource> =
        BUILT_IN.filter { Prefs.isSourceEnabled(context, it) } +
            Prefs.customSourceUrls(context).sorted().map { custom(it) }

    /** 使わなくなった取得元のファイルを消して、すぐ読み直す (ネットワークは使わない)。 */
    @Synchronized
    fun applySelection(context: Context) {
        removeUnused(context, BlockList.dir(context))
        BlockList.reload(context)
    }

    /** 選んだ取得元で、まだダウンロードしていないものがあるか。 */
    fun hasMissing(context: Context): Boolean {
        val dir = BlockList.dir(context)
        return sources(context).any { it.id != BlockList.BUNDLED_SOURCE_ID && !File(dir, "${it.id}.txt").exists() }
    }

    /** 消したファイルがあれば true。 */
    private fun removeUnused(context: Context, dir: File): Boolean {
        val ids = sources(context).map { "${it.id}.txt" }.toSet()
        val unused = dir.listFiles()?.filter { it.name !in ids }.orEmpty()
        unused.forEach { it.delete() }
        return unused.isNotEmpty()
    }

    /** ダウンロード済みのリストのルール数 (まだ無ければ null)。ファイルを読むのでメインスレッドで呼ばない。 */
    fun ruleCount(context: Context, source: BlockListSource): Int? {
        val file = File(BlockList.dir(context), "${source.id}.txt")
        if (!file.exists()) return null
        return file.bufferedReader().useLines { lines -> lines.count { it.isNotBlank() && !it.startsWith("#") } }
    }

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
        for (source in sources(context)) {
            try {
                if (fetch(context, source, dir)) updated++ else unchanged++
            } catch (e: Exception) {
                Log.w(TAG, "update failed: ${source.id}", e)
                failed += source.name
            }
        }
        // オフにした・取得元の一覧から外したリストのファイルは消す
        val removed = removeUnused(context, dir)

        if (updated > 0 || removed) BlockList.reload(context)
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
            // 自分で追加したリストは小さくてもよい (取得元の障害で空になったものだけ弾く)
            if (count < if (source.builtIn) MIN_RULES else 1) {
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
