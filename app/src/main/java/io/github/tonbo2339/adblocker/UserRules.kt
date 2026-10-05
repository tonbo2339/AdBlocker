package io.github.tonbo2339.adblocker

import android.content.Context
import androidx.core.content.edit

/**
 * 利用者が自分で追加したルール。ドメインとそのサブドメインに効く。
 *
 * 優先順位は 許可 > ブロック > ブロックリスト。件数は多くないので文字列の集合で持ち、
 * 変更はすぐに (VPN を作り直さずに) 反映される。
 */
object UserRules {
    enum class Kind(val key: String) { BLOCK("blocked"), ALLOW("allowed") }

    private const val NAME = "user_rules"
    private const val MAX_LENGTH = 253

    @Volatile
    private var blocked: Set<String> = emptySet()

    @Volatile
    private var allowed: Set<String> = emptySet()

    fun load(context: Context) {
        val prefs = prefs(context)
        blocked = prefs.getStringSet(Kind.BLOCK.key, null)?.toSet().orEmpty()
        allowed = prefs.getStringSet(Kind.ALLOW.key, null)?.toSet().orEmpty()
    }

    fun list(kind: Kind): List<String> = rulesOf(kind).sorted()

    private fun rulesOf(kind: Kind) = if (kind == Kind.BLOCK) blocked else allowed

    val size: Int get() = blocked.size + allowed.size

    /** 追加する。同じドメインがもう一方の種類にあれば移す。 */
    @Synchronized
    fun add(context: Context, kind: Kind, domain: String) {
        val other = if (kind == Kind.BLOCK) Kind.ALLOW else Kind.BLOCK
        save(context, kind, rulesOf(kind) + domain)
        if (domain in rulesOf(other)) save(context, other, rulesOf(other) - domain)
    }

    @Synchronized
    fun remove(context: Context, kind: Kind, domain: String) {
        save(context, kind, rulesOf(kind) - domain)
    }

    private fun save(context: Context, kind: Kind, set: Set<String>) {
        if (kind == Kind.BLOCK) blocked = set else allowed = set
        prefs(context).edit { putStringSet(kind.key, HashSet(set)) }
    }

    /** name に効くルール (無ければ null)。許可を先に見る。 */
    fun ruleFor(name: String): Kind? = when {
        matchesSelfOrParent(allowed, name) -> Kind.ALLOW
        matchesSelfOrParent(blocked, name) -> Kind.BLOCK
        else -> null
    }

    /** name そのもの、またはその親ドメインに直接付いているルール (ログから外すとき用)。 */
    fun ruleOwner(name: String): Pair<Kind, String>? {
        for (kind in listOf(Kind.ALLOW, Kind.BLOCK)) {
            val set = rulesOf(kind)
            selfAndParents(name).firstOrNull { it in set }?.let { return kind to it }
        }
        return null
    }

    private fun matchesSelfOrParent(set: Set<String>, name: String): Boolean =
        set.isNotEmpty() && selfAndParents(name).any { it in set }

    /** ads.example.com → ads.example.com, example.com, com */
    private fun selfAndParents(name: String): Sequence<String> =
        generateSequence(name.trimEnd('.')) { n -> n.indexOf('.').takeIf { it >= 0 }?.let { n.substring(it + 1) } }
            .filter { it.isNotEmpty() }

    /**
     * 入力をドメインに整える。URL や Adblock 形式 (`||example.com^`)、`*.example.com` も受け付ける。
     * ドメインとして正しくなければ null。
     */
    fun normalize(input: String): String? {
        var s = input.trim().lowercase()
        s = s.removePrefix("@@").removePrefix("||").removeSuffix("^")
        s.indexOf("://").takeIf { it >= 0 }?.let { s = s.substring(it + 3) }
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('@').substringBefore(':')
        s = s.removePrefix("*.").trim('.')
        if (s.isEmpty() || s.length > MAX_LENGTH || '.' !in s) return null
        val labels = s.split('.')
        val ok = labels.all { label ->
            label.length in 1..63 &&
                label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' } &&
                !label.startsWith('-') && !label.endsWith('-')
        }
        return if (ok) s else null
    }

    private fun prefs(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
