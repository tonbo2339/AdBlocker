package io.github.tonbo2339.adblocker

import android.content.Context
import android.net.VpnService
import android.util.Log

/**
 * 広告ブロックのオン / オフと一時停止の操作。ランチャーのショートカット (ShortcutActivity) とウィジェット、
 * 外部アプリからの指示 (RemoteControlReceiver) が、同じ動作を使う。画面や通知は出さず、結果だけを返す。
 */
object BlockingControl {
    private const val TAG = "BlockingControl"

    /** 一時停止の既定の長さ (分)。 */
    const val DEFAULT_PAUSE_MINUTES = 15

    /** 一時停止の長さの上限 (分)。外部アプリから大きな値を渡されても、1 日を超えて止めたままにしない。 */
    const val MAX_PAUSE_MINUTES = 24 * 60

    enum class Outcome {
        TURNED_ON,
        TURNED_OFF,

        /** VPN の許可がまだ、またはバックグラウンドから開始できなかった。アプリを開いて操作してもらう必要がある。 */
        NEEDS_APP,

        /** 広告ブロックがオフなので、一時停止・再開はできない。 */
        IS_OFF,
        PAUSED,
        RESUMED,
    }

    fun toggle(context: Context): Outcome = if (Prefs.isEnabled(context)) turnOff(context) else turnOn(context)

    fun turnOn(context: Context): Outcome {
        if (Prefs.isEnabled(context)) return Outcome.TURNED_ON
        // VPN の許可がまだなら、許可を求める画面 (アプリ) が要る
        if (VpnService.prepare(context) != null) return Outcome.NEEDS_APP
        return try {
            AdBlockVpnService.start(context)
            Prefs.setEnabled(context, true)
            Outcome.TURNED_ON
        } catch (e: IllegalStateException) {
            // バックグラウンドからのフォアグラウンドサービス開始が拒否された場合
            Log.w(TAG, "start failed", e)
            Outcome.NEEDS_APP
        }
    }

    fun turnOff(context: Context): Outcome {
        if (!Prefs.isEnabled(context)) return Outcome.TURNED_OFF
        AdBlockVpnService.stop(context)
        Prefs.setEnabled(context, false)
        return Outcome.TURNED_OFF
    }

    /** 一時停止中なら再開し、そうでなければ minutes 分の一時停止を始める。 */
    fun togglePause(context: Context, minutes: Int = DEFAULT_PAUSE_MINUTES): Outcome =
        if (Prefs.isEnabled(context) && Pause.isPaused()) resume(context) else pause(context, minutes)

    fun pause(context: Context, minutes: Int = DEFAULT_PAUSE_MINUTES): Outcome {
        if (!Prefs.isEnabled(context)) return Outcome.IS_OFF
        Pause.start(context, minutes.coerceIn(1, MAX_PAUSE_MINUTES) * 60_000L)
        return Outcome.PAUSED
    }

    fun resume(context: Context): Outcome {
        if (!Prefs.isEnabled(context)) return Outcome.IS_OFF
        Pause.resume(context)
        return Outcome.RESUMED
    }
}
