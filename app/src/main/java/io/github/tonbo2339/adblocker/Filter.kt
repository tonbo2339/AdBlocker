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

    /** 広告ブロックをしない Wi-Fi につながっているので通した */
    NETWORK(false),

    /** CNAME の行き先がブロック対象 (CNAME 隠し) */
    CNAME(true),

    /** 答えの IP アドレスが、手動でブロックした IP に当たった */
    IP(true),

    /** どのルールにも当たらなかった */
    PASS(false),
}

object Filter {
    /** 優先順位: 一時停止 > 広告ブロックをしない Wi-Fi > 手動の許可 > 手動のブロック > ブロックリスト。 */
    fun decide(name: String): Verdict {
        if (Pause.isPaused()) return Verdict.PAUSED
        if (WifiNetworks.isUnblocked()) return Verdict.NETWORK
        return when (UserRules.ruleFor(name)) {
            UserRules.Kind.ALLOW -> Verdict.USER_ALLOW
            UserRules.Kind.BLOCK -> Verdict.USER_BLOCK
            null -> if (BlockList.isBlocked(name)) Verdict.LIST else Verdict.PASS
        }
    }

    /** 転送先の答えを見てブロックした理由 (verdict) と、当たったもの (CNAME の行き先や IP のルール)。 */
    class ResponseBlock(val verdict: Verdict, val via: String)

    /**
     * 答えの中身でブロックするか。問い合わせた名前そのものが PASS だったときだけ呼ぶ (許可した名前・一時停止中は見ない)。
     * - CNAME の行き先がブロック対象 (自社のサブドメインに見せかけたトラッカー)
     * - 答えの IP アドレスが、手動でブロックした IP に当たる
     */
    fun checkResponse(response: ByteArray): ResponseBlock? {
        cnameBlocked(Dns.cnameTargets(response))?.let { return ResponseBlock(Verdict.CNAME, it) }
        UserRules.blockedAddress(Dns.answerAddresses(response))?.let { return ResponseBlock(Verdict.IP, it) }
        return null
    }

    /** CNAME の行き先のうち、ブロック対象の最初のもの (無ければ null)。 */
    fun cnameBlocked(targets: List<String>): String? = targets.firstOrNull { t ->
        when (UserRules.ruleFor(t)) {
            UserRules.Kind.ALLOW -> false
            UserRules.Kind.BLOCK -> true
            null -> BlockList.isBlocked(t)
        }
    }
}
