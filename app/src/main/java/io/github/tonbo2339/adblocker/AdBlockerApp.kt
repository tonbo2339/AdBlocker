package io.github.tonbo2339.adblocker

import android.app.Application

class AdBlockerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BlockListWorker.schedule(this)
        AppUpdateWorker.schedule(this)
    }
}
