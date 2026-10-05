package io.github.tonbo2339.adblocker

/** ブロックリストの 1 行を解釈した結果。 */
sealed interface Rule {
    val domain: String

    data class Block(override val domain: String) : Rule
    data class Allow(override val domain: String) : Rule
}

/**
 * hosts 形式 (`0.0.0.0 ads.example.com`)、ドメインだけの形式、AdGuard / Adblock の DNS 用ルール
 * (`||ads.example.com^`、例外 `@@||example.com^`) を読む。
 * それ以外 (正規表現、ワイルドカード、$client 等の修飾子付き) は DNS 単位で判定できないので無視する。
 */
object RuleParser {
    private val SINK_ADDRESSES = setOf("0.0.0.0", "127.0.0.1", "::", "::1")
    private val NOT_DOMAINS = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "0.0.0.0")
    private val DOMAIN = Regex("^(?=.{1,253}$)([a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?\\.)+[a-z0-9-]{2,63}$")

    /** アプリが保存する正規化済みの形式 (1 行 1 ルール)。例外は "@@" を付ける。 */
    fun format(rule: Rule): String = when (rule) {
        is Rule.Block -> rule.domain
        is Rule.Allow -> "@@" + rule.domain
    }

    fun parse(rawLine: String): Rule? {
        val line = rawLine.trim()
        if (line.isEmpty() || line[0] == '#' || line[0] == '!' || line[0] == '[') return null

        if (line.startsWith("@@||")) return adblock(line.substring(4))?.let { Rule.Allow(it) }
        if (line.startsWith("@@")) return domainOrNull(line.substring(2))?.let { Rule.Allow(it) }
        if (line.startsWith("||")) return adblock(line.substring(2))?.let { Rule.Block(it) }

        // 行末コメント (空白の後の #) を除いて空白で分割。
        // 空白の無い # は、Adblock 形式の要素を隠すルール (example.com##.ad) なので、ドメインとして読まない
        // (読むと、広告を出すサイトそのもの example.com を止めてしまう)
        val tokens = line.replace(TRAILING_COMMENT, "").trim().split(WHITESPACE)
        return when {
            tokens.size >= 2 && tokens[0] in SINK_ADDRESSES -> domainOrNull(tokens[1])?.let { Rule.Block(it) }
            // "*.example.com" (ワイルドカード付きのドメインの形式。example.com とそのサブドメインを止めるのと同じ)
            tokens.size == 1 -> domainOrNull(tokens[0].removePrefix("*."))?.let { Rule.Block(it) }
            else -> null
        }
    }

    /** `ads.example.com^`、`ads.example.com^|`、`ads.example.com^$important` を受け付ける。 */
    private fun adblock(body: String): String? {
        val caret = body.indexOf('^')
        if (caret <= 0) return null
        val rest = body.substring(caret + 1)
        if (rest.isNotEmpty() && rest != "|" && rest != "\$important") return null
        return domainOrNull(body.substring(0, caret))
    }

    private fun domainOrNull(s: String): String? {
        val d = s.lowercase().trimEnd('.')
        if (d in NOT_DOMAINS || !DOMAIN.matches(d)) return null
        return d
    }

    private val WHITESPACE = Regex("\\s+")
    private val TRAILING_COMMENT = Regex("\\s#.*$")
}
