package io.github.tonbo2339.adblocker.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import io.github.tonbo2339.adblocker.BuildConfig
import io.github.tonbo2339.adblocker.R
import io.github.tonbo2339.adblocker.block.BlockListWorker
import io.github.tonbo2339.adblocker.data.Prefs
import io.github.tonbo2339.adblocker.update.AppUpdateWorker

/** アップデート: ブロックリストとアプリの自動更新 (時刻・回線) と、アプリのバージョン・更新の確認。 */
class UpdateSettingsActivity : SettingsPageActivity() {

    companion object {
        /** 「新しいバージョンがあります」の通知から開いたときに、すぐ更新を確認する。 */
        private const val EXTRA_CHECK_UPDATE = "check_update"
        private const val KEY_AWAITING = "awaiting_app_update"
        private const val DEVELOPER_TAPS = 5

        fun intent(context: Context, checkUpdate: Boolean = false): Intent =
            Intent(context, UpdateSettingsActivity::class.java).putExtra(EXTRA_CHECK_UPDATE, checkUpdate)
    }

    override val pageTitle = R.string.title_updates

    private lateinit var timeRow: SettingRow
    private lateinit var checkRow: SettingRow

    /** 「アップデートを確認」をタップして結果を待っている間は true (回転などで作り直しても結果を知らせる)。 */
    private var awaitingAppUpdate = false

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_AWAITING, awaitingAppUpdate)
    }

    override fun SettingsBuilder.build(savedInstanceState: Bundle?) {
        val context = this@UpdateSettingsActivity
        awaitingAppUpdate = savedInstanceState?.getBoolean(KEY_AWAITING) == true
        header(R.string.section_auto_update)
        card {
            timeRow = link(R.string.row_update_time, R.drawable.ic_g_clock, R.color.sys_indigo) { chooseUpdateTime() }
            toggle(R.string.row_wifi_only, R.drawable.ic_g_wifi, R.color.accent_blue, Prefs.updateOnWifiOnly(context)) {
                Prefs.setUpdateOnWifiOnly(context, it)
                // 定期実行の条件を登録し直す (次回の実行予定は保たれる)
                BlockListWorker.schedule(context)
                AppUpdateWorker.schedule(context)
            }
        }
        footer(R.string.wifi_only_footer)

        header(R.string.section_app)
        card {
            info(R.string.row_version, R.drawable.ic_g_info, R.color.sys_gray).apply {
                value = getString(R.string.version_value, BuildConfig.VERSION_NAME)
                // 隠し機能: 5 回タップで開発者モード
                binding.row.setBackgroundResource(R.drawable.bg_row_pressed)
                binding.row.setOnClickListener { onVersionTapped() }
            }
            toggle(R.string.row_auto_install, R.drawable.ic_g_download, R.color.accent_blue, Prefs.autoInstallUpdates(context)) {
                Prefs.setAutoInstallUpdates(context, it)
            }
            checkRow = action(R.string.row_check_update, R.drawable.ic_g_refresh, R.color.accent_blue) { checkAppUpdate() }
        }
        footer(R.string.app_update_footer)

        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkLiveData(AppUpdateWorker.ONE_TIME)
            .observe(context) { infos -> onAppUpdateWork(infos.firstOrNull()) }

        // 画面の作り直し (回転など) では繰り返さない
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_CHECK_UPDATE, false)) checkAppUpdate()
    }

    override fun refresh() {
        timeRow.value = updateTimeLabel()
    }

    private var versionTaps = 0
    private var tapToast: Toast? = null

    /** バージョンを 5 回タップすると開発者モード (2 回目から残りの回数を出す)。 */
    private fun onVersionTapped() {
        val message = if (Prefs.developerMode(this)) {
            getString(R.string.developer_already_on)
        } else {
            versionTaps++
            val left = DEVELOPER_TAPS - versionTaps
            when {
                left <= 0 -> {
                    Prefs.setDeveloperMode(this, true)
                    getString(R.string.developer_on)
                }
                versionTaps >= 2 -> resources.getQuantityString(R.plurals.developer_taps_left, left, left)
                else -> return
            }
        }
        tapToast?.cancel()
        tapToast = Toast.makeText(this, message, Toast.LENGTH_SHORT).also { it.show() }
    }

    private fun checkAppUpdate() {
        awaitingAppUpdate = true
        AppUpdateWorker.runNow(this)
    }

    /** 「アップデートを確認」の進み具合と結果を表示する。 */
    private fun onAppUpdateWork(info: WorkInfo?) {
        val state = info?.state
        checkRow.busy = state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING
        checkRow.title = getString(
            when {
                state == WorkInfo.State.ENQUEUED -> R.string.update_waiting_network
                checkRow.busy -> R.string.checking_update
                else -> R.string.row_check_update
            }
        )
        if (state == null || !state.isFinished || !awaitingAppUpdate) return
        awaitingAppUpdate = false
        val data = if (state == WorkInfo.State.SUCCEEDED) info.outputData else null
        val message = when (data?.getString(AppUpdateWorker.KEY_RESULT)) {
            AppUpdateWorker.RESULT_UP_TO_DATE -> getString(R.string.update_up_to_date)
            AppUpdateWorker.RESULT_INSTALLING ->
                getString(R.string.update_installing, data.getString(AppUpdateWorker.KEY_VERSION))
            else -> getString(R.string.update_check_failed)
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** 自動更新の時刻を選ぶ (指定なし、または時刻)。 */
    private fun chooseUpdateTime() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.row_update_time)
            .setItems(arrayOf(getString(R.string.update_time_any), getString(R.string.update_time_pick))) { _, which ->
                if (which == 0) setUpdateTime(null) else pickUpdateTime()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun pickUpdateTime() {
        val current = Prefs.updateTimeMinutes(this) ?: (4 * 60)
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(if (android.text.format.DateFormat.is24HourFormat(this)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .setHour(current / 60)
            .setMinute(current % 60)
            .setTitleText(R.string.row_update_time)
            // 文字の入力ではなく、時計の文字盤で選ぶ
            .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
            .build()
        picker.addOnPositiveButtonClickListener { setUpdateTime(picker.hour * 60 + picker.minute) }
        picker.show(supportFragmentManager, "update_time")
    }

    private fun setUpdateTime(minutes: Int?) {
        Prefs.setUpdateTimeMinutes(this, minutes)
        // 次回の予定を新しい時刻で作り直す
        BlockListWorker.schedule(this, retime = true)
        AppUpdateWorker.schedule(this, retime = true)
        refresh()
    }
}
