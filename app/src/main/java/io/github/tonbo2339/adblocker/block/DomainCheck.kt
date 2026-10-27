package io.github.tonbo2339.adblocker.block

import android.content.Context
import io.github.tonbo2339.adblocker.Pause
import io.github.tonbo2339.adblocker.WifiNetworks
import io.github.tonbo2339.adblocker.data.Prefs

/**
 * 「このドメインはなぜブロックされる (されない) のか」を調べる。
 * 問い合わせのたびには使わない。調べるときだけ、ダウンロードしたリストのファイルを読み直して当たる行を探す
 * (リストごとの集合を常に持つと、メモリが増えるため)。ファイルを読むのでメインスレッドで呼ばない。
 */
object DomainCheck {
    /** リストの 1 行が当たった。domain は当たったドメイン (調べた名前そのものか、その親)。 */
    class Hit(val source: String, val domain: String, val allow: Boolean)

    class Result(
        val name: String,
        /** 実際に今ブロックされるか (一時停止や Wi-Fi の設定も含めた結果)。 */
        val blocked: Boolean,
        val paused: Boolean,
        val wifiUnblocked: Boolean,
        val userRule: Pair<UserRules.Kind, String>?,
        /** どのリストでも止めないドメインに当たったとき、そのドメイン。 */
        val builtinAllowed: String?,
        val hits: List<Hit>,
    )

    fun check(context: Context, name: String): Result {
        val candidates = UserRules.selfAndParents(name).toSet()
        val hits = mutableListOf<Hit>()
        eachList(context) { source, lines -> hits += scan(lines, candidates, source) }
        return Result(
            name = name,
            blocked = Filter.decide(name).blocked,
            paused = Pause.isPaused(),
            wifiUnblocked = WifiNetworks.isUnblocked(),
            userRule = UserRules.ruleOwner(name),
            builtinAllowed = candidates.firstOrNull { it in BlockList.ALWAYS_ALLOWED },
            hits = hits,
        )
    }

    /** 正規化済みの行 (ドメイン、"@@ドメイン"、"#" コメント) のうち、candidates に当たるもの。 */
    internal fun scan(lines: Sequence<String>, candidates: Set<String>, source: String): List<Hit> {
        val hits = mutableListOf<Hit>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '#') continue
            val allow = line.startsWith("@@")
            val domain = if (allow) line.substring(2) else line
            if (domain in candidates) hits += Hit(source, domain, allow)
        }
        return hits
    }

    /** 使っているリストを 1 つずつ、(リストの名前, 正規化済みの行) で渡す。ファイルを読むのでメインスレッドで呼ばない。 */
    internal fun eachList(context: Context, action: (source: String, lines: Sequence<String>) -> Unit) {
        val files = BlockList.downloadedFiles(context)
        for (file in files) {
            file.bufferedReader().useLines { action(sourceName(context, file.name.removeSuffix(".txt")), it) }
        }
        if (BlockList.bundledInUse(context, files)) {
            BlockList.openBundled(context).bufferedReader().useLines { action(sourceName(context, BlockList.BUNDLED_SOURCE_ID), it) }
        }
    }

    private fun sourceName(context: Context, id: String): String =
        BlockListUpdater.BUILT_IN.firstOrNull { it.id == id }?.name
            ?: Prefs.customSourceUrls(context).map { BlockListUpdater.custom(it) }.firstOrNull { it.id == id }?.name
            ?: id
}
