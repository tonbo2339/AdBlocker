package io.github.tonbo2339.adblocker

/**
 * 自分で指定する暗号化 DNS のサーバーの入力を読む。
 * サーバーは名前か URL (`https://dns.example/dns-query`、`tls://dns.example`)、IP アドレスは接続する先。
 * 名前だけでは接続できない (VPN の中で名前を引くと、自分の問い合わせに戻る) ので、名前を入れたときは IP アドレスも要る。
 */
object CustomDns {
    /** ホスト名の 1 つの部分 (ラベル)。 */
    private val LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")

    /** DNS over HTTPS の道筋に使える文字だけ (ヘッダーに入るので、空白や改行は入れない)。 */
    private val PATH = Regex("/[A-Za-z0-9._~!$&'()*+,;=:@%/-]*")

    /** 入力から転送先を作る。正しくなければ null。 */
    fun parse(serverText: String, addressText: String): EncryptedDns? {
        var rest = serverText.trim()
        for (scheme in listOf("https://", "tls://", "dns://")) {
            if (rest.startsWith(scheme, ignoreCase = true)) {
                rest = rest.substring(scheme.length)
                break
            }
        }
        val slash = rest.indexOf('/')
        val host = (if (slash < 0) rest else rest.substring(0, slash)).lowercase()
        val path = if (slash < 0) EncryptedDns.DEFAULT_PATH else rest.substring(slash)
        if (!validHost(host) || !PATH.matches(path) || path.length > 200) return null

        val address = addressText.trim().ifEmpty { if (IpRule.isLiteral(host)) host else return null }
        val bytes = IpRule.literal(address) ?: return null
        val normalized = java.net.InetAddress.getByAddress(bytes).hostAddress ?: return null
        return EncryptedDns(EncryptedDns.CUSTOM_ID, host, host, listOf(normalized), path)
    }

    /** ホスト名 (英数字とハイフンの部分をドットでつないだもの) か、数字の IP アドレス。 */
    private fun validHost(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        if (IpRule.isLiteral(host)) return true
        return host.split('.').all { LABEL.matches(it) }
    }
}
