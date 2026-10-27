package io.github.tonbo2339.adblocker.dns

import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TcpDnsTest {

    private val query = ByteArray(20) { it.toByte() }

    /** 1 回だけ接続を受けて、reply(受け取った問い合わせ) を長さ付きで返すサーバー。 */
    private fun serve(reply: (ByteArray) -> ByteArray): ServerSocket {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread {
            server.accept().use { s ->
                val input = DataInputStream(s.getInputStream())
                val received = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
                s.getOutputStream().write(Dns.tcpFrame(reply(received)))
            }
        }
        return server
    }

    private fun ask(server: ServerSocket, maxSize: Int = 4096, protect: Boolean = true) =
        TcpDns.query({ protect }, InetAddress.getLoopbackAddress(), query, maxSize, server.localPort)

    private fun answer(size: Int, id0: Byte = query[0]) = ByteArray(size) { (it + 5).toByte() }.also { it[0] = id0; it[1] = query[1] }

    @Test
    fun returnsAnswerWithSameId() {
        val answer = answer(100)
        serve { answer }.use { assertArrayEquals(answer, ask(it)) }
    }

    @Test
    fun rejectsAnswerWithOtherId() {
        serve { answer(100, id0 = (query[0] + 1).toByte()) }.use { assertNull(ask(it)) }
    }

    @Test
    fun rejectsAnswerLargerThanLimit() {
        serve { answer(100) }.use { assertNull(ask(it, maxSize = 99)) }
    }

    @Test
    fun doesNotConnectWhenProtectFails() {
        serve { it }.use { assertNull(ask(it, protect = false)) }
    }
}
