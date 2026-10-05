package io.github.tonbo2339.adblocker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** ブロックリストとアプリの更新を実行する条件と時刻 (BlockListWorker / AppUpdateWorker で共通)。 */
object UpdateConstraints {

    /** 1 日 1 回の自動更新。設定「Wi-Fi のときだけ自動更新」がオンなら従量制でない回線に限る。 */
    fun automatic(context: Context): Constraints = Constraints.Builder()
        .setRequiredNetworkType(
            if (Prefs.updateOnWifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED
        )
        .build()

    /** 画面から手動で更新するとき。ユーザーが自分で押したので、回線の種類は問わない。 */
    val manual: Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * 1 日 1 回の自動更新を登録する。時刻を指定していれば、次のその時刻から 24 時間ごと (端末が少し遅らせることはある)。
     * retime: 時刻を変えたとき true (予定を作り直す)。それ以外は、次回の予定を保ったまま条件だけ更新する。
     */
    fun schedulePeriodic(context: Context, name: String, worker: Class<out ListenableWorker>, retime: Boolean) {
        val builder = PeriodicWorkRequest.Builder(worker, 1, TimeUnit.DAYS)
            .setConstraints(automatic(context))
        Prefs.updateTimeMinutes(context)?.let { builder.setInitialDelay(delayUntil(it), TimeUnit.MILLISECONDS) }
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            name,
            if (retime) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.UPDATE,
            builder.build(),
        )
    }

    /** 次に minutes (0 時からの分) になるまでのミリ秒。 */
    fun delayUntil(minutes: Int, now: Calendar = Calendar.getInstance()): Long {
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis - now.timeInMillis
    }
}
