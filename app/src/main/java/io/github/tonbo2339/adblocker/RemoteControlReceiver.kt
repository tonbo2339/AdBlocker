package io.github.tonbo2339.adblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 外部のアプリ (Tasker・MacroDroid など) からの指示を受ける。ブロードキャストで、オン / オフと一時停止ができる。
 * 端末のどのアプリからでも送れてしまうので、設定の「外部アプリからの操作」をオンにしたときだけ受け付ける (既定はオフ)。
 */
class RemoteControlReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "RemoteControl"
        const val ACTION_START = "io.github.tonbo2339.adblocker.action.START"
        const val ACTION_STOP = "io.github.tonbo2339.adblocker.action.STOP"
        const val ACTION_PAUSE = "io.github.tonbo2339.adblocker.action.PAUSE"
        const val ACTION_RESUME = "io.github.tonbo2339.adblocker.action.RESUME"

        /** ACTION_PAUSE に付ける、一時停止の長さ (分)。 */
        const val EXTRA_MINUTES = "minutes"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        // 受け付けない設定のときは、何もしない (誰から来たかは分からないので、通知も出さない)
        if (!Prefs.externalControl(context)) {
            DebugLog.i(TAG, "ignored $action (external control is off)")
            return
        }
        val outcome = when (action) {
            ACTION_START -> BlockingControl.turnOn(context)
            ACTION_STOP -> BlockingControl.turnOff(context)
            ACTION_PAUSE -> BlockingControl.pause(context, intent.getIntExtra(EXTRA_MINUTES, BlockingControl.DEFAULT_PAUSE_MINUTES))
            ACTION_RESUME -> BlockingControl.resume(context)
            else -> return
        }
        DebugLog.i(TAG, "$action -> $outcome")
        // 開始できなかったときは、タップでアプリを開ける通知で知らせる (そこから許可・開始ができる)
        if (outcome == BlockingControl.Outcome.NEEDS_APP) Notifications.stopped(context)
    }
}
