package io.github.tonbo2339.adblocker

/** 問い合わせをどう扱ったか。 */
enum class Verdict(val blocked: Boolean) {
    /** ブロックリストに当たった */
    LIST(true),

    /** 手動でブロック (「ブロック・許可するドメイン」) */
    USER_BLOCK(true),

    /** 手動で許可 (「ブロック・許可するドメイン」) */
    USER_ALLOW(false),

    /** 一時停止中なので通した */
    PAUSED(false),

    /** CNAME の行き先がブロック対象 (CNAME 隠し) */
    CNAME(true),

    /** どのルールにも当たらなかった */
    PASS(false),
}

object Filter {
    /** 優先順位: 一時停止 > 手動の許可 > 手動のブロック > ブロックリスト。 */
    fun decide(name: String): Verdict {
        if (Pause.isPaused()) return Verdict.PAUSED
        return when (UserRules.ruleFor(name)) {
            UserRules.Kind.ALLOW -> Verdict.USER_ALLOW
            UserRules.Kind.BLOCK -> Verdict.USER_BLOCK
            null -> if (BlockList.isBlocked(name)) Verdict.LIST else Verdict.PASS
        }
    }

    /**
     * CNAME の行き先のうち、ブロック対象の最初のもの (無ければ null)。
     * 問い合わせた名前そのものが PASS だったときだけ呼ぶ (自分で許可した名前・一時停止中は見ない)。
     */
    fun cnameBlocked(targets: List<String>): String? = targets.firstOrNull { t ->
        when (UserRules.ruleFor(t)) {
            UserRules.Kind.ALLOW -> false
            UserRules.Kind.BLOCK -> true
            null -> BlockList.isBlocked(t)
        }
    }
}
