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

/** 暗号化 DNS (DNS over TLS) の転送先。アドレスは IP で持ち、名前解決なしで接続する。 */
enum class EncryptedDns(val id: String, val label: String, val host: String, val address: String) {
    CLOUDFLARE("cloudflare", "Cloudflare (1.1.1.1)", "cloudflare-dns.com", "1.1.1.1"),
    GOOGLE("google", "Google (8.8.8.8)", "dns.google", "8.8.8.8"),
    QUAD9("quad9", "Quad9 (9.9.9.9)", "dns.quad9.net", "9.9.9.9");

    companion object {
        fun of(id: String?): EncryptedDns? = entries.firstOrNull { it.id == id }
    }
}

/**
 * DNS over TLS (RFC 7858) のクライアント。問い合わせのたびに TLS の握手をしないよう、接続を使い回す。
 * 1 つの接続では 1 度に 1 つの問い合わせだけを送る (並行する問い合わせは別の接続を使う)。
 * ソケットは VpnService.protect() で VPN の外に出す。
 */
class DotClient(private val vpn: VpnService, val server: EncryptedDns) {

    private companion object {
        const val TAG = "DotClient"
        const val PORT = 853
        const val TIMEOUT_MS = 3000

        /** これより長く使っていない接続は、相手に切られている可能性が高いので使わずに作り直す。 */
        const val MAX_IDLE_MS = 8000L
        const val MAX_IDLE_CONNECTIONS = 4
    }

    private class Connection(val socket: SSLSocket) {
        val input = DataInputStream(socket.inputStream.buffered())
        val output = socket.outputStream
        var lastUsed = System.currentTimeMillis()
    }

    private val idle = ArrayBlockingQueue<Connection>(MAX_IDLE_CONNECTIONS)
    private val open = CopyOnWriteArraySet<Connection>()

    @Volatile
    private var closed = false

    /** 問い合わせて応答を返す。失敗したら null (呼び出し側は通常の DNS に切り替える)。 */
    fun query(dns: ByteArray): ByteArray? {
        // 使い回した接続が切れていたら、新しい接続で 1 回だけやり直す
        repeat(2) { attempt ->
            val conn = (if (attempt == 0) takeIdle() else null) ?: try {
                connect()
            } catch (e: IOException) {
                Log.d(TAG, "connect to ${server.host} failed: $e")
                return null
            }
            try {
                val response = exchange(conn, dns)
                release(conn)
                return response
            } catch (e: IOException) {
                Log.d(TAG, "query over ${server.host} failed: $e")
                close(conn)
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

    private fun connect(): Connection {
        val plain = Socket()
        try {
            vpn.protect(plain)
            plain.connect(InetSocketAddress(InetAddress.getByName(server.address), PORT), TIMEOUT_MS)
            plain.soTimeout = TIMEOUT_MS
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(plain, server.host, PORT, true) as SSLSocket
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
        // TCP と同じく、先頭に 2 バイトの長さを付ける
        val frame = ByteArray(2 + dns.size)
        put16(frame, 0, dns.size)
        System.arraycopy(dns, 0, frame, 2, dns.size)
        conn.output.write(frame)
        conn.output.flush()
        val length = conn.input.readUnsignedShort()
        val response = ByteArray(length)
        conn.input.readFully(response)
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
