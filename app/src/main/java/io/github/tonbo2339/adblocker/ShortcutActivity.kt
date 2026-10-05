package io.github.tonbo2339.adblocker

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * ランチャーのショートカット (res/xml/shortcuts.xml) の動作。画面は出さず、結果をトーストで知らせて閉じる。
 */
class ShortcutActivity : Activity() {

    companion object {
        private const val ACTION_TOGGLE = "io.github.tonbo2339.adblocker.shortcut.TOGGLE"
        private const val ACTION_PAUSE = "io.github.tonbo2339.adblocker.shortcut.PAUSE"
        private const val PAUSE_MS = 15 * 60_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            ACTION_TOGGLE -> toggle()
            ACTION_PAUSE -> togglePause()
        }
        finish()
    }

    private fun toggle() {
        if (Prefs.isEnabled(this)) {
            AdBlockVpnService.stop(this)
            Prefs.setEnabled(this, false)
            toast(R.string.shortcut_turned_off)
            return
        }
        // VPN の許可がまだなら、許可を求められる画面を開く
        if (VpnService.prepare(this) != null) {
            openApp()
            return
        }
        try {
            AdBlockVpnService.start(this)
            Prefs.setEnabled(this, true)
            toast(R.string.shortcut_turned_on)
        } catch (e: IllegalStateException) {
            Log.w("AdBlockShortcut", "start failed", e)
            openApp()
        }
    }

    private fun togglePause() {
        when {
            !Prefs.isEnabled(this) -> toast(R.string.shortcut_is_off)
            Pause.isPaused() -> {
                Pause.resume(this)
                toast(R.string.shortcut_resumed)
            }
            else -> {
                Pause.start(this, PAUSE_MS)
                toast(R.string.shortcut_paused)
            }
        }
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun toast(message: Int) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }
}
