package io.github.tonbo2339.adblocker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import io.github.tonbo2339.adblocker.Prefs.NotificationKind
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** ファイル名に付ける日付 (例: AdBlocker-settings-20261005.json)。 */
private fun fileDate(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

/** DNS: 暗号化 DNS の転送先と、DNS を自分で指定するアプリへの対策。 */
class DnsSettingsActivity : SettingsPageActivity() {
    override val pageTitle = R.string.title_dns

    private val servers = listOf<EncryptedDns?>(null) + EncryptedDns.entries
    private val serverRows = mutableListOf<SettingRow>()

    override fun SettingsBuilder.build(savedInstanceState: Bundle?) {
        header(R.string.row_encrypted_dns)
        card {
            for (server in servers) {
                serverRows += choice(server?.label ?: getString(R.string.encrypted_dns_off), false) {
                    // VPN を動かしたまま、次の問い合わせから切り替わる
                    Prefs.setEncryptedDns(this@DnsSettingsActivity, server)
                    refresh()
                }
            }
        }
        footer(R.string.encrypted_dns_footer)

        card(spaced = true) {
            toggle(R.string.row_capture_dns, R.drawable.ic_g_shield, R.color.sys_red, Prefs.captureHardcodedDns(this@DnsSettingsActivity)) {
                Prefs.setCaptureHardcodedDns(this@DnsSettingsActivity, it)
                // VPN のルートを変えるので、動作中なら作り直す
                if (AdBlockVpnService.state != AdBlockVpnService.State.STOPPED) {
                    AdBlockVpnService.start(this@DnsSettingsActivity, rebuild = true)
                }
            }
        }
        footer(R.string.capture_dns_footer)
    }

    override fun refresh() {
        val current = Prefs.encryptedDns(this)
        servers.forEachIndexed { i, server -> serverRows[i].checked = server == current }
    }
}

/** ログ: 問い合わせのログのオン / オフ、残す期間、書き出し。 */
class LogSettingsActivity : SettingsPageActivity() {
    override val pageTitle = R.string.title_log

    private lateinit var retentionCard: SettingsBuilder.Card
    private val retentionRows = mutableListOf<SettingRow>()
    private lateinit var exportRow: SettingRow
    private lateinit var footer: TextView
    private lateinit var retentionViews: List<View>

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) exportTo(uri)
    }

    override fun SettingsBuilder.build(savedInstanceState: Bundle?) {
        val context = this@LogSettingsActivity
        card(spaced = true) {
            lateinit var logRow: SettingRow
            logRow = toggle(R.string.row_query_log, R.drawable.ic_g_doc, R.color.sys_gray, Prefs.queryLogEnabled(context)) { on ->
                if (on) {
                    Prefs.setQueryLogEnabled(context, true)
                    refresh()
                } else {
                    // オフにすると保存したログも消えるので確かめる。やめたらスイッチを戻す
                    confirmDelete(0, onCancel = { logRow.binding.toggle.isChecked = true }) {
                        Prefs.setQueryLogEnabled(context, false)
                        refresh()
                    }
                }
            }
        }
        footer = footer()

        val retentionHeader = header(R.string.row_log_retention)
        retentionCard = card {
            for (days in Prefs.LOG_RETENTION_CHOICES) {
                retentionRows += choice(logRetentionLabel(days), false) {
                    confirmDelete(days) {
                        Prefs.setLogRetentionDays(context, days)
                        refresh()
                    }
                }
            }
        }
        val exportCard = card(spaced = true) {
            exportRow = action(R.string.row_export_log, R.drawable.ic_g_download, R.color.sys_gray) {
                exportLauncher.launch("AdBlocker-log-" + fileDate() + ".csv")
            }
        }
        retentionViews = listOf(retentionHeader, retentionCard.layout, exportCard.layout)
    }

    override fun refresh() {
        val on = Prefs.queryLogEnabled(this)
        val days = Prefs.logRetentionDays(this)
        // ログがオフなら、期間と書き出しは出さない
        retentionViews.forEach { it.isVisible = on }
        Prefs.LOG_RETENTION_CHOICES.forEachIndexed { i, d -> retentionRows[i].checked = d == days }
        footer.text = getString(if (on && days > 0) R.string.query_log_footer_saved else R.string.query_log_footer, logPeriodText(days))
    }

    /**
     * 保存したログが消える変更 (オフにする・期間を短くする) なら、消してよいか確かめてから onConfirm を呼ぶ。
     * days は変更後に残す日数 (0 ならすべて消える)。消えるものが無ければ、確かめずにそのまま呼ぶ。
     */
    private fun confirmDelete(days: Int, onCancel: () -> Unit = {}, onConfirm: () -> Unit) {
        val current = Prefs.logRetentionDays(this)
        val shrinks = current > 0 && (days == 0 || days < current)
        if (!shrinks || !QueryLogFiles.hasSavedLog()) {
            onConfirm()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.log_delete_title)
            .setMessage(if (days == 0) getString(R.string.log_delete_all) else getString(R.string.log_delete_older, logPeriodText(days)))
            .setPositiveButton(R.string.action_delete) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.action_cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }

    private fun exportTo(uri: Uri) {
        val app = applicationContext
        exportRow.busy = true
        thread {
            val ok = try {
                app.contentResolver.openOutputStream(uri, "wt")?.use { QueryLogFiles.exportCsv(it, QueryLog.snapshot()) } != null
            } catch (e: IOException) {
                false
            }
            runOnUiThread {
                if (!isDestroyed) exportRow.busy = false
                Toast.makeText(app, if (ok) R.string.export_log_done else R.string.export_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/** アップデート: ブロックリストとアプリの自動更新 (時刻・回線) と、アプリのバージョン・更新の確認。 */
class UpdateSettingsActivity : SettingsPageActivity() {

    companion object {
        /** 「新しいバージョンがあります」の通知から開いたときに、すぐ更新を確認する。 */
        private const val EXTRA_CHECK_UPDATE = "check_update"

        fun intent(context: Context, checkUpdate: Boolean = false): Intent =
            Intent(context, UpdateSettingsActivity::class.java).putExtra(EXTRA_CHECK_UPDATE, checkUpdate)
    }

    override val pageTitle = R.string.title_updates

    private lateinit var timeRow: SettingRow
    private lateinit var checkRow: SettingRow

    /** 「アップデートを確認」をタップして結果を待っている間は true。 */
    private var awaitingAppUpdate = false

    override fun SettingsBuilder.build(savedInstanceState: Bundle?) {
        val context = this@UpdateSettingsActivity
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
            info(R.string.row_version, R.drawable.ic_g_info, R.color.sys_gray).value =
                getString(R.string.version_value, BuildConfig.VERSION_NAME)
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

/** 通知: 種類ごとのオン / オフ。 */
class NotificationSettingsActivity : SettingsPageActivity() {
    override val pageTitle = R.string.section_notifications

    override fun SettingsBuilder.build(savedInstanceState: Bundle?) {
        val context = this@NotificationSettingsActivity
        fun SettingsBuilder.Card.kind(title: Int, icon: Int, color: Int, kind: NotificationKind, onChange: () -> Unit = {}) =
            toggle(title, icon, color, Prefs.isNotificationEnabled(context, kind)) {
                Prefs.setNotificationEnabled(context, kind, it)
                onChange()
            }
        card(spaced = true) {
            kind(R.string.notify_app_update, R.drawable.ic_g_bell, R.color.sys_red, NotificationKind.APP_UPDATE)
            kind(R.string.notify_blocklist_update, R.drawable.ic_g_list, R.color.sys_orange, NotificationKind.BLOCKLIST_UPDATE)
            kind(R.string.notify_running, R.drawable.ic_g_shield, R.color.sys_green, NotificationKind.RUNNING) {
                AdBlockVpnService.refreshNotification(context)
            }
        }
        footer(R.string.notifications_footer)
    }
}

/** バックアップ: 設定の書き出しと読み込み。 */
class BackupSettingsActivity : SettingsPageActivity() {
    override val pageTitle = R.string.section_backup

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) exportTo(uri)
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(uri)
    }

    override fun SettingsBuilder.build(savedInstanceState: Bundle?) {
        card(spaced = true) {
            action(R.string.row_export, R.drawable.ic_g_doc, R.color.sys_gray) {
                exportLauncher.launch("AdBlocker-settings-" + fileDate() + ".json")
            }
            action(R.string.row_import, R.drawable.ic_g_download, R.color.sys_gray) {
                importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            }
        }
        footer(R.string.backup_footer)
    }

    private fun exportTo(uri: Uri) {
        val ok = try {
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(SettingsBackup.export(this).toByteArray()) } != null
        } catch (e: IOException) {
            false
        }
        Toast.makeText(this, if (ok) R.string.export_done else R.string.export_failed, Toast.LENGTH_SHORT).show()
    }

    private fun importFrom(uri: Uri) {
        try {
            // 設定のファイルは小さい。大きすぎるものは別のファイルとみなす
            val text = contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                if (bytes.size > 1_000_000) throw IOException("too large")
                String(bytes)
            } ?: throw IOException("cannot open")
            SettingsBackup.import(this, text)
        } catch (e: IOException) {
            Toast.makeText(this, R.string.import_failed, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, R.string.import_done, Toast.LENGTH_SHORT).show()
    }
}
