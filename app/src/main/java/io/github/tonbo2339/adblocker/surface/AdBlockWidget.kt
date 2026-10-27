package io.github.tonbo2339.adblocker.surface

import android.app.LocaleManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import io.github.tonbo2339.adblocker.R
import io.github.tonbo2339.adblocker.data.Prefs
import io.github.tonbo2339.adblocker.vpn.Pause
import io.github.tonbo2339.adblocker.vpn.WifiNetworks

/**
 * ホーム画面のウィジェット。全体をタップでオン / オフ、右のボタンで一時停止 / 再開 (どちらも ShortcutActivity が処理する)。
 * 大きさを変えられる。幅に合わせて、盾と状態だけの小さな形 (COMPACT)・名前も出す形・一時停止のボタンも出す形に切り替える。
 */
class AdBlockWidget : AppWidgetProvider() {

    companion object {
        /** これより狭い幅 (dp) では、盾と状態だけを縦に並べた小さな形にする (1 マス分)。 */
        private const val COMPACT_BELOW_DP = 100

        /** これ以上の幅 (dp) で、一時停止のボタンを出す (それより狭いと名前の場所が足りない)。 */
        private const val PAUSE_BUTTON_FROM_DP = 190

        /** オン / オフ・一時停止・Wi-Fi の状態が変わったときに呼ぶ。ウィジェットが無ければ何もしない。 */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, AdBlockWidget::class.java))
            update(context, manager, ids)
        }

        private fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            Pause.load(context)
            for (id in ids) {
                val width = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                manager.updateAppWidget(id, build(context, width))
            }
        }

        /** width は今のウィジェットの幅 (dp)。分からない (0) ときは広い形にする。 */
        private fun build(context: Context, width: Int): RemoteViews {
            val enabled = Prefs.isEnabled(context)
            val paused = enabled && Pause.isPaused()
            val unblocked = enabled && !paused && WifiNetworks.isUnblocked()
            val compact = width in 1 until COMPACT_BELOW_DP
            val views = RemoteViews(context.packageName, if (compact) R.layout.widget_status_small else R.layout.widget_status)

            val state = when {
                !enabled -> R.string.tile_off
                paused -> R.string.tile_paused
                unblocked -> R.string.tile_wifi_unblocked
                else -> R.string.tile_on
            }
            views.setTextViewText(R.id.widgetState, localized(context).getString(state))
            views.setImageViewResource(R.id.widgetIcon, if (enabled) R.drawable.ic_shield_status else R.drawable.ic_shield_off)
            // 色は昼夜で同じものだけを使う (昼夜で変わる色は、ここ (アプリ側) ではホストの設定に合わせて決まらない)
            val color = when {
                !enabled -> R.color.sys_gray
                paused || unblocked -> R.color.sys_orange
                else -> R.color.accent_green
            }
            views.setInt(R.id.widgetIcon, "setColorFilter", context.getColor(color))
            views.setOnClickPendingIntent(R.id.widgetRoot, action(context, ShortcutActivity.ACTION_TOGGLE, 0))

            if (!compact) {
                // 一時停止のボタンは、オンで、幅があるときだけ。一時停止中は再開 (▶) にする
                val showPause = enabled && (width == 0 || width >= PAUSE_BUTTON_FROM_DP)
                views.setViewVisibility(R.id.widgetPause, if (showPause) View.VISIBLE else View.GONE)
                views.setImageViewResource(R.id.widgetPauseIcon, if (paused) R.drawable.ic_g_play else R.drawable.ic_g_pause)
                views.setOnClickPendingIntent(R.id.widgetPause, action(context, ShortcutActivity.ACTION_PAUSE, 1))
            }
            return views
        }

        /**
         * アプリごとに選んだ言語 (Android 13 以上) で文字を出すための Context。
         * ウィジェットの更新はレシーバーの Context で届くので、そのままではアプリの言語の設定が効かないことがある。
         */
        private fun localized(context: Context): Context {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return context
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) return context
            return context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocales(locales) })
        }

        private fun action(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, ShortcutActivity::class.java).setAction(action)
            return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context, manager, ids)

    /** 大きさを変えたときに、その幅の形に作り直す。 */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) =
        update(context, manager, intArrayOf(id))
}
