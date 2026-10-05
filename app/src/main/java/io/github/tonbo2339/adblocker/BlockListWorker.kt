package io.github.tonbo2339.adblocker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** ブロックリストを定期的に更新する。WorkManager が端末の再起動後も続けて実行する。 */
class BlockListWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val PERIODIC = "blocklist-update"
        const val ONE_TIME = "blocklist-update-now"
        private const val MAX_RETRIES = 3
        private const val KEY_MANUAL = "manual"

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** 1 日 1 回の自動更新を登録する (登録済みなら次回の実行予定は保ったまま設定だけ更新)。 */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BlockListWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** 今すぐ更新する。進み具合は getWorkInfosForUniqueWorkLiveData(ONE_TIME) で見られる。 */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<BlockListWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_MANUAL to true))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.KEEP, request)
        }
    }

    override fun doWork(): Result {
        // インストール直後の初回ダウンロードは「更新」ではないので通知しない
        val firstDownload = Prefs.blocklistCheckedAt(applicationContext) == 0L
        val result = BlockListUpdater.update(applicationContext)
        // 手動の更新は画面に結果を出すので、通知は自動更新で中身が変わったときだけ
        if (result.updated > 0 && !firstDownload && !inputData.getBoolean(KEY_MANUAL, false)) {
            Notifications.blocklistUpdated(applicationContext, BlockList.size)
        }
        return when {
            result.success -> Result.success()
            // 一時的な障害かもしれないので少し待って再試行 (それでもだめなら次回の定期実行に任せる)
            runAttemptCount < MAX_RETRIES -> Result.retry()
            else -> Result.failure()
        }
    }
}
