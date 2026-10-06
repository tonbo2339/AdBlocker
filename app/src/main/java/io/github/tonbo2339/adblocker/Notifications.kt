package io.github.tonbo2339.adblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.tonbo2339.adblocker.Prefs.NotificationKind

/**
 * 通知。種類ごとに設定 (Prefs.NotificationKind) でオン / オフできる。
 *
 * - アプリのアップデート (新しい版がある・確認が必要・失敗・更新した)
 * - ブロックリストの更新
 * - 動作中の表示: VPN サービスの起動時に必要な常駐通知。オフのときは VPN の確立後にサービス側で外す
 *   (AdBlockVpnService.applyRunningNotificationSetting)
 */
object Notifications {
    private const val CHANNEL_UPDATES = "updates"
    private const val CHANNEL_BLOCKLIST = "blocklist"
    private const val CHANNEL_RUNNING = "vpn"

    const val ID_RUNNING = 1
    private const val ID_UPDATE = 100
    private const val ID_BLOCKLIST = 101

    // ---------------------------------------------------------------- アプリのアップデート

    /** 新しいバージョンがある (「自動でアップデート」がオフのとき)。タップでインストールを始める。 */
    fun updateAvailable(context: Context, version: String) {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_INSTALL_UPDATE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        showUpdate(context, context.getString(R.string.notif_update_available, version), context.getString(R.string.notif_update_available_text), intent)
    }

    /** インストールに確認が必要 (初回など)。タップで確認画面を開く。 */
    fun updateNeedsConfirmation(context: Context, confirm: Intent) {
        showUpdate(context, context.getString(R.string.notif_update_confirm), context.getString(R.string.notif_update_confirm_text), confirm)
    }

    fun updateFailed(context: Context) {
        showUpdate(context, context.getString(R.string.notif_update_failed), context.getString(R.string.notif_update_failed_text), mainIntent(context))
    }

    /** 更新が終わった (新しいバージョンで呼ばれる)。 */
    fun updated(context: Context) {
        showUpdate(context, context.getString(R.string.notif_updated, BuildConfig.VERSION_NAME), null, mainIntent(context))
    }

    private fun showUpdate(context: Context, title: String, text: String?, intent: Intent) {
        if (!Prefs.isNotificationEnabled(context, NotificationKind.APP_UPDATE)) return
        createChannel(context, CHANNEL_UPDATES, R.string.notif_channel_updates, NotificationManager.IMPORTANCE_DEFAULT)
        post(context, ID_UPDATE, CHANNEL_UPDATES, title, text, intent)
    }

    // ---------------------------------------------------------------- ブロックリストの更新

    fun blocklistUpdated(context: Context, domains: Int) {
        if (!Prefs.isNotificationEnabled(context, NotificationKind.BLOCKLIST_UPDATE)) return
        createChannel(context, CHANNEL_BLOCKLIST, R.string.notif_channel_blocklist, NotificationManager.IMPORTANCE_LOW)
        val count = java.text.NumberFormat.getIntegerInstance().format(domains)
        post(context, ID_BLOCKLIST, CHANNEL_BLOCKLIST, context.getString(R.string.notif_blocklist_updated), context.getString(R.string.notif_blocklist_updated_text, count), mainIntent(context))
    }

    // ---------------------------------------------------------------- 動作中の表示

    /** VPN サービスの startForeground() に渡す通知。 */
    fun running(context: Context): Notification {
        createChannel(context, CHANNEL_RUNNING, R.string.notif_channel, NotificationManager.IMPORTANCE_LOW)
        val builder = Notification.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentIntent(pendingActivity(context, ID_RUNNING, mainIntent(context)))
            .setOngoing(true)
        if (Pause.isPaused()) {
            val time = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(Pause.resumesAt))
            val resume = PendingIntent.getBroadcast(
                context, 0, Intent(context, ResumeReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.setContentText(context.getString(R.string.notif_paused_text, time))
                .addAction(
                    Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_g_play),
                        context.getString(R.string.row_resume), resume,
                    ).build()
                )
        } else if (WifiNetworks.isUnblocked()) {
            builder.setContentText(context.getString(R.string.notif_wifi_unblocked_text))
        } else {
            builder.setContentText(context.getString(R.string.notif_text))
        }
        return builder.build()
    }

    // ----------------------------------------------------------------

    private fun createChannel(context: Context, id: String, name: Int, importance: Int) {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(id, context.getString(name), importance))
    }

    private fun post(context: Context, id: Int, channel: String, title: String, text: String?, intent: Intent) {
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .apply { if (text != null) setContentText(text) }
            .setContentIntent(pendingActivity(context, id, intent))
            .setAutoCancel(true)
            .build()
        // 通知の許可が無い場合は何も表示されないだけ
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private fun mainIntent(context: Context) = Intent(context, MainActivity::class.java)

    private fun pendingActivity(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
