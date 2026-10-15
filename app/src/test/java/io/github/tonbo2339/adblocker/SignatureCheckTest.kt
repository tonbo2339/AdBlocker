package io.github.tonbo2339.adblocker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** アプリの自動更新で、入れ替えてよい署名かの判定。 */
class SignatureCheckTest {

    @Test
    fun sameKeyIsAccepted() {
        assertTrue(AppUpdater.signaturesAccepted(setOf("A"), setOf("A")))
    }

    @Test
    fun differentKeyIsRejected() {
        assertFalse(AppUpdater.signaturesAccepted(setOf("A"), setOf("B")))
    }

    @Test
    fun rotatedKeyIsAcceptedWhenHistoryContainsTheInstalledKey() {
        // 鍵を切り替えた APK は、署名の履歴に今の鍵 (A) が入っていれば受け入れられる
        assertTrue(AppUpdater.signaturesAccepted(setOf("A"), setOf("A", "B")))
    }

    @Test
    fun unreadableInstalledKeyIsRejected() {
        // 今の鍵が読めない (空) のに通すと、検証が働かない
        assertFalse(AppUpdater.signaturesAccepted(emptySet(), setOf("A")))
        assertFalse(AppUpdater.signaturesAccepted(emptySet(), emptySet()))
    }

    @Test
    fun allInstalledKeysMustBePresent() {
        assertFalse(AppUpdater.signaturesAccepted(setOf("A", "B"), setOf("A")))
        assertTrue(AppUpdater.signaturesAccepted(setOf("A", "B"), setOf("B", "A", "C")))
    }
}
