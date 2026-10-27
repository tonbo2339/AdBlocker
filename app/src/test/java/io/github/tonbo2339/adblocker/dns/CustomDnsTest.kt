package io.github.tonbo2339.adblocker.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CustomDnsTest {

    @Test
    fun readsUrlWithPathAndAddress() {
        val dns = CustomDns.parse("https://DNS.Example.com/my-path", "203.0.113.7")!!
        assertEquals("dns.example.com", dns.host)
        assertEquals("/my-path", dns.path)
        assertEquals(listOf("203.0.113.7"), dns.addresses)
        assertEquals(EncryptedDns.CUSTOM_ID, dns.id)
    }

    @Test
    fun plainNameUsesDefaultPath() {
        val dns = CustomDns.parse("dns.example.com", "2001:db8::1")!!
        assertEquals("/dns-query", dns.path)
        assertEquals("2001:db8:0:0:0:0:0:1", dns.addresses.single())
    }

    @Test
    fun nameNeedsAnAddressButAnAddressAloneDoesNot() {
        assertNull(CustomDns.parse("dns.example.com", ""))
        assertEquals(listOf("9.9.9.10"), CustomDns.parse("9.9.9.10", "")!!.addresses)
    }

    @Test
    fun rejectsBadHostsAddressesAndPaths() {
        assertNull(CustomDns.parse("", "1.1.1.1"))
        assertNull(CustomDns.parse("bad host.example", "1.1.1.1"))
        assertNull(CustomDns.parse("-bad.example", "1.1.1.1"))
        assertNull(CustomDns.parse("dns.example", "not-an-ip"))
        assertNull(CustomDns.parse("dns.example", "999.1.1.1"))
        // ヘッダーに入るので、空白や改行を含む道筋は受け付けない
        assertNull(CustomDns.parse("https://dns.example/a b", "1.1.1.1"))
        assertNull(CustomDns.parse("https://dns.example/a\r\nX: y", "1.1.1.1"))
    }

    @Test
    fun builtInServersAreFoundById() {
        assertNotNull(EncryptedDns.of("cloudflare"))
        assertNull(EncryptedDns.of(EncryptedDns.CUSTOM_ID))
        assertEquals("Cloudflare (1.1.1.1)", EncryptedDns.CLOUDFLARE.label)
    }
}
