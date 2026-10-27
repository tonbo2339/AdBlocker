package io.github.tonbo2339.adblocker.block

/**
 * 自分のルールのテキスト形式 (書き出しと読み込み)。1 行に 1 つ。
 *
 * - `example.com`、`ads.*`、`203.0.113.0/24`: ブロック
 * - `@@example.com`: 許可
 * - `# …` と `! …` の行: コメント
 * - hosts 形式 (`0.0.0.0 example.com`): ブロック (他のアプリや PC の hosts ファイルをそのまま読める)
 */
object RuleText {
    /** 読み込めるルールの数の上限 (ルールは設定に保存するので、巨大なファイルで膨らませない。リストは「ブロックリスト」に追加する)。 */
    const val MAX_RULES = 5000

    /** 読み込んだ結果。skipped は読めなかった行の数 (空の行・コメント・localhost などは数えない)、tooMany は上限を超えて読まなかったか。 */
    class Parsed(val block: Set<String>, val allow: Set<String>, val skipped: Int, val tooMany: Boolean)

    fun format(block: Collection<String>, allow: Collection<String>): String = buildString {
        append("# AdBlocker rules\n")
        append("# Block: one rule per line. Allow: start the line with @@\n")
        for (rule in block.sorted()) append(rule).append('\n')
        for (rule in allow.sorted()) append("@@").append(rule).append('\n')
    }

    fun parse(text: String): Parsed {
        val block = LinkedHashSet<String>()
        val allow = LinkedHashSet<String>()
        var skipped = 0
        var tooMany = false
        for (raw in text.lineSequence()) {
            // 行の途中の "# コメント" は捨てる (hosts ファイルでよく付いている)
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty() || line.startsWith("!")) continue
            if (block.size + allow.size >= MAX_RULES) {
                tooMany = true
                break
            }
            val rule = readLine(line)
            when {
                rule == null -> skipped++
                rule === IGNORED -> {}
                rule.allow -> allow += rule.rule
                else -> block += rule.rule
            }
        }
        // 同じルールが両方にあれば、許可が勝つ (ルールの優先順位と同じ)
        block.removeAll(allow)
        return Parsed(block, allow, skipped, tooMany)
    }

    private class Line(val rule: String, val allow: Boolean)

    /** 読めたが、ルールにしない行 (hosts に最初から書いてある localhost など)。読めなかった行には数えない。 */
    private val IGNORED = Line("", allow = false)

    private fun readLine(line: String): Line? {
        if (line.startsWith("@@")) {
            return UserRules.normalize(line.substring(2), allowIp = false)?.let { Line(it, allow = true) }
        }
        val tokens = line.split(Regex("\\s+"))
        // hosts 形式: 先頭が IP アドレスで、あとにドメインが続く。ドメインだけを取る (IP は転送先の指定で、ブロックするものではない)
        if (tokens.size >= 2 && IpRule.isLiteral(tokens[0])) {
            // localhost などは、ドットが無くてドメインとして読めないので、先に見分ける
            if (tokens[1].lowercase() in IGNORED_HOSTS) return IGNORED
            val name = UserRules.normalize(tokens[1], allowIp = false) ?: return null
            return Line(name, allow = false)
        }
        if (tokens.size != 1) return null
        val rule = UserRules.normalize(tokens[0]) ?: return null
        return if (rule in UNSPECIFIED) IGNORED else Line(rule, allow = false)
    }

    /** hosts ファイルに最初から書いてある、ブロックするものではない名前。 */
    private val IGNORED_HOSTS = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback")

    /** 何にでも当たってしまう / 自分自身を指す IP アドレス (hosts の行の「ブロック先」として使われるので、ルールにしない)。 */
    private val UNSPECIFIED = setOf("0.0.0.0", "127.0.0.1", "::", "::1", "0:0:0:0:0:0:0:0", "0:0:0:0:0:0:0:1")
}
