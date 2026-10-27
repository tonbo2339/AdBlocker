package io.github.tonbo2339.adblocker.dns

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException

/**
 * DNS over HTTPS (RFC 8484) の HTTP/1.1 の部分。POST で DNS メッセージをそのまま送り、本文で答えを受け取る。
 * 通信 (TLS の接続) は EncryptedDnsClient が受け持ち、ここは送る文と受け取った文の読み取りだけ (テストしやすいよう分けてある)。
 */
object Doh {
    private const val MEDIA_TYPE = "application/dns-message"

    /** 行や本文の大きさの上限 (壊れた応答や悪意のある応答で、メモリを使い切らないため)。 */
    private const val MAX_LINE = 8192
    private const val MAX_BODY = 65535

    class Reply(val body: ByteArray, val keepAlive: Boolean)

    fun request(host: String, path: String, dns: ByteArray): ByteArray {
        val head = "POST $path HTTP/1.1\r\n" +
            "Host: $host\r\n" +
            "Accept: $MEDIA_TYPE\r\n" +
            "Content-Type: $MEDIA_TYPE\r\n" +
            "Content-Length: ${dns.size}\r\n" +
            "Connection: keep-alive\r\n\r\n"
        return head.toByteArray(Charsets.ISO_8859_1) + dns
    }

    /** 応答 (ステータス行・ヘッダー・本文) を読む。200 以外や読めない形なら IOException。 */
    fun readResponse(input: DataInputStream): Reply {
        val status = readLine(input).split(' ', limit = 3)
        if (status.size < 2 || status[1] != "200") throw IOException("HTTP status ${status.getOrNull(1)}")
        var length = -1
        var chunked = false
        var keepAlive = !status[0].equals("HTTP/1.0", ignoreCase = true)
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val value = line.substring(colon + 1).trim()
            when (line.substring(0, colon).trim().lowercase()) {
                "content-length" -> length = value.toIntOrNull() ?: throw IOException("bad Content-Length")
                "transfer-encoding" -> chunked = value.equals("chunked", ignoreCase = true)
                "connection" -> keepAlive = !value.equals("close", ignoreCase = true)
            }
        }
        val body = when {
            chunked -> readChunked(input)
            length in 0..MAX_BODY -> ByteArray(length).also { input.readFully(it) }
            else -> throw IOException("no usable body length")
        }
        return Reply(body, keepAlive)
    }

    private fun readChunked(input: DataInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            // 「サイズ (16 進)」の行。";" 以降は拡張なので読み飛ばす
            val size = readLine(input).substringBefore(';').trim().toIntOrNull(16) ?: throw IOException("bad chunk size")
            if (size == 0) break
            if (size < 0 || out.size() + size > MAX_BODY) throw IOException("body too large")
            val chunk = ByteArray(size)
            input.readFully(chunk)
            out.write(chunk)
            readLine(input) // 各塊の後ろの空行
        }
        // 最後の塊の後ろにあるトレーラー (あれば) を、空行まで読み捨てる
        while (readLine(input).isNotEmpty()) { /* skip */ }
        return out.toByteArray()
    }

    /** CRLF (または LF) で終わる 1 行 (改行は含まない)。 */
    private fun readLine(input: DataInputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) throw IOException("unexpected end of stream")
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > MAX_LINE) throw IOException("line too long")
        }
        return sb.toString()
    }
}
