package io.github.tonbo2339.adblocker

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/** クイック設定パネルのタイル。タップで広告ブロックを ON / OFF する。 */
class AdBlockTileService : TileService() {

    companion object {
        private const val TAG = "AdBlockTile"

        /** ON / OFF が変わったときに呼ぶ。パネルが開いていればタイルの表示が更新される。 */
        fun refresh(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, AdBlockTileService::class.java))
            } catch (e: Exception) {
                // タイルが追加されていない場合など
                Log.d(TAG, "requestListeningState failed: $e")
            }
        }
    }

    override fun onStartListening() {
        updateTile()
    }

    override fun onClick() {
        if (Prefs.isEnabled(this) && Pause.isPaused()) {
            // 一時停止中のタップは再開
            Pause.resume(this)
        } else if (Prefs.isEnabled(this)) {
            AdBlockVpnService.stop(this)
            Prefs.setEnabled(this, false)
        } else if (VpnService.prepare(this) != null) {
            // VPN の許可ダイアログはアクティビティからしか出せないのでアプリを開く
            openApp()
            return
        } else {
            try {
                AdBlockVpnService.start(this)
                Prefs.setEnabled(this, true)
            } catch (e: IllegalStateException) {
                // バックグラウンドからのフォアグラウンドサービス開始が拒否された場合
                Log.w(TAG, "start from tile failed", e)
                openApp()
                return
            }
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val enabled = Prefs.isEnabled(this)
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                when {
                    !enabled -> R.string.tile_off
                    Pause.isPaused() -> R.string.tile_paused
                    WifiNetworks.isUnblocked() -> R.string.tile_wifi_unblocked
                    else -> R.string.tile_on
                }
            )
        }
        tile.updateTile()
    }

    /** アプリを開く。ロック中ならロック解除してから開く。 */
    private fun openApp() {
        if (isLocked) unlockAndRun { launchMainActivity() } else launchMainActivity()
    }

    private fun launchMainActivity() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
