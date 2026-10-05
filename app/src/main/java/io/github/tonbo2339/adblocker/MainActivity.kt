package io.github.tonbo2339.adblocker

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import androidx.core.graphics.drawable.toDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.CompoundButton
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.tonbo2339.adblocker.Prefs.NotificationKind
import io.github.tonbo2339.adblocker.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    /** 状態カードの表示内容。 */
    private data class StatusLook(val title: Int, val detail: Int, val color: Int, val icon: Int)

    /** 今表示している状態 (1 秒ごとの更新で、変わったときだけ描き直す)。 */
    private var shownStatus: StatusLook? = null

    companion object {
        /** 「新しいバージョンがあります」の通知から開いたときのアクション。 */
        const val ACTION_INSTALL_UPDATE = "io.github.tonbo2339.adblocker.INSTALL_UPDATE"
    }

    private lateinit var binding: ActivityMainBinding
    private val numberFormat = NumberFormat.getIntegerInstance()
    private val handler = Handler(Looper.getMainLooper())

    /** 日付は端末の言語に関係なく 年/月/日 の順で表示する (作者の希望)。 */
    private val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

    /** updateStatus() がスイッチを同期している間は true (リスナーで無視する)。 */
    private var syncingSwitch = false

    /** 「今すぐ更新」をタップして結果を待っている間は true。 */
    private var awaitingUpdate = false

    /** 「アップデートを確認」をタップして結果を待っている間は true。 */
    private var awaitingAppUpdate = false
    private val refresher = object : Runnable {
        override fun run() {
            updateStatus()
            // ブロックリストはバックグラウンドで更新されるので件数も追従させる
            updateSettings()
            handler.postDelayed(this, 1000)
        }
    }

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                AdBlockVpnService.start(this)
            } else {
                Prefs.setEnabled(this, false)
                Toast.makeText(this, R.string.vpn_denied, Toast.LENGTH_LONG).show()
            }
            updateStatus()
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setUpNavigationBar()

        binding.vpnSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitch) return@setOnCheckedChangeListener
            Prefs.setEnabled(this, checked)
            if (checked) startVpn() else AdBlockVpnService.stop(this)
            updateStatus()
        }
        binding.exclusionsRow.setOnClickListener {
            startActivity(Intent(this, AppListActivity::class.java))
        }
        binding.alwaysOnRow.setOnClickListener {
            // 端末によっては VPN 設定画面が無いので、ネットワーク設定 → 設定アプリの順に試す
            val actions = listOf(Settings.ACTION_VPN_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS)
            for (action in actions) {
                try {
                    startActivity(Intent(action))
                    break
                } catch (_: ActivityNotFoundException) {
                }
            }
        }
        binding.updateRow.setOnClickListener {
            awaitingUpdate = true
            BlockListWorker.runNow(this)
        }
        binding.sourcesFooter.text = getString(
            R.string.sources_footer,
            BlockListUpdater.SOURCES.joinToString("、") { it.name },
        )
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(BlockListWorker.ONE_TIME)
            .observe(this) { infos -> onUpdateWork(infos.firstOrNull()) }

        binding.versionValue.text = getString(R.string.version_value, BuildConfig.VERSION_NAME)
        binding.autoInstallSwitch.isChecked = Prefs.autoInstallUpdates(this)
        binding.autoInstallSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setAutoInstallUpdates(this, checked)
        }
        binding.checkUpdateRow.setOnClickListener { checkAppUpdate() }
        bindNotificationSwitch(binding.notifyAppUpdateSwitch, NotificationKind.APP_UPDATE)
        bindNotificationSwitch(binding.notifyBlocklistSwitch, NotificationKind.BLOCKLIST_UPDATE)
        bindNotificationSwitch(binding.notifyRunningSwitch, NotificationKind.RUNNING) {
            AdBlockVpnService.refreshNotification(this)
        }
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(AppUpdateWorker.ONE_TIME)
            .observe(this) { infos -> onAppUpdateWork(infos.firstOrNull()) }
        handleIntent(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // ブロックリストの件数を表示するために先に読み込んでおく
        thread {
            BlockList.load(applicationContext)
            runOnUiThread { if (!isDestroyed) updateSettings() }
        }
    }

    /**
     * iOS の大きなタイトルの動き: 内容はステータスバーの下までスクロールし、大きなタイトルが隠れたら
     * 上部のバーに背景・区切り線・小さなタイトルを出す。
     */
    private fun setUpNavigationBar() {
        val navBackground = getColor(R.color.nav_bar_bg).toDrawable().apply { alpha = 0 }
        binding.navBar.background = navBackground

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.navBar.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            binding.scroll.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        val fadeDistance = resources.displayMetrics.density * 12
        binding.scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            // 背景と区切り線は少しスクロールしたら出す
            val barAlpha = (scrollY / fadeDistance).coerceIn(0f, 1f)
            navBackground.alpha = (barAlpha * 255).toInt()
            binding.navDivider.alpha = barAlpha
            // 小さなタイトルは、大きなタイトルがバーの下に隠れたら出す
            val titleHidden = scrollY > binding.largeTitle.bottom - binding.navTitle.height
            val target = if (titleHidden) 1f else 0f
            if (binding.navTitle.tag != target) {
                binding.navTitle.tag = target
                binding.navTitle.animate().alpha(target).setDuration(150).start()
            }
        }
    }

    private fun bindNotificationSwitch(switch: CompoundButton, kind: NotificationKind, onChange: () -> Unit = {}) {
        switch.isChecked = Prefs.isNotificationEnabled(this, kind)
        switch.setOnCheckedChangeListener { _, checked ->
            Prefs.setNotificationEnabled(this, kind, checked)
            onChange()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** 「新しいバージョンがあります」の通知から開かれたらインストールを始める。 */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != ACTION_INSTALL_UPDATE) return
        intent.action = null // 画面の回転などで作り直されたときに繰り返さない
        checkAppUpdate()
    }

    private fun checkAppUpdate() {
        awaitingAppUpdate = true
        AppUpdateWorker.runNow(this)
    }

    override fun onResume() {
        super.onResume()
        AppUpdater.uiVisible = true
        resumeIfNeeded()
        updateSettings()
        handler.post(refresher)
    }

    /** ON のはずなのに動いていない (プロセスが終了させられた等) ときは開き直したついでに再開する。 */
    private fun resumeIfNeeded() {
        if (!Prefs.isEnabled(this) || AdBlockVpnService.state != AdBlockVpnService.State.STOPPED) return
        if (VpnService.prepare(this) == null) {
            AdBlockVpnService.start(this)
        } else {
            Prefs.setEnabled(this, false)
        }
    }

    override fun onPause() {
        AppUpdater.uiVisible = false
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun startVpn() {
        val consent = VpnService.prepare(this)
        if (consent != null) {
            vpnPermission.launch(consent)
        } else {
            AdBlockVpnService.start(this)
        }
    }

    private fun updateStatus() {
        val enabled = Prefs.isEnabled(this)
        syncingSwitch = true
        binding.vpnSwitch.isChecked = enabled
        syncingSwitch = false

        val look = when {
            !enabled -> StatusLook(R.string.status_off, R.string.status_detail_off, R.color.status_off, R.drawable.ic_shield_off)
            AdBlockVpnService.isRunning ->
                StatusLook(R.string.status_on, R.string.status_detail_on, R.color.sys_green, R.drawable.ic_shield_status)
            else -> StatusLook(R.string.status_starting, R.string.status_detail_starting, R.color.status_off, R.drawable.ic_shield_status)
        }
        if (look != shownStatus) {
            shownStatus = look
            binding.statusText.setText(look.title)
            binding.statusDetail.setText(look.detail)
            binding.statusIcon.setImageResource(look.icon)
            binding.statusIcon.imageTintList = ColorStateList.valueOf(getColor(look.color))
        }

        binding.blockedCount.text = numberFormat.format(AdBlockVpnService.blockedCount.get())
        binding.queryCount.text = numberFormat.format(AdBlockVpnService.queryCount.get())
    }

    private fun updateSettings() {
        binding.exclusionValue.text = getString(R.string.exclusion_value, Prefs.excluded(this).size)
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
        updateSettings()
        // 以前の実行結果も最初に通知されるので、このタップで始めた更新のときだけ知らせる
        if (!awaitingUpdate) return
        awaitingUpdate = false
        val message = if (state == WorkInfo.State.SUCCEEDED) R.string.update_done else R.string.update_failed
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
