package io.github.tonbo2339.adblocker

import android.icu.text.ListFormatter
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tonbo2339.adblocker.databinding.ItemSourceRowBinding
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** ブロックリスト: 状態 (件数・最終確認・今すぐ更新)、使うリストの選択 (組み込みのオン / オフと、URL での追加)。 */
class BlocklistsActivity : SettingsPageActivity() {

    override val pageTitle = R.string.title_blocklists

    private val numberFormat = NumberFormat.getIntegerInstance()

    /** 日付は端末の言語に関係なく 年/月/日 の順で表示する (作者の希望)。 */
    private val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

    private lateinit var domainsRow: SettingRow
    private lateinit var checkedRow: SettingRow
    private lateinit var updateRow: SettingRow
    private lateinit var sourcesFooter: TextView
    private lateinit var builtInCard: SettingsBuilder.Card
    private lateinit var customCard: SettingsBuilder.Card

    /** ダウンロード中か (まだ無いリストを「ダウンロード中」と見せる)。 */
    private var downloading = false

    /** 「今すぐ更新」をタップして結果を待っている間は true。 */
    private var awaitingUpdate = false

    /** 取得元ごとのルール数 (ファイルを読んで数える)。 */
    private var counts: Map<String, Int?> = emptyMap()

    override fun SettingsBuilder.build(savedInstanceState: Bundle?) {
        header(R.string.section_blocklist_status)
        card {
            // 値 (件数・日時) が長いので、行頭のアイコンは付けない
            domainsRow = info(R.string.row_domains)
            checkedRow = info(R.string.row_last_checked)
            updateRow = action(R.string.row_update_now) {
                awaitingUpdate = true
                BlockListWorker.runNow(this@BlocklistsActivity)
            }
        }
        sourcesFooter = footer()

        header(R.string.section_built_in_lists)
        builtInCard = card {}
        footer(R.string.built_in_lists_footer)

        header(R.string.section_custom_lists)
        customCard = card {}
        footer(R.string.custom_lists_footer)

        // 今すぐ更新・足したリストのダウンロードが終わったら、件数を数え直す
        WorkManager.getInstance(this@BlocklistsActivity)
            .getWorkInfosForUniqueWorkLiveData(BlockListWorker.ONE_TIME)
            .observe(this@BlocklistsActivity) { infos -> onUpdateWork(infos.firstOrNull()) }

        // ブロックしているドメイン数を出すために読み込んでおく (読み込み済みならすぐ終わる)
        val app = applicationContext
        thread {
            BlockList.load(app)
            runOnUiThread { if (!isDestroyed) refresh() }
        }
        loadCounts()
    }

    override fun refresh() {
        domainsRow.value = getString(R.string.blocklist_value, numberFormat.format(BlockList.size))
        val checkedAt = Prefs.blocklistCheckedAt(this)
        checkedRow.value = if (checkedAt == 0L) getString(R.string.last_checked_never) else dateFormat.format(Date(checkedAt))
        val sources = BlockListUpdater.sources(this)
        // 区切り方は言語に合わせる (英語は "A and B"、日本語は "A、B")
        sourcesFooter.text = if (sources.isEmpty()) {
            getString(R.string.sources_footer_none)
        } else {
            getString(R.string.sources_footer, ListFormatter.getInstance().format(sources.map { it.name }))
        }
        show()
    }

    /** 「今すぐ更新」の進み具合を表示する。 */
    private fun onUpdateWork(info: WorkInfo?) {
        val state = info?.state
        downloading = state != null && !state.isFinished
        updateRow.busy = downloading
        updateRow.title = getString(
            when {
                // 初回の実行待ち = ネットワーク待ち (再試行の待ちは「更新中」と見せる)
                state == WorkInfo.State.ENQUEUED && info.runAttemptCount == 0 -> R.string.update_waiting_network
                downloading -> R.string.updating
                else -> R.string.row_update_now
            }
        )
        if (state == null || !state.isFinished) {
            show()
            return
        }
        refresh()
        loadCounts()
        // 以前の実行結果も最初に通知されるので、このタップで始めた更新のときだけ知らせる
        if (!awaitingUpdate) return
        awaitingUpdate = false
        Toast.makeText(this, if (state == WorkInfo.State.SUCCEEDED) R.string.update_done else R.string.update_failed, Toast.LENGTH_SHORT).show()
    }

    private fun loadCounts() {
        val app = applicationContext
        thread {
            val all = BlockListUpdater.BUILT_IN + Prefs.customSourceUrls(app).map { BlockListUpdater.custom(it) }
            val result = all.associate { it.id to BlockListUpdater.ruleCount(app, it) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                counts = result
                show()
            }
        }
    }

    /** 取得元の一覧を作り直す。 */
    private fun show() {
        builtInCard.clear()
        for (source in BlockListUpdater.BUILT_IN) {
            val row = sourceRow(source.name, listOfNotNull(countText(source), source.license).joinToString(" · "))
            row.toggle.isChecked = Prefs.isSourceEnabled(this, source)
            row.toggle.setOnCheckedChangeListener { _, checked ->
                Prefs.setSourceEnabled(this, source, checked)
                selectionChanged()
            }
            // 行のどこを押してもスイッチを切り替える (iOS の設定と同じ)
            row.row.setOnClickListener { row.toggle.toggle() }
            builtInCard.custom(row.root)
        }

        customCard.clear()
        for (url in Prefs.customSourceUrls(this).sorted()) {
            val source = BlockListUpdater.custom(url)
            val row = sourceRow(source.name, listOfNotNull(countText(source), url).joinToString(" · "))
            row.toggle.isVisible = false
            row.row.setOnClickListener { confirmDelete(url, source.name) }
            customCard.custom(row.root)
        }
        customCard.action(R.string.list_add) { showAddDialog() }
    }

    private fun sourceRow(title: String, subtitle: String): ItemSourceRowBinding =
        ItemSourceRowBinding.inflate(layoutInflater, builtInCard.layout, false).apply {
            this.title.text = title
            this.subtitle.text = subtitle
            row.setBackgroundResource(R.drawable.bg_row_pressed)
            // 区切り線はカードの側で入れる
            separator.isVisible = false
        }

    private fun countText(source: BlockListSource): String? {
        val enabled = !source.builtIn || Prefs.isSourceEnabled(this, source)
        if (!enabled) return null
        val count = counts[source.id]
        return when {
            count != null -> getString(R.string.list_rules, numberFormat.format(count))
            // 同梱の StevenBlack はダウンロード前でも使える
            source.id == BlockList.BUNDLED_SOURCE_ID -> getString(R.string.list_bundled)
            !counts.containsKey(source.id) -> null
            downloading -> getString(R.string.updating)
            else -> getString(R.string.list_not_downloaded)
        }
    }

    /** 選択を変えたら、外したリストはすぐ使わなくし、足したリストはダウンロードする。 */
    private fun selectionChanged() {
        val app = applicationContext
        thread {
            BlockListUpdater.applySelection(app)
            if (BlockListUpdater.hasMissing(app)) BlockListWorker.runNow(app)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                refresh()
                loadCounts()
            }
        }
    }

    private fun showAddDialog() {
        showInputDialog(
            title = R.string.list_add,
            hint = R.string.list_url_hint,
            error = R.string.list_url_invalid,
            parse = BlockListUpdater::normalizeUrl,
        ) { url ->
            Prefs.setCustomSourceUrls(this, Prefs.customSourceUrls(this) + url)
            selectionChanged()
            show()
        }
    }

    private fun confirmDelete(url: String, name: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.list_delete_title, name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                Prefs.setCustomSourceUrls(this, Prefs.customSourceUrls(this) - url)
                selectionChanged()
                show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
