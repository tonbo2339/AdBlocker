package io.github.tonbo2339.adblocker

import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 通常の DNS (TCP の 53 番) の 1 回きりの問い合わせ。UDP の答えが切り詰められた (TC) ときに、同じ転送先から完全な答えを取り直すのに使う。
 * ソケットは VpnService.protect() で VPN の外に出す。
 */
object TcpDns {
    private const val TAG = "TcpDns"
    private const val TIMEOUT_MS = 2000

    /** 先頭に 2 バイトの長さを付けた形 (RFC 1035 4.2.2)。 */
    fun frame(dns: ByteArray): ByteArray {
        val frame = ByteArray(2 + dns.size)
        put16(frame, 0, dns.size)
        System.arraycopy(dns, 0, frame, 2, dns.size)
        return frame
    }

    /** server の port (通常は 53 番) へ問い合わせる。protect はソケットを VPN の外に出す処理 (失敗したら送らない)。失敗したら null。maxSize を超える答えも null (呼び出し側が受け取れない大きさ)。 */
    fun query(protect: (Socket) -> Boolean, server: InetAddress, dns: ByteArray, maxSize: Int, port: Int = 53): ByteArray? {
        val socket = Socket()
        try {
            if (!socket.protectWith(protect)) return null
            socket.soTimeout = TIMEOUT_MS
            socket.connect(InetSocketAddress(server, port), TIMEOUT_MS)
            socket.getOutputStream().apply {
                write(frame(dns))
                flush()
            }
            val input = DataInputStream(socket.getInputStream().buffered())
            val length = input.readUnsignedShort()
            if (length < 12 || length > maxSize) return null
            val response = ByteArray(length)
            input.readFully(response)
            // 同じ ID の答えだけ受け取る
            return response.takeIf { it[0] == dns[0] && it[1] == dns[1] }
        } catch (e: IOException) {
            Log.d(TAG, "TCP query to $server failed: $e")
            return null
        } finally {
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }
}

/**
 * ソケットを VPN の外に出す (VpnService.protect)。
 * 接続前の Socket にはまだ OS のソケット (ファイル記述子) が無く、そのまま protect() すると false になるので、先に bind して作らせる。
 */
internal fun Socket.protectWith(protect: (Socket) -> Boolean): Boolean {
    bind(null)
    return protect(this)
}
