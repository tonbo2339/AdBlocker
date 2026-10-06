package io.github.tonbo2339.adblocker

import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * デバッグログ用の DNS の件数と失敗の記録 (VPN サービスから呼ぶ。どのスレッドからでもよい)。
 * 件数は summary() でまとめて書いて 0 に戻す。失敗は、回線が切れている間に何千行も書かないよう間引く。
 * デバッグログがオフのときは何もしない。
 */
object DnsDebugStats {
    private const val TAG = "AdBlockVpn"

    /** 転送の失敗をまとめて書く間隔と、転送先ごとの送信の失敗を書く間隔。 */
    private const val FAILURE_LOG_INTERVAL_MS = 10_000L
    private const val SEND_FAILURE_LOG_INTERVAL_MS = 60_000L

    private val queries = AtomicInteger()
    private val blocked = AtomicInteger()
    private val cached = AtomicInteger()
    private val forwarded = AtomicInteger()
    private val dot = AtomicInteger()
    private val failed = AtomicInteger()
    private val latencyTotal = AtomicLong()
    private val latencyMax = AtomicLong()

    private val failuresSinceLog = AtomicInteger()
    @Volatile
    private var failureLoggedAt = 0L
    private val sendFailureLoggedAt = ConcurrentHashMap<InetAddress, Long>()

    fun query(blocked: Boolean) {
        if (!DebugLog.enabled) return
        queries.incrementAndGet()
        if (blocked) this.blocked.incrementAndGet()
    }

    fun cached() {
        if (DebugLog.enabled) cached.incrementAndGet()
    }

    fun forwarded(ms: Long) {
        if (!DebugLog.enabled) return
        forwarded.incrementAndGet()
        latencyTotal.addAndGet(ms)
        latencyMax.accumulateAndGet(ms, ::maxOf)
    }

    fun answeredOverDot() {
        if (DebugLog.enabled) dot.incrementAndGet()
    }

    /** どの転送先も答えなかった。続いたときは FAILURE_LOG_INTERVAL_MS に 1 行にまとめる。 */
    fun failed(servers: () -> List<InetAddress>) {
        if (!DebugLog.enabled) return
        failed.incrementAndGet()
        val count = failuresSinceLog.incrementAndGet()
        val now = System.currentTimeMillis()
        if (now - failureLoggedAt < FAILURE_LOG_INTERVAL_MS) return
        failureLoggedAt = now
        failuresSinceLog.set(0)
        DebugLog.w(TAG, "no upstream answered ($count queries; servers ${servers().map { it.hostAddress }})")
    }

    /** 転送先に送れなかった理由 (届かないアドレス・Android 17 の LAN 制限の EPERM など)。転送先ごとに 1 分に 1 回。 */
    fun sendFailed(server: InetAddress, e: IOException) {
        if (!DebugLog.enabled) return
        val now = System.currentTimeMillis()
        val last = sendFailureLoggedAt[server]
        if (last != null && now - last < SEND_FAILURE_LOG_INTERVAL_MS) return
        sendFailureLoggedAt[server] = now
        DebugLog.w(TAG, "send to ${server.hostAddress} failed", e)
    }

    /** 前回からの件数と応答時間を 1 行書いて 0 に戻す (問い合わせが無ければ書かない)。 */
    fun summary(period: String, servers: List<InetAddress>) {
        if (!DebugLog.enabled) return
        val q = queries.getAndSet(0)
        val f = forwarded.getAndSet(0)
        val total = latencyTotal.getAndSet(0)
        val line = "last $period: $q queries, ${blocked.getAndSet(0)} blocked, ${cached.getAndSet(0)} cached, " +
            "$f forwarded (${dot.getAndSet(0)} over DoT), ${failed.getAndSet(0)} failed; " +
            "upstream ${if (f > 0) total / f else 0} ms avg, ${latencyMax.getAndSet(0)} ms max; " +
            "servers ${servers.map { it.hostAddress }}; unblocked Wi-Fi: ${WifiNetworks.isUnblocked()}"
        if (q > 0) DebugLog.i(TAG, line)
    }
}
