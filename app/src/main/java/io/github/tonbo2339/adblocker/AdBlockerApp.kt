package io.github.tonbo2339.adblocker

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlin.concurrent.thread

class AdBlockerApp : Application() {

    companion object {
        /** アプリの画面が 1 つでも表示されているか (更新の確認画面をその場で出すか、通知にするかの判断に使う)。 */
        @Volatile
        var inForeground = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(ForegroundTracker())
        Prefs.migrate(this)
        // VPN と画面の両方から使うので、プロセスの開始時に読み込む
        UserRules.load(this)
        Pause.load(this)
        QueryLog.enabled = Prefs.queryLogEnabled(this)
        val retention = if (QueryLog.enabled) Prefs.logRetentionDays(this) else 0
        QueryLogFiles.configure(this, retention)
        // ファイルに残したログがあれば、画面のログに戻す (プロセスが作り直されても続きから見られるように)
        if (retention > 0) thread(name = "QueryLogLoad") { QueryLog.prepend(QueryLogFiles.recent(QueryLog.CAPACITY)) }
        BlockListWorker.schedule(this)
        AppUpdateWorker.schedule(this)
    }

    /** 表示中 (onStart〜onStop) の画面の数を数える。メインスレッドからだけ呼ばれる。 */
    private class ForegroundTracker : ActivityLifecycleCallbacks {
        private var started = 0

        override fun onActivityStarted(activity: Activity) {
            started++
            inForeground = true
        }

        override fun onActivityStopped(activity: Activity) {
            started--
            inForeground = started > 0
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}
