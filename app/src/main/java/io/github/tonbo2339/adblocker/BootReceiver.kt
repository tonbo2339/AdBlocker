package io.github.tonbo2339.adblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log

/** 端末の再起動後とアプリの更新後、ON にしていた場合は広告ブロックを再開する。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Notifications.updated(context)
        if (!Prefs.isEnabled(context)) return
        // 許可が取り消されている (別の VPN アプリで上書きされた等) なら OFF に戻すだけ
        if (VpnService.prepare(context) != null) {
            Prefs.setEnabled(context, false)
            return
        }
        try {
            AdBlockVpnService.start(context)
        } catch (e: IllegalStateException) {
            Log.w("AdBlockBoot", "auto start failed", e)
            Prefs.setEnabled(context, false)
        }
    }
}
