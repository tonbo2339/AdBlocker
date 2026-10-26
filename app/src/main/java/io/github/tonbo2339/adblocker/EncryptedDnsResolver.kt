package io.github.tonbo2339.adblocker

import android.net.VpnService

/**
 * 暗号化 DNS の問い合わせ口。選んだ方式 (mode) でつながらないときは、同じ事業者のもう一方の方式を試す
 * (例: DNS over TLS の 853 番を塞ぐ回線では、443 番の DNS over HTTPS に切り替わる)。別の事業者には送らない。
 * つながらない方式は EncryptedDnsClient が 30 秒だけ試さなくなるので、毎回のタイムアウトは待たない。
 */
class EncryptedDnsResolver(vpn: VpnService, val server: EncryptedDns, val mode: EncryptedDnsMode) {
    private val primary = EncryptedDnsClient(vpn, server, mode)
    private val alternate = EncryptedDnsClient(vpn, server, mode.other)

    /** 問い合わせて応答を返す。どちらの方式でも答えが無ければ null (呼び出し側は通常の DNS に切り替える)。 */
    fun query(dns: ByteArray): ByteArray? = primary.query(dns) ?: alternate.query(dns)

    fun close() {
        primary.close()
        alternate.close()
    }
}
