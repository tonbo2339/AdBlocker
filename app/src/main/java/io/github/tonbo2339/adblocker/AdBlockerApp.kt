package io.github.tonbo2339.adblocker

import android.app.Activity
import android.app.Application
import android.os.Bundle

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
