package io.github.tonbo2339.adblocker

import android.content.Context
import android.content.Intent
import android.icu.text.ListFormatter
import android.os.Bundle
import android.widget.CompoundButton
import android.widget.Toast
import android.net.Uri
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import java.io.IOException
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.tonbo2339.adblocker.Prefs.NotificationKind
import io.github.tonbo2339.adblocker.databinding.ActivitySettingsBinding
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** 設定 (ホームの右上の ≡ から開く)。 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        /** 「新しいバージョンがあります」の通知から開いたときに、すぐ更新を確認する。 */
        private const val EXTRA_CHECK_UPDATE = "check_update"

        fun intent(context: Context, checkUpdate: Boolean = false): Intent =
            Intent(context, SettingsActivity::class.java).putExtra(EXTRA_CHECK_UPDATE, checkUpdate)
    }

    private lateinit var binding: ActivitySettingsBinding
    private val numberFormat = NumberFormat.getIntegerInstance()

    /** 日付は端末の言語に関係なく 年/月/日 の順で表示する (作者の希望)。 */
    private val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) exportTo(uri)
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(uri)
    }

    /** 「今すぐ更新」をタップして結果を待っている間は true。 */
    private var awaitingUpdate = false

    /** 「アップデートを確認」をタップして結果を待っている間は true。 */
    private var awaitingAppUpdate = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()

        binding.backButton.setOnClickListener { finish() }
        binding.rulesRow.setOnClickListener { startActivity(Intent(this, RulesActivity::class.java)) }
        // リスナーを付ける前に値を入れる (付けた後だと、値を入れただけで設定を書き直してしまう)
        refreshSwitches()
        binding.queryLogSwitch.setOnCheckedChangeListener { _, checked -> Prefs.setQueryLogEnabled(this, checked) }

        binding.updateRow.setOnClickListener {
            awaitingUpdate = true
            BlockListWorker.runNow(this)
        }
        binding.encryptedDnsRow.setOnClickListener { chooseEncryptedDns() }
        binding.exportRow.setOnClickListener {
            exportLauncher.launch("AdBlocker-settings-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".json")
        }
        binding.importRow.setOnClickListener { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        binding.listsRow.setOnClickListener { startActivity(Intent(this, BlocklistsActivity::class.java)) }
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(BlockListWorker.ONE_TIME)
            .observe(this) { infos -> onUpdateWork(infos.firstOrNull()) }

        binding.wifiOnlySwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setUpdateOnWifiOnly(this, checked)
            // 定期実行の条件を登録し直す (次回の実行予定は保たれる)
            BlockListWorker.schedule(this)
            AppUpdateWorker.schedule(this)
        }

        binding.versionValue.text = getString(R.string.version_value, BuildConfig.VERSION_NAME)
        binding.autoInstallSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setAutoInstallUpdates(this, checked)
        }
        binding.checkUpdateRow.setOnClickListener { checkAppUpdate() }
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(AppUpdateWorker.ONE_TIME)
            .observe(this) { infos -> onAppUpdateWork(infos.firstOrNull()) }

        bindNotificationSwitch(binding.notifyAppUpdateSwitch, NotificationKind.APP_UPDATE)
        bindNotificationSwitch(binding.notifyBlocklistSwitch, NotificationKind.BLOCKLIST_UPDATE)
        bindNotificationSwitch(binding.notifyRunningSwitch, NotificationKind.RUNNING) {
            AdBlockVpnService.refreshNotification(this)
        }

        // 画面の作り直し (回転など) では繰り返さない
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_CHECK_UPDATE, false)) checkAppUpdate()

        // ブロックリストの件数を表示するために読み込んでおく (読み込み済みならすぐ終わる)
        thread {
            BlockList.load(applicationContext)
            runOnUiThread { if (!isDestroyed) updateValues() }
        }
    }

    override fun onResume() {
        super.onResume()
        updateValues()
    }

    private fun bindNotificationSwitch(switch: CompoundButton, kind: NotificationKind, onChange: () -> Unit = {}) {
        switch.setOnCheckedChangeListener { _, checked ->
            Prefs.setNotificationEnabled(this, kind, checked)
            onChange()
        }
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
        // recreate() だと作り直す前のスイッチの状態が復元されて設定に書き戻されるので、値を入れ直す
        refreshSwitches()
        updateValues()
    }

    private fun refreshSwitches() {
        binding.queryLogSwitch.isChecked = Prefs.queryLogEnabled(this)
        binding.wifiOnlySwitch.isChecked = Prefs.updateOnWifiOnly(this)
        binding.autoInstallSwitch.isChecked = Prefs.autoInstallUpdates(this)
        binding.notifyAppUpdateSwitch.isChecked = Prefs.isNotificationEnabled(this, NotificationKind.APP_UPDATE)
        binding.notifyBlocklistSwitch.isChecked = Prefs.isNotificationEnabled(this, NotificationKind.BLOCKLIST_UPDATE)
        binding.notifyRunningSwitch.isChecked = Prefs.isNotificationEnabled(this, NotificationKind.RUNNING)
    }

    private fun chooseEncryptedDns() {
        val choices = listOf<EncryptedDns?>(null) + EncryptedDns.entries
        val labels = choices.map { it?.label ?: getString(R.string.encrypted_dns_off) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.row_encrypted_dns)
            .setSingleChoiceItems(labels, choices.indexOf(Prefs.encryptedDns(this))) { dialog, which ->
                // VPN を動かしたまま、次の問い合わせから切り替わる
                Prefs.setEncryptedDns(this, choices[which])
                updateValues()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun checkAppUpdate() {
        awaitingAppUpdate = true
        AppUpdateWorker.runNow(this)
    }

    private fun updateValues() {
        binding.rulesValue.text = getString(R.string.rules_value, UserRules.size)
        binding.encryptedDnsValue.text =
            Prefs.encryptedDns(this)?.title ?: getString(R.string.encrypted_dns_off)
        val sources = BlockListUpdater.sources(this)
        binding.listsValue.text = getString(R.string.rules_value, sources.size)
        // 区切り方は言語に合わせる (英語は "A and B"、日本語は "A、B")
        binding.sourcesFooter.text = if (sources.isEmpty()) {
            getString(R.string.sources_footer_none)
        } else {
            getString(R.string.sources_footer, ListFormatter.getInstance().format(sources.map { it.name }))
        }
        binding.blocklistValue.text = getString(R.string.blocklist_value, numberFormat.format(BlockList.size))
        val checkedAt = Prefs.blocklistCheckedAt(this)
        binding.lastCheckedValue.text =
            if (checkedAt == 0L) getString(R.string.last_checked_never) else dateFormat.format(Date(checkedAt))
    }

    /** 「アップデートを確認」の進み具合と結果を表示する。 */
    private fun onAppUpdateWork(info: WorkInfo?) {
        val state = info?.state
        val busy = state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING
        binding.checkUpdateRow.isEnabled = !busy
        binding.checkUpdateProgress.isVisible = busy
        binding.checkUpdateText.setText(
            when {
                state == WorkInfo.State.ENQUEUED -> R.string.update_waiting_network
                busy -> R.string.checking_update
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

    /** 「今すぐ更新」の進み具合を表示する。 */
    private fun onUpdateWork(info: WorkInfo?) {
        val state = info?.state
        val busy = state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING
        binding.updateRow.isEnabled = !busy
        binding.updateProgress.isVisible = busy
        binding.updateText.setText(
            when {
                // 初回の実行待ち = ネットワーク待ち (再試行の待ちは「更新中」と見せる)
                state == WorkInfo.State.ENQUEUED && info.runAttemptCount == 0 -> R.string.update_waiting_network
                busy -> R.string.updating
                else -> R.string.row_update_now
            }
        )
        if (state == null || !state.isFinished) return
        updateValues()
        // 以前の実行結果も最初に通知されるので、このタップで始めた更新のときだけ知らせる
        if (!awaitingUpdate) return
        awaitingUpdate = false
        val message = if (state == WorkInfo.State.SUCCEEDED) R.string.update_done else R.string.update_failed
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
