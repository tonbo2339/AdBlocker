package io.github.tonbo2339.adblocker

import android.Manifest
import android.content.ActivityNotFoundException
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
import android.view.LayoutInflater
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
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tonbo2339.adblocker.databinding.ActivityMainBinding
import io.github.tonbo2339.adblocker.databinding.ItemLogBinding
import io.github.tonbo2339.adblocker.databinding.ItemLogHeaderBinding
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
    private val logTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private var tab = Tab.HOME
    private var shownStatus: StatusLook? = null

    /** updateHome() がスイッチを同期している間は true (リスナーで無視する)。 */
    private var syncingSwitch = false

    /** よくブロックしたドメインの表示中の内容 (変わったときだけ作り直す)。 */
    private var shownTopDomains: List<Pair<String, Int>>? = null

    private val logAdapter = LogAdapter()

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
        // 設定ボタンはホームにだけ出す
        binding.menuButton.isVisible = tab == Tab.HOME
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
        home.exclusionsRow.setOnClickListener { startActivity(Intent(this, AppListActivity::class.java)) }
        // 端末によっては VPN 設定画面が無いので、ネットワーク設定 → 設定アプリの順に試す
        home.alwaysOnRow.setOnClickListener {
            openSettings(Settings.ACTION_VPN_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS)
        }
        home.privateDnsRow.setOnClickListener {
            openSettings(Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS)
        }
    }

    private fun openSettings(vararg actions: String) {
        for (action in actions) {
            try {
                startActivity(Intent(action))
                return
            } catch (_: ActivityNotFoundException) {
            }
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
        home.exclusionValue.text = getString(R.string.exclusion_value, Prefs.excluded(this).size)
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
        val top = SessionStats.top(TOP_DOMAINS)
        if (top == shownTopDomains) return
        shownTopDomains = top
        val card = binding.stats.topDomains
        card.removeAllViews()
        if (top.isEmpty()) {
            val row = ItemValueRowBinding.inflate(layoutInflater, card, true)
            row.title.setText(R.string.top_domains_empty)
            row.title.setTextColor(getColor(R.color.label_secondary))
            row.row.isClickable = false
            row.separator.isVisible = false
            return
        }
        for ((i, entry) in top.withIndex()) {
            val (domain, count) = entry
            val row = ItemValueRowBinding.inflate(layoutInflater, card, true)
            row.title.text = domain
            row.value.text = numberFormat.format(count)
            row.separator.isVisible = i != top.lastIndex
            row.row.setOnClickListener { DomainActions.show(this, domain) }
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

    /** 1 行目が見出し (タイトル・検索・絞り込み)、2 行目以降が問い合わせ (新しい順)。 */
    private inner class LogAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val typeHeader = 0
        private val typeEntry = 1

        var header: ItemLogHeaderBinding? = null
            private set
        private var entries: List<QueryLog.Entry> = emptyList()
        private var shownVersion = -1L
        private var query = ""
        private var blockedOnly = false

        fun refresh(force: Boolean = false) {
            val version = QueryLog.version
            if (!force && version == shownVersion) return
            shownVersion = version
            val q = query
            val list = QueryLog.snapshot().filter { e ->
                (!blockedOnly || e.verdict.blocked) && (q.isEmpty() || e.domain.contains(q))
            }
            val old = entries.size
            entries = list
            // 見出しは作り直さない (検索欄の入力中にフォーカスやキーボードが外れないように)
            val new = list.size
            if (new > old) notifyItemRangeInserted(1 + old, new - old)
            if (new < old) notifyItemRangeRemoved(1 + new, old - new)
            if (minOf(old, new) > 0) notifyItemRangeChanged(1, minOf(old, new))
            updateHeaderTexts()
        }

        private fun updateHeaderTexts() {
            val h = header ?: return
            h.filterAll.isSelected = !blockedOnly
            h.filterBlocked.isSelected = blockedOnly
            val enabled = QueryLog.enabled
            h.logFooter.isVisible = enabled
            h.logFooter.text = getString(R.string.log_footer, numberFormat.format(QueryLog.CAPACITY))
            h.emptyText.isVisible = entries.isEmpty()
            h.emptyText.setText(
                when {
                    !enabled -> R.string.log_disabled
                    query.isNotEmpty() || blockedOnly -> R.string.log_no_results
                    else -> R.string.log_empty
                }
            )
        }

        override fun getItemCount() = 1 + entries.size

        override fun getItemViewType(position: Int) = if (position == 0) typeHeader else typeEntry

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            if (viewType == typeHeader) {
                val h = ItemLogHeaderBinding.inflate(inflater, parent, false)
                header = h
                h.searchBox.doAfterTextChanged {
                    query = it.toString().trim().lowercase()
                    refresh(force = true)
                }
                h.filterAll.setOnClickListener {
                    blockedOnly = false
                    refresh(force = true)
                }
                h.filterBlocked.setOnClickListener {
                    blockedOnly = true
                    refresh(force = true)
                }
                updateHeaderTexts()
                return object : RecyclerView.ViewHolder(h.root) {}
            }
            return EntryHolder(ItemLogBinding.inflate(inflater, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder !is EntryHolder) return
            val index = position - 1
            val e = entries[index]
            val b = holder.b
            b.domain.text = e.domain
            val verdict = getString(
                when (e.verdict) {
                    Verdict.LIST -> R.string.verdict_list
                    Verdict.USER_BLOCK -> R.string.verdict_user_block
                    Verdict.USER_ALLOW -> R.string.verdict_user_allow
                    Verdict.PAUSED -> R.string.verdict_paused
                    Verdict.PASS -> R.string.verdict_pass
                }
            )
            b.detail.text = if (e.count > 1) getString(R.string.log_detail_count, verdict, e.count) else verdict
            b.time.text = logTimeFormat.format(Date(e.time))
            b.dot.backgroundTintList = ColorStateList.valueOf(
                getColor(
                    when (e.verdict) {
                        Verdict.LIST, Verdict.USER_BLOCK -> R.color.sys_red
                        Verdict.USER_ALLOW -> R.color.sys_green
                        Verdict.PAUSED -> R.color.sys_orange
                        Verdict.PASS -> R.color.status_off
                    }
                )
            )
            // iOS の inset grouped リストのように、先頭と末尾だけ角を丸める
            val first = index == 0
            val last = index == entries.lastIndex
            b.root.setBackgroundResource(
                when {
                    first && last -> R.drawable.bg_row_single
                    first -> R.drawable.bg_row_top
                    last -> R.drawable.bg_row_bottom
                    else -> R.drawable.bg_row_middle
                }
            )
            b.separator.isVisible = !last
        }

        inner class EntryHolder(val b: ItemLogBinding) : RecyclerView.ViewHolder(b.root) {
            init {
                // 押したときのハイライトを、先頭・末尾の行の角丸で切り抜く
                b.root.clipToOutline = true
                b.root.setOnClickListener {
                    val index = bindingAdapterPosition - 1
                    val entry = entries.getOrNull(index) ?: return@setOnClickListener
                    DomainActions.show(this@MainActivity, entry.domain)
                }
            }
        }
    }
}
