package io.github.tonbo2339.adblocker

import android.net.VpnService
import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArraySet
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 暗号化 DNS の転送先。アドレスは IP で持ち、名前解決なしで接続する (VPN の中で名前を引くと、自分の問い合わせに戻ってしまうため)。
 * host は証明書の確認と SNI・Host ヘッダーに使う名前、addresses は接続する先 (先頭から順に試す)、path は DNS over HTTPS の道筋。
 */
data class EncryptedDns(
    val id: String,
    val title: String,
    val host: String,
    val addresses: List<String>,
    val path: String = DEFAULT_PATH,
) {
    /** 選ぶときの表示 (例: "Cloudflare (1.1.1.1)")。 */
    val label: String get() = "$title (${addresses.first()})"

    companion object {
        const val DEFAULT_PATH = "/dns-query"

        /** 自分で指定したサーバーの id (保存している設定は Prefs.customDns)。 */
        const val CUSTOM_ID = "custom"

        // 2 つ目のアドレスは、同じ事業者の予備 (1 つ目に届かない回線のため)
        val CLOUDFLARE = EncryptedDns("cloudflare", "Cloudflare", "cloudflare-dns.com", listOf("1.1.1.1", "1.0.0.1"))
        val GOOGLE = EncryptedDns("google", "Google", "dns.google", listOf("8.8.8.8", "8.8.4.4"))
        val QUAD9 = EncryptedDns("quad9", "Quad9", "dns.quad9.net", listOf("9.9.9.9", "149.112.112.112"))

        val BUILT_IN = listOf(CLOUDFLARE, GOOGLE, QUAD9)

        /** 組み込みのサーバーを id から (自分で指定したものは Prefs.encryptedDns から)。 */
        fun of(id: String?): EncryptedDns? = BUILT_IN.firstOrNull { it.id == id }
    }
}

/** 暗号化 DNS の方式。 */
enum class EncryptedDnsMode(val id: String, val port: Int) {
    /** DNS over TLS (RFC 7858)。853 番を塞いでいる回線では使えない。 */
    TLS("tls", 853),

    /** DNS over HTTPS (RFC 8484)。普通の Web と同じ 443 番なので、853 番が塞がれていても通りやすい。 */
    HTTPS("https", 443);

    /** 選んだ方式がつながらないときに、同じ事業者で試すもう一方の方式。 */
    val other: EncryptedDnsMode get() = if (this == TLS) HTTPS else TLS

    companion object {
        fun of(id: String?): EncryptedDnsMode = entries.firstOrNull { it.id == id } ?: TLS
    }
}

/**
 * 暗号化 DNS (DNS over TLS / DNS over HTTPS) のクライアント。問い合わせのたびに TLS の握手をしないよう、接続を使い回す。
 * 1 つの接続では 1 度に 1 つの問い合わせだけを送る (並行する問い合わせは別の接続を使う)。
 * ソケットは VpnService.protect() で VPN の外に出す。
 */
class EncryptedDnsClient(private val vpn: VpnService, val server: EncryptedDns, val mode: EncryptedDnsMode = EncryptedDnsMode.TLS) {

    private companion object {
        const val TAG = "EncryptedDnsClient"
        const val TIMEOUT_MS = 3000

        /** これより長く使っていない接続は、相手に切られている可能性が高いので使わずに作り直す。 */
        const val MAX_IDLE_MS = 8000L
        const val MAX_IDLE_CONNECTIONS = 4

        /**
         * つながらなかったら、この間は試さずに通常の DNS を使う。
         * 853 番を塞いでいる回線で、問い合わせのたびに接続のタイムアウトを待たないようにする。
         */
        const val RETRY_AFTER_FAILURE_MS = 30_000L
    }

    private class Connection(val socket: SSLSocket) {
        val input = DataInputStream(socket.inputStream.buffered())
        val output = socket.outputStream
        var lastUsed = System.currentTimeMillis()

        /** 相手が「この接続は閉じる」(Connection: close) と答えたら false。 */
        var reusable = true
    }

    private val idle = ArrayBlockingQueue<Connection>(MAX_IDLE_CONNECTIONS)
    private val open = CopyOnWriteArraySet<Connection>()

    @Volatile
    private var closed = false

    @Volatile
    private var failedUntil = 0L

    /** 最後につながったアドレスの位置 (次はここから試す。届かないアドレスのタイムアウトを毎回待たないため)。 */
    @Volatile
    private var preferred = 0

    /** 問い合わせて応答を返す。失敗したら null (呼び出し側は通常の DNS に切り替える)。 */
    fun query(dns: ByteArray): ByteArray? {
        if (System.currentTimeMillis() < failedUntil) return null
        // 使い回した接続が切れていたら、新しい接続で 1 回だけやり直す
        repeat(2) { attempt ->
            val conn = (if (attempt == 0) takeIdle() else null) ?: try {
                connect()
            } catch (e: IOException) {
                DebugLog.w(TAG, "connect to ${server.host} failed; using normal DNS for 30 s", e)
                failedUntil = System.currentTimeMillis() + RETRY_AFTER_FAILURE_MS
                return null
            }
            try {
                val response = exchange(conn, dns)
                if (conn.reusable) release(conn) else close(conn)
                return response
            } catch (e: IOException) {
                Log.d(TAG, "query over ${server.host} failed: $e")
                close(conn)
                // 新しい接続でも答えなければ、しばらく通常の DNS にする (問い合わせのたびにタイムアウトを待たない)
                if (attempt == 1) {
                    DebugLog.w(TAG, "${server.host} did not answer; using normal DNS for 30 s", e)
                    failedUntil = System.currentTimeMillis() + RETRY_AFTER_FAILURE_MS
                }
            }
        }
        return null
    }

    fun close() {
        closed = true
        idle.clear()
        open.forEach { close(it) }
    }

    private fun takeIdle(): Connection? {
        while (true) {
            val conn = idle.poll() ?: return null
            if (System.currentTimeMillis() - conn.lastUsed < MAX_IDLE_MS) return conn
            close(conn)
        }
    }

    private fun release(conn: Connection) {
        conn.lastUsed = System.currentTimeMillis()
        if (closed || !idle.offer(conn)) close(conn)
    }

    /** addresses を、最後につながったものから順に試す。どれにもつながらなければ最後の失敗を投げる。 */
    private fun connect(): Connection {
        val count = server.addresses.size
        var failure: IOException? = null
        for (i in 0 until count) {
            val index = (preferred + i) % count
            try {
                return connect(server.addresses[index]).also { preferred = index }
            } catch (e: IOException) {
                failure = e
            }
        }
        throw failure ?: IOException("no address")
    }

    private fun connect(address: String): Connection {
        val plain = Socket()
        try {
            // 保護できないと、この接続が自分の tun を通ってしまう (送らずに、通常の DNS に切り替える)
            if (!plain.protectWith(vpn::protect)) throw IOException("protect failed")
            plain.connect(InetSocketAddress(InetAddress.getByName(address), mode.port), TIMEOUT_MS)
            plain.soTimeout = TIMEOUT_MS
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(plain, server.host, mode.port, true) as SSLSocket
            ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(SNIHostName(server.host)) }
            ssl.startHandshake()
            // 証明書がこのサーバーの名前のものか確かめる (握手だけでは名前は確かめられない)
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(server.host, ssl.session)) {
                ssl.close()
                throw IOException("certificate does not match ${server.host}")
            }
            return Connection(ssl).also { open += it }
        } catch (e: IOException) {
            plain.close()
            throw e
        }
    }

    private fun exchange(conn: Connection, dns: ByteArray): ByteArray {
        val response = when (mode) {
            EncryptedDnsMode.TLS -> {
                conn.output.write(Dns.tcpFrame(dns))
                conn.output.flush()
                val length = conn.input.readUnsignedShort()
                ByteArray(length).also { conn.input.readFully(it) }
            }
            EncryptedDnsMode.HTTPS -> {
                conn.output.write(Doh.request(server.host, server.path, dns))
                conn.output.flush()
                val reply = Doh.readResponse(conn.input)
                conn.reusable = reply.keepAlive
                reply.body
            }
        }
        // 別の問い合わせへの答えが混ざっていたら、この接続は使わない
        if (response.size < 12 || response[0] != dns[0] || response[1] != dns[1]) throw IOException("unexpected response")
        return response
    }

    private fun close(conn: Connection) {
        open -= conn
        try {
            conn.socket.close()
        } catch (_: IOException) {
        }
    }
}
