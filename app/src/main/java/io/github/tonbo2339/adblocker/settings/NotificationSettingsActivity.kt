package io.github.tonbo2339.adblocker.settings

import android.os.Bundle
import io.github.tonbo2339.adblocker.R
import io.github.tonbo2339.adblocker.data.Prefs
import io.github.tonbo2339.adblocker.data.Prefs.NotificationKind
import io.github.tonbo2339.adblocker.vpn.AdBlockVpnService

/** 通知: 種類ごとのオン / オフ。 */
class NotificationSettingsActivity : SettingsPageActivity() {
    override val pageTitle = R.string.section_notifications

    override fun SettingsBuilder.build(savedInstanceState: Bundle?) {
        val context = this@NotificationSettingsActivity
        fun SettingsBuilder.Card.kind(title: Int, icon: Int, color: Int, kind: NotificationKind, onChange: () -> Unit = {}) =
            toggle(title, icon, color, Prefs.isNotificationEnabled(context, kind)) {
                Prefs.setNotificationEnabled(context, kind, it)
                onChange()
            }
        card(spaced = true) {
            kind(R.string.notify_app_update, R.drawable.ic_g_bell, R.color.sys_red, NotificationKind.APP_UPDATE)
            kind(R.string.notify_blocklist_update, R.drawable.ic_g_list, R.color.sys_orange, NotificationKind.BLOCKLIST_UPDATE)
            kind(R.string.notify_stopped, R.drawable.ic_g_bell, R.color.sys_indigo, NotificationKind.STOPPED)
            kind(R.string.notify_running, R.drawable.ic_g_shield, R.color.sys_green, NotificationKind.RUNNING) {
                AdBlockVpnService.refreshNotification(context)
            }
        }
        footer(R.string.notifications_footer)
    }
}
