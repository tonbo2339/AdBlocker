package io.github.tonbo2339.adblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** AppUpdater が始めたインストールの結果を受け取る。 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmIntent(intent) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // 画面を開いていなければアクティビティを出せないので、通知から確認画面を開いてもらう
                if (AdBlockerApp.inForeground) {
                    context.startActivity(confirm)
                } else {
                    Notifications.updateNeedsConfirmation(context, confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // 新しいバージョンが起動し直すと BootReceiver (MY_PACKAGE_REPLACED) が通知を出す
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                DebugLog.w("InstallResult", "install failed: $status $message")
                Notifications.updateFailed(context)
            }
        }
    }

    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
}
