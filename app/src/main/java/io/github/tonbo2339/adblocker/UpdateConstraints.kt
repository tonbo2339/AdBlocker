package io.github.tonbo2339.adblocker

import android.content.Context
import androidx.work.Constraints
import androidx.work.NetworkType

/** ブロックリストとアプリの更新を実行する条件 (BlockListWorker / AppUpdateWorker で共通)。 */
object UpdateConstraints {

    /** 1 日 1 回の自動更新。設定「Wi-Fi のときだけ」がオンなら従量制でない回線に限る。 */
    fun automatic(context: Context): Constraints = Constraints.Builder()
        .setRequiredNetworkType(
            if (Prefs.updateOnWifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED
        )
        .build()

    /** 画面から手動で更新するとき。ユーザーが自分で押したので、回線の種類は問わない。 */
    val manual: Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
}
