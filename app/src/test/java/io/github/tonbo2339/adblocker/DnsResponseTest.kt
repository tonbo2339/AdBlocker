package io.github.tonbo2339.adblocker

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class DnsResponseTest {

    private fun name(vararg labels: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (l in labels) {
            out.write(l.length)
            out.write(l.toByteArray())
        }
        out.write(0)
        return out.toByteArray()
    }

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    private fun record(owner: ByteArray, type: Int, rdata: ByteArray) =
        owner + u16(type) + u16(1) + byteArrayOf(0, 0, 0, 60) + u16(rdata.size) + rdata

    private fun header(questions: Int, answers: Int) =
        u16(0x1234) + u16(0x8180) + u16(questions) + u16(answers) + u16(0) + u16(0)

    /** metrics.example.com → CNAME tracker.example.net (net は圧縮ポインタ) → A */
    @Test
    fun cnameChainWithCompression() {
        val question = name("metrics", "example", "com") + u16(1) + u16(1)
        // 質問の名前は 12 バイト目から。"example.com" は 12 + 8 = 20 バイト目
        val pointerToQuestion = byteArrayOf(0xC0.toByte(), 12)
        val cnameRdata = name("tracker", "example", "net")
        val first = record(pointerToQuestion, 5, cnameRdata)
        // 2 つ目の回答の持ち主は 1 つ目の rdata (CNAME の行き先) を指す
        val rdataOffset = 12 + question.size + 2 + 10
        val second = record(byteArrayOf(0xC0.toByte(), rdataOffset.toByte()), 1, byteArrayOf(1, 2, 3, 4))
        val msg = header(1, 2) + question + first + second
        assertEquals(listOf("tracker.example.net"), Dns.cnameTargets(msg))
    }

    @Test
    fun cnameTargetUsingPointer() {
        val question = name("a", "example", "com") + u16(1) + u16(1)
        // 行き先 "b" + 質問の "example.com" (12 + 2 = 14 バイト目) へのポインタ
        val rdata = byteArrayOf(1, 'b'.code.toByte(), 0xC0.toByte(), 14)
        val msg = header(1, 1) + question + record(byteArrayOf(0xC0.toByte(), 12), 5, rdata)
        assertEquals(listOf("b.example.com"), Dns.cnameTargets(msg))
    }

    @Test
    fun noAnswersOrBrokenInput() {
        val question = name("a", "example", "com") + u16(1) + u16(1)
        assertEquals(emptyList<String>(), Dns.cnameTargets(header(1, 0) + question))
        assertEquals(emptyList<String>(), Dns.cnameTargets(ByteArray(5)))
        // 回答数が実際より多い (途中で切れている)
        assertEquals(emptyList<String>(), Dns.cnameTargets(header(1, 3) + question))
    }

    @Test
    fun pointerLoopIsRejected() {
        val question = name("a", "example", "com") + u16(1) + u16(1)
        val loopAt = 12 + question.size + 2 + 10
        // 自分自身を指すポインタ
        val rdata = byteArrayOf(0xC0.toByte(), loopAt.toByte())
        val msg = header(1, 1) + question + record(byteArrayOf(0xC0.toByte(), 12), 5, rdata)
        assertEquals(emptyList<String>(), Dns.cnameTargets(msg))
    }
}
