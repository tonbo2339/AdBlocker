package io.github.tonbo2339.adblocker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import io.github.tonbo2339.adblocker.vpn.BlockingControl
import io.github.tonbo2339.adblocker.vpn.BlockingControl.Outcome

/**
 * ランチャーのショートカット (res/xml/shortcuts.xml) とウィジェットの動作。画面は出さず、結果をトーストで知らせて閉じる。
 * 操作そのものは BlockingControl。
 */
class ShortcutActivity : Activity() {

    companion object {
        internal const val ACTION_TOGGLE = "io.github.tonbo2339.adblocker.shortcut.TOGGLE"
        internal const val ACTION_PAUSE = "io.github.tonbo2339.adblocker.shortcut.PAUSE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val outcome = when (intent?.action) {
            ACTION_TOGGLE -> BlockingControl.toggle(this)
            ACTION_PAUSE -> BlockingControl.togglePause(this)
            else -> null
        }
        when (outcome) {
            null -> {}
            Outcome.NEEDS_APP -> openApp()
            Outcome.TURNED_ON -> toast(R.string.shortcut_turned_on)
            Outcome.TURNED_OFF -> toast(R.string.shortcut_turned_off)
            Outcome.IS_OFF -> toast(R.string.shortcut_is_off)
            Outcome.PAUSED -> toast(R.string.shortcut_paused)
            Outcome.RESUMED -> toast(R.string.shortcut_resumed)
        }
        finish()
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun toast(message: Int) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }
}
