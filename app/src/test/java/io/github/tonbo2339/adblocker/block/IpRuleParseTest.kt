package io.github.tonbo2339.adblocker.block

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** IP ルールの読み方。数字でない文字列は、名前解決に回さずに断る。 */
class IpRuleParseTest {

    private fun ip(s: String) = IpRule.literal(s)

    @Test
    fun ipv4() {
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), ip("1.2.3.4"))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), ip("0.0.0.0"))
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1), ip("255.255.255.255"))
    }

    @Test
    fun invalidIpv4() {
        for (s in listOf("1.2.3", "1.2.3.4.5", "256.1.1.1", "1.2.3.", ".1.2.3", "1..2.3", "a.b.c.d", "1.2.3.-4", "01234.1.1.1", "")) {
            assertNull(s, ip(s))
        }
    }

    @Test
    fun ipv6Forms() {
        assertEquals(16, ip("2001:db8::1")!!.size)
        assertArrayEquals(ByteArray(16), ip("::"))
        assertArrayEquals(ByteArray(15) + byteArrayOf(1), ip("::1"))
        assertArrayEquals(byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte()) + ByteArray(12), ip("2001:db8::"))
        assertArrayEquals(ip("2001:0db8:0000:0000:0000:0000:0000:0001"), ip("2001:db8::1"))
        assertArrayEquals(ip("2001:DB8::1"), ip("2001:db8::1")) // 大文字小文字
    }

    @Test
    fun ipv4MappedBecomesIpv4() {
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), ip("::ffff:1.2.3.4"))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), ip("::ffff:102:304"))
    }

    @Test
    fun ipv6WithIpv4Tail() {
        val a = ip("64:ff9b::1.2.3.4")!!
        assertEquals(16, a.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), a.copyOfRange(12, 16))
    }

    @Test
    fun notAddressesAreRejectedWithoutResolving() {
        // "a:b:c" や "c:d" のような、':' を含むだけの文字列。InetAddress.getByName に渡すと名前解決に回りうる
        for (s in listOf("c:d", "a:b:c", "a:b:c:d:e:f:1", "1:2:3:4:5:6:7:8:9", "1::2::3", ":::", "::g", "12345::1", ":1:2:3:4:5:6:7", "1:2:3:4:5:6:7:",
            "example.com:80", "1.2.3.4::", "::1.2.3.4:1", "1:1.2.3.4:1::", "fe80::1%eth0")) {
            assertNull(s, ip(s))
        }
    }

    @Test
    fun doubleColonMustOmitAtLeastOneGroup() {
        assertNull(ip("1:2:3:4:5:6:7::8")) // 8 組すでにある
        assertNotNull(ip("1:2:3:4:5:6:7::")) // 1 組を省く
        assertNotNull(ip("::2:3:4:5:6:7:8"))
    }

    @Test
    fun parseAndContains() {
        val net = IpRule.parse("10.0.0.0/8")!!
        assertTrue(net.contains(byteArrayOf(10, 1, 2, 3)))
        assertFalse(net.contains(byteArrayOf(11, 0, 0, 1)))
        val v6 = IpRule.parse("2001:db8::/32")!!
        assertTrue(v6.contains(ip("2001:db8:1::5")!!))
        assertFalse(v6.contains(ip("2001:db9::1")!!))
        assertNull(IpRule.parse("10.0.0.0/33"))
        assertNull(IpRule.parse("c:d"))
    }

    @Test
    fun normalizeKeepsWorking() {
        assertEquals("1.2.3.4", IpRule.normalize("1.2.3.4"))
        assertEquals("10.0.0.0/8", IpRule.normalize("10.0.0.0/8"))
        assertEquals("1.2.3.4", IpRule.normalize("::ffff:1.2.3.4"))
        assertNull(IpRule.normalize("a:b:c"))
    }
}
