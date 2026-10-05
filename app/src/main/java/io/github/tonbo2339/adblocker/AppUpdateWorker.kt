package io.github.tonbo2339.adblocker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** アプリの更新を確認する。定期実行は設定に従い「自動でインストール」か「通知のみ」。 */
class AppUpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val PERIODIC = "app-update"
        const val ONE_TIME = "app-update-now"
        private const val KEY_INSTALL = "install"
        const val KEY_RESULT = "result"
        const val KEY_VERSION = "version"

        const val RESULT_UP_TO_DATE = "up_to_date"
        const val RESULT_AVAILABLE = "available"
        const val RESULT_INSTALLING = "installing"
        const val RESULT_FAILED = "failed"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AppUpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(UpdateConstraints.automatic(context))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** 今すぐ確認して、新しい版があればインストールする (ユーザー操作から呼ぶ)。 */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<AppUpdateWorker>()
                .setConstraints(UpdateConstraints.manual)
                .setInputData(workDataOf(KEY_INSTALL to true))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.KEEP, request)
        }
    }

    override fun doWork(): Result {
        // 定期実行には入力データが無い (設定に従う)。手動の確認は KEY_INSTALL=true で来る
        val periodic = KEY_INSTALL !in inputData.keyValueMap
        val install = if (periodic) Prefs.autoInstallUpdates(applicationContext) else inputData.getBoolean(KEY_INSTALL, true)

        val output = when (val r = AppUpdater.check(applicationContext, install)) {
            AppUpdater.Result.UpToDate -> workDataOf(KEY_RESULT to RESULT_UP_TO_DATE)
            is AppUpdater.Result.Available -> {
                if (periodic) Notifications.updateAvailable(applicationContext, r.version)
                workDataOf(KEY_RESULT to RESULT_AVAILABLE, KEY_VERSION to r.version)
            }
            is AppUpdater.Result.Installing -> workDataOf(KEY_RESULT to RESULT_INSTALLING, KEY_VERSION to r.version)
            // 定期実行の失敗は次回に任せる (毎日確認するので再試行はしない)
            is AppUpdater.Result.Failed -> return Result.failure(workDataOf(KEY_RESULT to RESULT_FAILED))
        }
        return Result.success(output)
    }
}
