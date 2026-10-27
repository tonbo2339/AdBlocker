package io.github.tonbo2339.adblocker.block

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.tonbo2339.adblocker.DebugLog
import io.github.tonbo2339.adblocker.Prefs
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
) {
    /** 端末のファイル (ファイルの選択で追加したもの)。URL ではなく、読む許可を保存した content:// の場所。 */
    val isFile: Boolean get() = BlockListUpdater.isFileUrl(url)
}

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

    /** 利用者が追加した取得元 (URL か端末のファイル)。id は URL から作る (同じ URL なら同じファイル)。 */
    fun custom(url: String): BlockListSource {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        val id = "custom-" + digest.take(6).joinToString("") { "%02x".format(it) }
        // ファイルの名前は、選んだファイルの場所からは分からない (表示のときに fileName で調べる)。それまでは仮の名前
        val name = if (isFileUrl(url)) "File" else runCatching { java.net.URI(url).host }.getOrNull() ?: url
        return BlockListSource(id, name, url, builtIn = false)
    }

    fun isFileUrl(url: String): Boolean = url.startsWith("content://")

    /** 取得元の表示名。ファイルは、ファイルの名前 (調べられなければ仮の名前)。ファイルの提供元に尋ねるので、メインスレッドで呼ばない。 */
    fun displayName(context: Context, source: BlockListSource): String {
        if (!source.isFile) return source.name
        return try {
            context.contentResolver.query(Uri.parse(source.url), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: source.name
        } catch (e: Exception) {
            source.name
        }
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

    /**
     * 端末のファイルの取得元だけを読み直す (ネットワークは使わない)。ファイルを足したときに、通信を待たずに反映するために使う。
     * 中身が変わっていれば BlockList に反映する。
     */
    @Synchronized
    fun updateFiles(context: Context) {
        val dir = BlockList.dir(context).apply { mkdirs() }
        var changed = false
        for (source in sources(context).filter { it.isFile }) {
            try {
                if (fetch(context, source, dir)) changed = true
            } catch (e: Exception) {
                DebugLog.w(TAG, "file source failed: ${source.id}", e)
            }
        }
        if (changed) BlockList.reload(context)
    }

    /** 使わなくなった取得元のファイルを消して、すぐ読み直す (ネットワークは使わない)。 */
    @Synchronized
    fun applySelection(context: Context) {
        removeUnused(context, BlockList.dir(context))
        BlockList.reload(context)
    }

    /** 選んだ取得元で、まだダウンロードしていないものがあるか。 */
    fun hasMissing(context: Context): Boolean {
        val dir = BlockList.dir(context)
        // 端末のファイルは通信が要らない (updateFiles で読む) ので、ダウンロードの対象に数えない
        return sources(context).any { it.id != BlockList.BUNDLED_SOURCE_ID && !it.isFile && !File(dir, "${it.id}.txt").exists() }
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
                DebugLog.w(TAG, "update failed: ${source.id}", e)
                failed += source.name
            }
        }
        // オフにした・取得元の一覧から外したリストのファイルは消す
        val removed = removeUnused(context, dir)

        if (updated > 0 || removed) BlockList.reload(context)
        DebugLog.i(TAG, "update: $updated updated, $unchanged unchanged, ${failed.size} failed")
        if (failed.isEmpty()) Prefs.setBlocklistCheckedAt(context, System.currentTimeMillis())
        return Result(updated, unchanged, failed)
    }

    /** リストの行を読んで、正規化したルールを tmp に書く。書いたルールの数を返す。 */
    private fun writeRules(lines: Sequence<String>, tmp: File): Int {
        var count = 0
        tmp.bufferedWriter().use { out ->
            for (line in lines) {
                val rule = RuleParser.parse(line) ?: continue
                out.write(RuleParser.format(rule))
                out.write('\n'.code)
                count++
            }
        }
        return count
    }

    /**
     * 端末のファイルの取得元を読んで保存する。内容が前と同じなら保存し直さず false (毎日の更新で、リストの組み立て直しを避ける)。
     * ファイルが消えた・読む許可を失ったときは例外 (前回の内容を使い続け、更新の失敗として知らせる)。
     */
    private fun readFile(context: Context, source: BlockListSource, dir: File): Boolean {
        val target = File(dir, "${source.id}.txt")
        val tmp = File(dir, "${source.id}.tmp")
        val input = context.contentResolver.openInputStream(Uri.parse(source.url)) ?: throw IOException("cannot open the file")
        val count = input.bufferedReader().useLines { writeRules(it, tmp) }
        if (count < 1) {
            tmp.delete()
            throw IOException("no rules in the file")
        }
        if (target.exists() && target.length() == tmp.length() && target.readBytes().contentEquals(tmp.readBytes())) {
            tmp.delete()
            return false
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("rename failed")
        }
        DebugLog.i(TAG, "${source.id}: $count rules (file)")
        return true
    }

    /** 1 つの取得元を取得する。内容が変わって保存したら true、変更なし (304) なら false。 */
    private fun fetch(context: Context, source: BlockListSource, dir: File): Boolean {
        if (source.isFile) return readFile(context, source, dir)
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
            val count = conn.inputStream.bufferedReader().useLines { writeRules(it, tmp) }
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
            DebugLog.i(TAG, "${source.id}: $count rules")
            return true
        } finally {
            conn.disconnect()
        }
    }
}
