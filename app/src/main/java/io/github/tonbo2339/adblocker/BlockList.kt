package io.github.tonbo2339.adblocker

import android.content.Context
import java.io.File

/**
 * ブロック対象ドメインの集合。
 *
 * BlockListUpdater がダウンロードしたリスト (filesDir/blocklists/ 内の .txt) を使い、
 * StevenBlack のリストをオンにしていて、まだダウンロードできていなければ、同梱の assets/blocklist.txt を使う。
 * reload() は集合を丸ごと差し替えるので、VPN を止めずに反映される。
 */
object BlockList {
    private const val ASSET = "blocklist.txt"
    private const val DIR = "blocklists"

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

    /** まだ読み込んでいなければ読み込む。 */
    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (!loaded) reload(context)
        }
    }

    @Synchronized
    fun reload(context: Context) {
        val blocked = DomainSet.Builder()
        val allowed = DomainSet.Builder()
        val dir = dir(context)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }.orEmpty()
        for (f in files) f.bufferedReader().useLines { addNormalized(it, blocked, allowed) }
        // 同梱リストを使うかは、上で読んだ一覧だけで決める。別に exists() で調べると、その間に
        // ダウンロードしたファイルの名前が確定したとき、どちらも読まずに 0 件になる (初回起動時に実際に起きた)
        val bundledEnabled = Prefs.isSourceEnabled(context, BlockListUpdater.BUILT_IN.first { it.id == BUNDLED_SOURCE_ID })
        if (bundledEnabled && files.none { it.name == "$BUNDLED_SOURCE_ID.txt" }) {
            context.assets.open(ASSET).bufferedReader().useLines { addNormalized(it, blocked, allowed) }
        }
        rules = Rules(blocked.build(), allowed.build())
        loaded = true
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
