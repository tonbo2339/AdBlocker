package io.github.tonbo2339.adblocker

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DohTest {

    private fun read(text: String, body: ByteArray = ByteArray(0)) =
        Doh.readResponse(DataInputStream(ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1) + body)))

    @Test
    fun requestHasHeadersAndBody() {
        val dns = byteArrayOf(1, 2, 3)
        val text = String(Doh.request("dns.example", "/custom", dns), Charsets.ISO_8859_1)
        assertTrue(text.startsWith("POST /custom HTTP/1.1\r\nHost: dns.example\r\n"))
        assertTrue("Content-Length: 3\r\n" in text)
        assertTrue("Content-Type: application/dns-message\r\n" in text)
        assertTrue(text.endsWith("\r\n\r\n" + String(dns, Charsets.ISO_8859_1)))
    }

    @Test
    fun readsBodyByContentLength() {
        val reply = read("HTTP/1.1 200 OK\r\ncontent-type: application/dns-message\r\nContent-Length: 4\r\n\r\n", byteArrayOf(9, 8, 7, 6))
        assertArrayEquals(byteArrayOf(9, 8, 7, 6), reply.body)
        assertTrue(reply.keepAlive)
    }

    @Test
    fun readsChunkedBody() {
        val reply = read("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nab\r\n3;ext=1\r\ncde\r\n0\r\n\r\n")
        assertEquals("abcde", String(reply.body))
    }

    @Test
    fun connectionCloseIsNotReusable() {
        val reply = read("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 0\r\n\r\n")
        assertFalse(reply.keepAlive)
    }

    @Test
    fun rejectsErrorStatusAndMissingOrHugeLength() {
        val bad = listOf(
            "HTTP/1.1 500 Oops\r\nContent-Length: 0\r\n\r\n",
            "HTTP/1.1 200 OK\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 999999\r\n\r\n",
        )
        for (text in bad) {
            try {
                read(text)
                fail("should reject: $text")
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun rejectsTruncatedStream() {
        try {
            read("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n", byteArrayOf(1, 2))
            fail("should reject")
        } catch (_: EOFException) {
        }
    }
}
