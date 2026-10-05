package io.github.tonbo2339.adblocker

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * ブロック対象ドメインの集合。
 *
 * BlockListUpdater がダウンロードしたリスト (filesDir/blocklists/ 内の .txt) を使い、
 * StevenBlack のリストをオンにしていて、まだダウンロードできていなければ、同梱の assets/blocklist.txt を使う。
 * reload() は集合を丸ごと差し替えるので、VPN を止めずに反映される。
 *
 * 組み立てた集合は filesDir/blocklist.cache に保存し、元のファイルが変わっていなければ次の起動ではそれを読む
 * (数十万行の文字列を読み直してハッシュを計算するより、ずっと速い)。
 */
object BlockList {
    private const val ASSET = "blocklist.txt"
    private const val DIR = "blocklists"
    private const val CACHE = "blocklist.cache"
    private const val TAG = "BlockList"

    /** assets/blocklist.txt の中身と同じ取得元。 */
    const val BUNDLED_SOURCE_ID = "stevenblack"

    private class Rules(val blocked: DomainSet, val allowed: DomainSet)

    @Volatile
    private var rules = Rules(DomainSet.EMPTY, DomainSet.EMPTY)

    @Volatile
    private var loaded = false

    val size: Int get() = rules.blocked.size

    /** ダウンロードしたリストの保存先。 */
    fun dir(context: Context): File = File(context.filesDir, DIR)

    /** まだ読み込んでいなければ読み込む (保存した集合が使えればそれを読む)。 */
    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (!loaded) build(context, useCache = true)
        }
    }

    /** リストのファイルから組み立て直す (更新・取得元の選択を変えたとき)。 */
    @Synchronized
    fun reload(context: Context) = build(context, useCache = false)

    private fun build(context: Context, useCache: Boolean) {
        val started = SystemClock.elapsedRealtime()
        val dir = dir(context)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }.orEmpty().sortedBy { it.name }
        // 同梱リストを使うかは、上で読んだ一覧だけで決める。別に exists() で調べると、その間に
        // ダウンロードしたファイルの名前が確定したとき、どちらも読まずに 0 件になる (初回起動時に実際に起きた)
        val bundledEnabled = Prefs.isSourceEnabled(context, BlockListUpdater.BUILT_IN.first { it.id == BUNDLED_SOURCE_ID })
        val useBundled = bundledEnabled && files.none { it.name == "$BUNDLED_SOURCE_ID.txt" }
        // 元のファイルの名前・大きさ・更新日時と、同梱リスト (アプリの版で変わる) で、保存した集合が使えるか決める
        val key = buildString {
            append(BuildConfig.VERSION_CODE).append('|').append(useBundled)
            for (f in files) append('|').append(f.name).append(':').append(f.length()).append(':').append(f.lastModified())
        }
        val cacheFile = File(context.filesDir, CACHE)
        val cached = if (useCache) BlockListCache.read(cacheFile, key) else null
        if (cached != null) {
            rules = Rules(cached.first, cached.second)
        } else {
            val blocked = DomainSet.Builder()
            val allowed = DomainSet.Builder()
            for (f in files) f.bufferedReader().useLines { addNormalized(it, blocked, allowed) }
            if (useBundled) context.assets.open(ASSET).bufferedReader().useLines { addNormalized(it, blocked, allowed) }
            rules = Rules(blocked.build(), allowed.build())
            BlockListCache.write(cacheFile, key, rules.blocked, rules.allowed)
        }
        loaded = true
        Log.i(TAG, "${rules.blocked.size} domains (${if (cached != null) "cache" else "lists"}, ${SystemClock.elapsedRealtime() - started} ms)")
    }

    /**
     * 正規化済みの行 (RuleParser.format の出力と同梱リスト: ドメイン、"@@ドメイン"、"#" コメント) を読む。
     * 自分で書いたファイルなので、取得時のような検証はしない。
     */
    private fun addNormalized(lines: Sequence<String>, blocked: DomainSet.Builder, allowed: DomainSet.Builder) {
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.isEmpty() || line[0] == '#' -> {}
                line.startsWith("@@") -> allowed.add(line.substring(2))
                else -> blocked.add(line)
            }
        }
    }

    /**
     * 名前そのものか、その親ドメインがリストにあればブロック (ads.example.com → example.com も見る)。
     * ただし例外ルール (@@) に当たれば通す。
     */
    fun isBlocked(name: String): Boolean {
        val r = rules
        return r.blocked.matchesSelfOrParent(name) && !r.allowed.matchesSelfOrParent(name)
    }
}

/** 組み立て済みの DomainSet の保存と読み込み。壊れていたり key が違ったりすれば null (組み立て直す)。 */
internal object BlockListCache {
    private const val MAGIC = 0x41424c43 // "ABLC"
    private const val FORMAT = 1

    fun read(file: File, key: String): Pair<DomainSet, DomainSet>? {
        if (!file.exists()) return null
        return try {
            val buf = java.nio.ByteBuffer.wrap(file.readBytes())
            if (buf.int != MAGIC || buf.int != FORMAT) return null
            val keyBytes = ByteArray(buf.int).also { buf.get(it) }
            if (String(keyBytes, Charsets.UTF_8) != key) return null
            val blocked = DomainSet.fromSorted(longs(buf)) ?: return null
            val allowed = DomainSet.fromSorted(longs(buf)) ?: return null
            blocked to allowed
        } catch (e: Exception) {
            // 途中で切れている (BufferUnderflowException) など
            null
        }
    }

    fun write(file: File, key: String, blocked: DomainSet, allowed: DomainSet) {
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val b = blocked.toArray()
        val a = allowed.toArray()
        val buf = java.nio.ByteBuffer.allocate(4 * 5 + keyBytes.size + 8 * (b.size + a.size))
        buf.putInt(MAGIC).putInt(FORMAT).putInt(keyBytes.size).put(keyBytes)
        buf.putInt(b.size)
        buf.asLongBuffer().put(b)
        buf.position(buf.position() + 8 * b.size)
        buf.putInt(a.size)
        buf.asLongBuffer().put(a)
        // 書きかけのファイルを読まないよう、別名で書いてから置き換える
        val tmp = File(file.path + ".tmp")
        try {
            tmp.writeBytes(buf.array())
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: java.io.IOException) {
            Log.w("BlockList", "cache write failed", e)
            tmp.delete()
        }
    }

    private fun longs(buf: java.nio.ByteBuffer): LongArray {
        val n = buf.int
        if (n < 0 || n.toLong() * 8 > buf.remaining()) throw java.io.IOException("bad length")
        val out = LongArray(n)
        buf.asLongBuffer().get(out)
        buf.position(buf.position() + 8 * n)
        return out
    }
}
