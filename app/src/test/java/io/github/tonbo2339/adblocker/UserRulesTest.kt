package io.github.tonbo2339.adblocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserRulesTest {

    @Test
    fun normalizeAcceptsCommonForms() {
        assertEquals("example.com", UserRules.normalize("example.com"))
        assertEquals("example.com", UserRules.normalize("  Example.COM. "))
        assertEquals("ads.example.com", UserRules.normalize("https://ads.example.com/path?q=1#x"))
        assertEquals("ads.example.com", UserRules.normalize("http://user@ads.example.com:8080/"))
        assertEquals("example.com", UserRules.normalize("||example.com^"))
        assertEquals("example.com", UserRules.normalize("@@||example.com^"))
        assertEquals("example.com", UserRules.normalize("*.example.com"))
        assertEquals("_dmarc.example.com", UserRules.normalize("_dmarc.example.com"))
    }

    @Test
    fun normalizeRejectsInvalid() {
        assertNull(UserRules.normalize(""))
        assertNull(UserRules.normalize("localhost"))
        assertNull(UserRules.normalize("bad..example.com"))
        assertNull(UserRules.normalize("-bad.example.com"))
        assertNull(UserRules.normalize("bad-.example.com"))
        assertNull(UserRules.normalize("exa mple.com"))
        assertNull(UserRules.normalize("a".repeat(64) + ".com"))
        assertNull(UserRules.normalize("a.".repeat(127) + "com")) // 253 文字を超える
    }
}
