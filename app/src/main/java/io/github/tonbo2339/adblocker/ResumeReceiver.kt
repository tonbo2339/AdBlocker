package io.github.tonbo2339.adblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 常駐通知の「再開」ボタン。 */
class ResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Pause.resume(context)
    }
}
