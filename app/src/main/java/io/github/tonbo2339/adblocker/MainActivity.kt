package io.github.tonbo2339.adblocker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tonbo2339.adblocker.databinding.ActivityMainBinding
import io.github.tonbo2339.adblocker.databinding.ItemValueRowBinding
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        /** 「新しいバージョンがあります」の通知から開いたときのアクション。 */
        const val ACTION_INSTALL_UPDATE = "io.github.tonbo2339.adblocker.INSTALL_UPDATE"

        private const val KEY_TAB = "tab"
        private const val TOP_DOMAINS = 10
        private val PAUSE_CHOICES = listOf(
            R.string.pause_5min to 5 * DateUtils.MINUTE_IN_MILLIS,
            R.string.pause_15min to 15 * DateUtils.MINUTE_IN_MILLIS,
            R.string.pause_1hour to DateUtils.HOUR_IN_MILLIS,
        )
    }

    private enum class Tab { HOME, STATS, LOG }

    /** 状態カードの表示内容。 */
    private data class StatusLook(val title: Int, val detail: String, val color: Int, val icon: Int)

    private lateinit var binding: ActivityMainBinding
    private lateinit var privateDns: PrivateDnsMonitor
    private val handler = Handler(Looper.getMainLooper())
    private val numberFormat = NumberFormat.getIntegerInstance()
    private val percentFormat = NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }

    /** 日付は端末の言語に関係なく 年/月/日 の順で表示する (作者の希望)。 */
    private val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
    private val weekdayFormat = SimpleDateFormat("EEE", Locale.getDefault())

    private var tab = Tab.HOME
    private var shownStatus: StatusLook? = null

    /** updateHome() がスイッチを同期している間は true (リスナーで無視する)。 */
    private var syncingSwitch = false

    /** よくブロックしたドメイン・アプリの表示中の内容 (変わったときだけ作り直す)。 */
    private var shownTop: Triple<List<Pair<String, Int>>, List<Pair<String, Int>>, Boolean>? = null

    private val logAdapter = LogAdapter(this)

    private val refresher = object : Runnable {
        override fun run() {
            refreshCurrentTab()
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
            updateHome()
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setUpHome()
        setUpStats()
        setUpLog()
        setUpBars()
        binding.menuButton.setOnClickListener { startActivity(SettingsActivity.intent(this)) }
        binding.tabHome.setOnClickListener { selectTab(Tab.HOME) }
        binding.tabStats.setOnClickListener { selectTab(Tab.STATS) }
        binding.tabLog.setOnClickListener { selectTab(Tab.LOG) }
        selectTab(savedInstanceState?.getString(KEY_TAB)?.let { Tab.valueOf(it) } ?: Tab.HOME)

        privateDns = PrivateDnsMonitor(this) { updatePrivateDnsCard() }
        handleIntent(intent)

        // 通知の許可は初回だけ尋ねる (断られたら、あとは端末の設定から変えてもらう)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !Prefs.notificationPermissionAsked(this) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Prefs.setNotificationPermissionAsked(this)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // ログの「許可する / ブロックする」の判断にブロックリストを使うので先に読み込んでおく
        thread { BlockList.load(applicationContext) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, tab.name)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** 「新しいバージョンがあります」の通知から開かれたら、設定画面で更新を確認する。 */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != ACTION_INSTALL_UPDATE) return
        intent.action = null // 画面の回転などで作り直されたときに繰り返さない
        startActivity(SettingsActivity.intent(this, checkUpdate = true))
    }

    override fun onStart() {
        super.onStart()
        privateDns.start()
    }

    override fun onStop() {
        privateDns.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        resumeIfNeeded()
        // 設定でログをオン / オフしたときは中身が変わらなくても表示を変える
        logAdapter.refresh(force = true)
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
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

    // ---- タブとバー ----

    private fun selectTab(tab: Tab) {
        this.tab = tab
        binding.home.root.isVisible = tab == Tab.HOME
        binding.stats.root.isVisible = tab == Tab.STATS
        binding.log.root.isVisible = tab == Tab.LOG
        binding.tabHome.isSelected = tab == Tab.HOME
        binding.tabStats.isSelected = tab == Tab.STATS
        binding.tabLog.isSelected = tab == Tab.LOG
        binding.navTitle.setText(
            when (tab) {
                Tab.HOME -> R.string.app_name
                Tab.STATS -> R.string.tab_stats
                Tab.LOG -> R.string.tab_log
            }
        )
        // 表示したばかりのページはまだ大きさが決まっていないので、配置が済んでから判定する
        binding.root.post { updateNavBar() }
        refreshCurrentTab()
    }

    private fun refreshCurrentTab() {
        when (tab) {
            Tab.HOME -> updateHome()
            Tab.STATS -> updateStats()
            Tab.LOG -> updateLog()
        }
    }

    private lateinit var navBackground: ColorDrawable

    /**
     * iOS の大きなタイトルの動き: 内容はステータスバーの下までスクロールし、大きなタイトルが隠れたら
     * 上部のバーに背景・区切り線・小さなタイトルを出す。下のタブバーはナビゲーションバーの上に置く。
     */
    private fun setUpBars() {
        navBackground = getColor(R.color.nav_bar_bg).toDrawable().apply { alpha = 0 }
        binding.navBar.background = navBackground

        val pages = listOf(binding.home.root, binding.stats.root, binding.log.root)
        // ログのリストは自分の左右の余白を持っているので、それに足す
        val basePadding = pages.associateWith { it.paddingLeft to it.paddingRight }
        val tabBarHeight = resources.getDimensionPixelSize(R.dimen.tab_bar_height)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.navBar.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            binding.tabBar.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            for (page in pages) {
                val (left, right) = basePadding.getValue(page)
                page.updatePadding(
                    left = left + bars.left, top = bars.top, right = right + bars.right, bottom = bars.bottom + tabBarHeight,
                )
            }
            insets
        }

        binding.home.root.setOnScrollChangeListener { _, _, _, _, _ -> updateNavBar() }
        binding.stats.root.setOnScrollChangeListener { _, _, _, _, _ -> updateNavBar() }
        binding.log.root.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = updateNavBar()
        })
    }

    private fun updateNavBar() {
        val (scrollY, titleBottom) = when (tab) {
            Tab.HOME -> binding.home.root.scrollY to binding.home.largeTitle.bottom
            Tab.STATS -> binding.stats.root.scrollY to binding.stats.statsTitle.bottom
            Tab.LOG -> {
                val list = binding.log.root
                val header = logAdapter.header
                // 見出しが画面外まで流れたら、タイトルは隠れている
                val headerShown = header != null && header.root.parent != null && list.getChildAdapterPosition(header.root) == 0
                list.computeVerticalScrollOffset() to (if (headerShown) header.largeTitle.bottom else 0)
            }
        }
        val fadeDistance = resources.displayMetrics.density * 12
        val barAlpha = (scrollY / fadeDistance).coerceIn(0f, 1f)
        navBackground.alpha = (barAlpha * 255).toInt()
        binding.navDivider.alpha = barAlpha
        val titleHidden = scrollY > titleBottom - binding.navTitle.height
        val target = if (titleHidden) 1f else 0f
        if (binding.navTitle.tag != target) {
            binding.navTitle.tag = target
            binding.navTitle.animate().alpha(target).setDuration(150).start()
        }
    }

    // ---- ホーム ----

    private fun setUpHome() {
        val home = binding.home
        home.vpnSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitch) return@setOnCheckedChangeListener
            Prefs.setEnabled(this, checked)
            if (checked) startVpn() else AdBlockVpnService.stop(this)
            updateHome()
        }
        home.pauseRow.setOnClickListener {
            if (Pause.isPaused()) {
                Pause.resume(this)
                updateHome()
            } else {
                choosePauseDuration()
            }
        }
        home.privateDnsRow.setOnClickListener {
            openSystemSettings(Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS)
        }
    }

    private fun startVpn() {
        val consent = VpnService.prepare(this)
        if (consent != null) {
            vpnPermission.launch(consent)
        } else {
            AdBlockVpnService.start(this)
        }
    }

    private fun choosePauseDuration() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pause_title)
            .setItems(PAUSE_CHOICES.map { getString(it.first) }.toTypedArray()) { _, which ->
                Pause.start(this, PAUSE_CHOICES[which].second)
                updateHome()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun updateHome() {
        val home = binding.home
        val enabled = Prefs.isEnabled(this)
        val paused = enabled && Pause.isPaused()
        syncingSwitch = true
        home.vpnSwitch.isChecked = enabled
        syncingSwitch = false

        val look = when {
            !enabled -> StatusLook(
                R.string.status_off, getString(R.string.status_detail_off), R.color.status_off, R.drawable.ic_shield_off,
            )
            paused -> StatusLook(
                R.string.status_paused,
                getString(R.string.status_detail_paused, DateFormat.getTimeFormat(this).format(Date(Pause.resumesAt))),
                R.color.sys_orange, R.drawable.ic_shield_status,
            )
            AdBlockVpnService.isRunning && WifiNetworks.isUnblocked() -> StatusLook(
                R.string.status_wifi_unblocked, getString(R.string.status_detail_wifi_unblocked), R.color.sys_orange, R.drawable.ic_shield_status,
            )
            AdBlockVpnService.isRunning -> StatusLook(
                R.string.status_on, getString(R.string.status_detail_on), R.color.sys_green, R.drawable.ic_shield_status,
            )
            else -> StatusLook(
                R.string.status_starting, getString(R.string.status_detail_starting), R.color.status_off, R.drawable.ic_shield_status,
            )
        }
        if (look != shownStatus) {
            shownStatus = look
            home.statusText.setText(look.title)
            home.statusDetail.text = look.detail
            home.statusIcon.setImageResource(look.icon)
            home.statusIcon.imageTintList = ColorStateList.valueOf(getColor(look.color))
        }

        // 一時停止は動作中だけ出す
        home.pauseRow.isVisible = enabled
        home.pauseSeparator.isVisible = enabled
        home.pauseText.setText(if (paused) R.string.row_resume else R.string.row_pause)
        home.pauseIcon.setImageResource(if (paused) R.drawable.ic_g_play else R.drawable.ic_g_pause)
        home.pauseIcon.backgroundTintList =
            ColorStateList.valueOf(getColor(if (paused) R.color.sys_green else R.color.sys_orange))
        home.pauseValue.text = if (paused) DateUtils.formatElapsedTime((Pause.remaining() + 999) / 1000) else ""

        val today = StatsStore.today(this)
        home.blockedCount.text = numberFormat.format(today.blocked)
        home.queryCount.text = numberFormat.format(today.queries)
    }

    private fun updatePrivateDnsCard() {
        val hostname = privateDns.hostname
        binding.home.privateDnsCard.isVisible = hostname != null
        if (hostname != null) binding.home.privateDnsText.text = getString(R.string.private_dns_text, hostname)
    }

    // ---- 統計 ----

    private fun setUpStats() {
        binding.stats.resetRow.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.stats_reset_title)
                .setMessage(R.string.stats_reset_message)
                .setPositiveButton(R.string.action_reset) { _, _ ->
                    StatsStore.clear(this)
                    SessionStats.reset()
                    updateStats()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    private fun formatRate(blocked: Long, queries: Long): String =
        if (queries == 0L) "–" else percentFormat.format(blocked.toDouble() / queries)

    private fun updateStats() {
        val stats = binding.stats
        val week = StatsStore.days(this, 7)
        val today = week.last()
        stats.todayBlocked.text = numberFormat.format(today.blocked)
        stats.todayQueries.text = numberFormat.format(today.queries)
        stats.todayRate.text = formatRate(today.blocked, today.queries)

        stats.weekChart.bars = week.map { BarChartView.Bar(weekdayFormat.format(it.date), it.queries, it.blocked) }
        stats.weekSummary.text = getString(
            R.string.week_summary,
            numberFormat.format(week.sumOf { it.blocked }),
            numberFormat.format(week.sumOf { it.queries }),
        )

        val totals = StatsStore.totals(this)
        stats.totalBlocked.text = numberFormat.format(totals.blocked)
        stats.totalQueries.text = numberFormat.format(totals.queries)
        stats.totalSince.text = if (totals.since == 0L) "–" else dateFormat.format(Date(totals.since))

        updateTopDomains()
    }

    private fun updateTopDomains() {
        val domains = SessionStats.top(TOP_DOMAINS)
        val apps = SessionStats.topApps(TOP_DOMAINS)
        val enabled = QueryLog.enabled
        val shown = Triple(domains, apps, enabled)
        if (shown == shownTop) return
        shownTop = shown
        fillTopCard(binding.stats.topDomains, domains, enabled, { it }) { domain ->
            DomainActions.show(this, domain, blocked = true)
        }
        // アプリはタップしても何もしない (例外アプリにするかは「例外アプリ」の画面で選ぶ)
        fillTopCard(binding.stats.topApps, apps, enabled, { AppLabels.of(this, it) }, null)
    }

    /** よくブロックしたもの (ドメイン・アプリ) の一覧を作り直す。 */
    private fun fillTopCard(
        card: ViewGroup,
        top: List<Pair<String, Int>>,
        enabled: Boolean,
        title: (String) -> String,
        onClick: ((String) -> Unit)?,
    ) {
        card.removeAllViews()
        if (top.isEmpty()) {
            val row = ItemValueRowBinding.inflate(layoutInflater, card, true)
            row.title.setText(if (enabled) R.string.top_domains_empty else R.string.top_domains_disabled)
            row.title.setTextColor(getColor(R.color.label_secondary))
            row.row.isClickable = false
            row.separator.isVisible = false
            return
        }
        for ((i, entry) in top.withIndex()) {
            val (key, count) = entry
            val row = ItemValueRowBinding.inflate(layoutInflater, card, true)
            row.title.text = title(key)
            row.value.text = numberFormat.format(count)
            row.separator.isVisible = i != top.lastIndex
            if (onClick != null) row.row.setOnClickListener { onClick(key) } else row.row.isClickable = false
        }
    }

    // ---- ログ ----

    private fun setUpLog() {
        binding.log.root.layoutManager = LinearLayoutManager(this)
        binding.log.root.adapter = logAdapter
        // 1 秒ごとの差し替えで行が点滅しないようにする
        binding.log.root.itemAnimator = null
    }

    private fun updateLog() {
        logAdapter.refresh()
    }
}
