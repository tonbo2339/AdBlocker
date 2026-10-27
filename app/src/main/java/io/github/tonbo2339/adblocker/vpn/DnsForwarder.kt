package io.github.tonbo2339.adblocker.vpn

import android.net.VpnService
import android.util.Log
import io.github.tonbo2339.adblocker.data.Prefs
import io.github.tonbo2339.adblocker.dns.Dns
import io.github.tonbo2339.adblocker.dns.DnsDebugStats
import io.github.tonbo2339.adblocker.dns.EncryptedDnsResolver
import io.github.tonbo2339.adblocker.dns.TcpDns
import io.github.tonbo2339.adblocker.dns.UpstreamDns
import io.github.tonbo2339.adblocker.log.DebugLog
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * 本物の DNS サーバーへの転送。暗号化 DNS (オンのとき) → 通常の DNS (UDP) → 答えが切り詰められていたら TCP、の順に試す。
 * 送るソケットは protect() で VPN を通らないようにする (自分の tun に戻って、また転送するのを防ぐ)。
 * VPN の状態は知らない。呼ぶのは [QueryHandler] だけ。
 */
internal class DnsForwarder(private val vpn: VpnService, private val upstream: UpstreamDns) {

    private companion object {
        /** 通常の DNS で答えを待つ時間 (すべての転送先を合わせて)。 */
        const val UPSTREAM_TIMEOUT_MS = 3000

        /** 答えが無ければ次の転送先にも送るまでの時間と、同時に送る台数の上限。 */
        const val STAGGER_MS = 400L
        const val MAX_PARALLEL = 3
    }

    /** 暗号化 DNS のクライアント。設定が変わったら作り直す (VPN は作り直さない)。 */
    @Volatile
    private var encrypted: EncryptedDnsResolver? = null
    private val encryptedLock = Any()

    /** [close] の後か (encryptedLock の中で読み書きする)。 */
    private var destroyed = false

    /** サービスの終了時に呼ぶ。以後は暗号化 DNS のクライアントを作らない。 */
    fun close() {
        synchronized(encryptedLock) {
            destroyed = true
            encrypted?.close()
            encrypted = null
        }
    }

    /** 暗号化 DNS の使い回し接続を閉じる (次の問い合わせで作り直す)。回線が替わったときに呼ぶ。 */
    fun resetEncrypted() {
        synchronized(encryptedLock) {
            encrypted?.close()
            encrypted = null
        }
    }

    /** 設定に合った暗号化 DNS のクライアント (オフなら null)。 */
    private fun encryptedClient(): EncryptedDnsResolver? {
        val server = Prefs.encryptedDns(vpn)
        val mode = Prefs.encryptedDnsMode(vpn)
        val current = encrypted
        if (current?.server == server && (current == null || current.mode == mode)) return current
        // VPN の作り直しと同じロック (サービス自身) を使うと、tun を作っている間の問い合わせが待たされる
        synchronized(encryptedLock) {
            // 終了後に残った転送が新しいクライアントを作ると、その接続は誰も閉じない
            if (destroyed) return null
            val again = encrypted
            if (again?.server == server && (again == null || again.mode == mode)) return again
            again?.close()
            return server?.let { EncryptedDnsResolver(vpn, it, mode) }.also { encrypted = it }
        }
    }

    /**
     * 本物の DNS サーバーに問い合わせる。暗号化 DNS がオンならそれを使い、失敗したときだけ通常の DNS にする。
     * 全部だめなら null。
     */
    fun forward(dns: ByteArray): ByteArray? {
        // tun に書けるのは MTU まで。DoT (TCP) の応答はそれより大きいことがある。さらに、問い合わせた側が受け取れる UDP の大きさ
        // (EDNS が無ければ 512) を超える答えは、切り捨てられて名前解決に失敗しうる。どちらもそのときは UDP で取り直す
        // (UDP の転送先は、問い合わせの EDNS の大きさに合わせて答えるので、大きすぎるときは切り詰め (TC) の答えが返る)
        val client = encryptedClient()
        if (client != null) {
            val limit = minOf(MAX_RESPONSE, Dns.maxUdpSize(dns))
            client.query(dns)?.takeIf { it.size <= limit }?.let {
                DnsDebugStats.answeredOverEncrypted()
                return it
            }
        }
        val udp = forwardUdp(dns) ?: return null
        if (!Dns.isTruncated(udp.response)) return udp.response
        // 切り詰められた (TC) 答えは、アプリが tun 越しに TCP で取り直せない。同じ転送先から TCP で完全な答えを取り、
        // 問い合わせた側が受け取れる大きさに収まるときだけ使う (収まらなければ切り詰めた答えのまま返す)
        val limit = minOf(MAX_RESPONSE, Dns.maxUdpSize(dns))
        return TcpDns.query(vpn::protect, udp.server, dns, limit)?.also { DnsDebugStats.retriedOverTcp() } ?: udp.response
    }

    /** UDP で届いた答えと、答えた転送先。 */
    private class UdpAnswer(val response: ByteArray, val server: InetAddress)

    /**
     * 通常の DNS (UDP) で問い合わせる。1 台目に送り、STAGGER_MS 待っても答えが無ければ 2 台目にも送る、というふうに
     * 最大 MAX_PARALLEL 台まで重ねて送り、最初に届いた答えを使う。応答しないサーバーがあっても、そのタイムアウトを待たない。
     */
    private fun forwardUdp(dns: ByteArray): UdpAnswer? {
        // 回線の DNS が多くても、最後の予備 (公開 DNS) を 1 台は残す
        val all = upstream.servers()
        val servers = if (all.size > MAX_PARALLEL) all.take(MAX_PARALLEL - 1) + all.last() else all
        if (servers.isEmpty() || dns.size < 2) return null
        try {
            DatagramSocket().use { socket ->
                // 保護できないと、送った問い合わせが自分の tun に戻って、また転送してしまう (公開 DNS も捕まえる設定のとき)
                if (!vpn.protect(socket)) {
                    DebugLog.w(VPN_TAG, "protect(socket) failed: not forwarding")
                    return null
                }
                val sent = HashSet<InetAddress>()
                val rb = ByteArray(MAX_RESPONSE)
                val rp = DatagramPacket(rb, rb.size)
                val deadline = System.currentTimeMillis() + UPSTREAM_TIMEOUT_MS
                var next = 0
                while (true) {
                    if (next < servers.size) {
                        val server = servers[next++]
                        try {
                            socket.send(DatagramPacket(dns, dns.size, server, 53))
                            sent += server
                        } catch (e: IOException) {
                            // その回線では届かないアドレス (IPv6 が無い等)。待たずに次へ
                            Log.d(VPN_TAG, "send to $server failed: $e")
                            DnsDebugStats.sendFailed(server, e)
                            continue
                        }
                    }
                    if (sent.isEmpty()) return null // どこにも送れなかった
                    val waitUntil = if (next < servers.size) minOf(System.currentTimeMillis() + STAGGER_MS, deadline) else deadline
                    while (true) {
                        val remaining = waitUntil - System.currentTimeMillis()
                        if (remaining <= 0) break
                        socket.soTimeout = remaining.toInt()
                        // 前の受信で長さが縮んでいるので、毎回バッファ全体に戻す
                        rp.setLength(rb.size)
                        try {
                            socket.receive(rp)
                        } catch (_: SocketTimeoutException) {
                            break
                        }
                        // 送った相手からの、同じ ID の答えだけを受け取る (ほかからのパケットは無視)
                        if (rp.address in sent && rp.length >= 12 && rb[0] == dns[0] && rb[1] == dns[1]) {
                            upstream.markWorking(rp.address)
                            return UdpAnswer(rb.copyOf(rp.length), rp.address)
                        }
                    }
                    if (System.currentTimeMillis() >= deadline) return null
                }
            }
        } catch (e: IOException) {
            Log.d(VPN_TAG, "upstream query failed: $e")
            return null
        }
    }
}
