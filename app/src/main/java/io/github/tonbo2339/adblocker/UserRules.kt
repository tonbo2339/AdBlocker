package io.github.tonbo2339.adblocker

import android.content.Context
import androidx.core.content.edit
import java.net.InetAddress

/**
 * 利用者が手動で追加したルール (「ブロック・許可するドメイン」)。3 つの書き方がある。
 *
 * - ドメイン (`example.com`): そのドメインとサブドメインに効く
 * - ワイルドカード (`ads.*`、`*tracker*`): `*` は何文字でも (ドットも含む) に当たり、名前全体と比べる
 * - IP アドレス (`203.0.113.7`、`203.0.113.0/24`、IPv6 も可。ブロックだけ): 答えの IP がそれに当たる名前を止める
 *
 * 優先順位は 許可 > ブロック > ブロックリスト。件数は多くないので文字列の集合で持ち、
 * 変更はすぐに (VPN を作り直さずに) 反映される。
 */
object UserRules {
    enum class Kind(val key: String) { BLOCK("blocked"), ALLOW("allowed") }

    private const val NAME = "user_rules"
    private const val MAX_LENGTH = 253

    /** 保存しているルール (書いたとおり) と、照合用に分けたもの。 */
    private class Rules(val all: Set<String>) {
        val domains: Set<String> = all.filterTo(HashSet()) { '*' !in it && IpRule.parse(it) == null }
        val wildcards: List<String> = all.filter { '*' in it }.sorted()
        val ips: List<Pair<String, IpRule>> = all.mapNotNull { r -> IpRule.parse(r)?.let { r to it } }.sortedBy { it.first }
    }

    @Volatile
    private var blocked = Rules(emptySet())

    @Volatile
    private var allowed = Rules(emptySet())

    fun load(context: Context) {
        val prefs = prefs(context)
        blocked = Rules(prefs.getStringSet(Kind.BLOCK.key, null)?.toSet().orEmpty())
        allowed = Rules(prefs.getStringSet(Kind.ALLOW.key, null)?.toSet().orEmpty())
    }

    fun list(kind: Kind): List<String> = rulesOf(kind).all.sorted()

    private fun rulesOf(kind: Kind) = if (kind == Kind.BLOCK) blocked else allowed

    val size: Int get() = blocked.all.size + allowed.all.size

    /** 追加する。同じルールがもう一方の種類にあれば移す。 */
    @Synchronized
    fun add(context: Context, kind: Kind, rule: String) {
        val other = if (kind == Kind.BLOCK) Kind.ALLOW else Kind.BLOCK
        save(context, kind, rulesOf(kind).all + rule)
        if (rule in rulesOf(other).all) save(context, other, rulesOf(other).all - rule)
    }

    /** まとめて置き換える (設定の読み込み用)。 */
    @Synchronized
    fun replace(context: Context, kind: Kind, rules: Set<String>) {
        save(context, kind, rules)
    }

    @Synchronized
    fun remove(context: Context, kind: Kind, rule: String) {
        save(context, kind, rulesOf(kind).all - rule)
    }

    private fun save(context: Context, kind: Kind, set: Set<String>) {
        val rules = Rules(set)
        if (kind == Kind.BLOCK) blocked = rules else allowed = rules
        prefs(context).edit { putStringSet(kind.key, HashSet(set)) }
    }

    /** name に効くルール (無ければ null)。許可を先に見る。 */
    fun ruleFor(name: String): Kind? = when {
        matches(allowed, name) -> Kind.ALLOW
        matches(blocked, name) -> Kind.BLOCK
        else -> null
    }

    /** name に効いているルールそのもの (ログから外すとき用)。ドメインのルールを先に見る。 */
    fun ruleOwner(name: String): Pair<Kind, String>? {
        for (kind in listOf(Kind.ALLOW, Kind.BLOCK)) {
            val rules = rulesOf(kind)
            selfAndParents(name).firstOrNull { it in rules.domains }?.let { return kind to it }
            rules.wildcards.firstOrNull { wildcardMatches(it, name) }?.let { return kind to it }
        }
        return null
    }

    /** 答えの IP アドレスのうち、ブロックするルールに当たるもの (当たったルールを返す。無ければ null)。 */
    fun blockedAddress(addresses: List<ByteArray>): String? {
        val ips = blocked.ips
        if (ips.isEmpty()) return null
        for (a in addresses) ips.firstOrNull { it.second.contains(a) }?.let { return it.first }
        return null
    }

    private fun matches(rules: Rules, name: String): Boolean =
        rules.domains.isNotEmpty() && selfAndParents(name).any { it in rules.domains } ||
            rules.wildcards.any { wildcardMatches(it, name) }

    /** ads.example.com → ads.example.com, example.com, com */
    private fun selfAndParents(name: String): Sequence<String> =
        generateSequence(name.trimEnd('.')) { n -> n.indexOf('.').takeIf { it >= 0 }?.let { n.substring(it + 1) } }
            .filter { it.isNotEmpty() }

    /**
     * `*` は何文字でも (ドットも含む) に当たる。名前全体と比べる。
     * 正規表現にすると `*` の多いパターンで照合が極端に遅くなることがある (問い合わせのたびに VPN のスレッドで動く) ので、
     * `*` の位置を覚えて戻るだけの方法で、長さの積に比例する時間で必ず終わるようにする。
     */
    internal fun wildcardMatches(pattern: String, name: String): Boolean {
        var p = 0
        var n = 0
        var star = -1 // 最後に見た * の位置
        var resume = 0 // その * で読み飛ばし始めた名前の位置
        while (n < name.length) {
            when {
                p < pattern.length && pattern[p] == name[n] -> {
                    p++
                    n++
                }
                p < pattern.length && pattern[p] == '*' -> {
                    star = p++
                    resume = n
                }
                star >= 0 -> {
                    // 直前の * にもう 1 文字飲み込ませてやり直す
                    p = star + 1
                    n = ++resume
                }
                else -> return false
            }
        }
        while (p < pattern.length && pattern[p] == '*') p++
        return p == pattern.length
    }

    /**
     * 入力をルールに整える。URL や Adblock 形式 (`||example.com^`)、`*.example.com` (= example.com)、
     * ワイルドカード、IP アドレス (allowIp のときだけ) を受け付ける。正しくなければ null。
     */
    fun normalize(input: String, allowIp: Boolean = true): String? {
        val trimmed = input.trim().lowercase()
        // IP アドレス (範囲付きも)。範囲の書き方が違うものを、URL のパスとして読み飛ばさないよう先に見る
        val bare = trimmed.removePrefix("[").removeSuffix("]")
        if (IpRule.isLiteral(bare.substringBefore('/'))) return if (allowIp) IpRule.normalize(bare) else null
        var s = trimmed.removePrefix("@@").removePrefix("||").removeSuffix("^")
        s.indexOf("://").takeIf { it >= 0 }?.let { s = s.substring(it + 3) }
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('@').substringBefore(':')
        s = s.trim('.')
        // URL の中の IP アドレス (https://203.0.113.7/ など)
        IpRule.normalize(s)?.let { return if (allowIp) it else null }
        // "*.example.com" は今までどおり example.com (サブドメインも含む)
        val withoutPrefix = s.removePrefix("*.")
        if ('*' !in withoutPrefix) s = withoutPrefix
        if (s.isEmpty() || s.length > MAX_LENGTH) return null
        val wildcard = '*' in s
        // ワイルドカードはドットが無くてもよい (`*tracker*`)。ただし * とドットだけのものは何にでも当たるので受け付けない
        if (wildcard && s.none { it in 'a'..'z' || it in '0'..'9' }) return null
        if (!wildcard && '.' !in s) return null
        val labels = s.split('.')
        // 最後のラベル (トップレベルドメイン) が数字だけなのは、ドメインではなく壊れた IP アドレス (256.1.1.1 など)
        if (!wildcard && labels.last().all { it in '0'..'9' }) return null
        val ok = labels.all { label ->
            label.length in 1..63 &&
                label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' || wildcard && it == '*' } &&
                !label.startsWith('-') && !label.endsWith('-')
        }
        return if (ok) s else null
    }

    private fun prefs(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}

/** IP アドレスのルール (1 つのアドレス、または `/` 付きの範囲)。 */
class IpRule private constructor(private val network: ByteArray, private val prefix: Int) {

    fun contains(address: ByteArray): Boolean {
        if (address.size != network.size) return false
        val full = prefix / 8
        for (i in 0 until full) if (address[i] != network[i]) return false
        val rest = prefix % 8
        if (rest == 0) return true
        val mask = (0xFF shl (8 - rest)) and 0xFF
        return (address[full].toInt() and mask) == (network[full].toInt() and mask)
    }

    companion object {
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
        private val IPV6 = Regex("""[0-9a-f:]*:[0-9a-f:.]*""")

        /** 保存している形 (normalize の出力) を読む。IP のルールでなければ null。 */
        fun parse(rule: String): IpRule? {
            val address = rule.substringBefore('/')
            val bytes = literal(address) ?: return null
            val bits = bytes.size * 8
            val prefix = if ('/' in rule) rule.substringAfter('/').toIntOrNull() ?: return null else bits
            if (prefix !in 1..bits) return null
            return IpRule(bytes, prefix)
        }

        /** 入力を IP のルールに整える (同じアドレスが同じ文字列になるように)。IP のルールでなければ null。 */
        fun normalize(input: String): String? {
            val rule = parse(input) ?: return null
            val address = InetAddress.getByAddress(literal(input.substringBefore('/'))).hostAddress ?: return null
            val bits = rule.network.size * 8
            return if (rule.prefix == bits) address else "$address/${rule.prefix}"
        }

        /** 数字の IP アドレスか (範囲の部分は含めない)。 */
        fun isLiteral(s: String): Boolean = literal(s) != null

        /** 数字の IP アドレスだけを読む (名前は解決しない)。 */
        private fun literal(s: String): ByteArray? {
            if (!IPV4.matches(s) && !IPV6.matches(s)) return null
            if (IPV4.matches(s) && s.split('.').any { it.toInt() > 255 }) return null
            return try {
                // 数字のアドレスだけを渡しているので、名前解決 (ネットワーク) は起きない
                InetAddress.getByName(s).address
            } catch (_: Exception) {
                null
            }
        }
    }
}
