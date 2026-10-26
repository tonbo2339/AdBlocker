package io.github.tonbo2339.adblocker

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** ホーム画面のウィジェット。全体をタップでオン / オフ、右のボタンで一時停止 / 再開 (どちらも ShortcutActivity が処理する)。 */
class AdBlockWidget : AppWidgetProvider() {

    companion object {
        /** オン / オフ・一時停止・Wi-Fi の状態が変わったときに呼ぶ。ウィジェットが無ければ何もしない。 */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, AdBlockWidget::class.java))
            if (ids.isNotEmpty()) update(context, manager, ids)
        }

        private fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            Pause.load(context)
            val enabled = Prefs.isEnabled(context)
            val paused = enabled && Pause.isPaused()
            val unblocked = enabled && !paused && WifiNetworks.isUnblocked()
            val views = RemoteViews(context.packageName, R.layout.widget_status)

            val state = when {
                !enabled -> R.string.tile_off
                paused -> R.string.tile_paused
                unblocked -> R.string.tile_wifi_unblocked
                else -> R.string.tile_on
            }
            views.setTextViewText(R.id.widgetState, context.getString(state))
            views.setImageViewResource(R.id.widgetIcon, if (enabled) R.drawable.ic_shield_status else R.drawable.ic_shield_off)
            // 色は昼夜で同じ色だけを使う (ボタンの ▶ / ⏸ は layout の tint がホストの昼夜に合わせて決まる)
            val color = when {
                !enabled -> R.color.sys_gray // 昼夜で色が変わる色は、ここでは (ホストの設定に合わせて) 解決されないので使わない
                paused || unblocked -> R.color.sys_orange
                else -> R.color.accent_green
            }
            views.setInt(R.id.widgetIcon, "setColorFilter", context.getColor(color))

            // 一時停止のボタンは、オンのときだけ。一時停止中は再開 (▶) にする
            views.setViewVisibility(R.id.widgetPause, if (enabled) android.view.View.VISIBLE else android.view.View.GONE)
            views.setImageViewResource(R.id.widgetPauseIcon, if (paused) R.drawable.ic_g_play else R.drawable.ic_g_pause)

            views.setOnClickPendingIntent(R.id.widgetRoot, action(context, ShortcutActivity.ACTION_TOGGLE, 0))
            views.setOnClickPendingIntent(R.id.widgetPause, action(context, ShortcutActivity.ACTION_PAUSE, 1))
            manager.updateAppWidget(ids, views)
        }

        private fun action(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, ShortcutActivity::class.java).setAction(action)
            return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context, manager, ids)
}
