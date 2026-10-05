package io.github.tonbo2339.adblocker

import android.content.DialogInterface
import android.os.Bundle
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.work.WorkManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tonbo2339.adblocker.databinding.ActivityBlocklistsBinding
import io.github.tonbo2339.adblocker.databinding.DialogRuleBinding
import io.github.tonbo2339.adblocker.databinding.ItemSourceRowBinding
import java.net.URI
import java.text.NumberFormat
import kotlin.concurrent.thread

/** 使うブロックリストを選ぶ (組み込みのオン / オフと、URL での追加)。 */
class BlocklistsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlocklistsBinding
    private val numberFormat = NumberFormat.getIntegerInstance()

    /** ダウンロード中か (まだ無いリストを「ダウンロード中」と見せる)。 */
    private var downloading = false

    /** 取得元ごとのルール数 (ファイルを読んで数える)。 */
    private var counts: Map<String, Int?> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityBlocklistsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()

        binding.backButton.setOnClickListener { finish() }
        binding.addRow.setOnClickListener { showAddDialog() }
        show()
        loadCounts()
        // 足したリストのダウンロードが終わったら件数を数え直す
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(BlockListWorker.ONE_TIME)
            .observe(this) { infos ->
                val state = infos.firstOrNull()?.state
                downloading = state != null && !state.isFinished
                if (state?.isFinished == true) loadCounts() else show()
            }
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

    private fun show() {
        val builtIn = binding.builtInCard
        builtIn.removeAllViews()
        for ((i, source) in BlockListUpdater.BUILT_IN.withIndex()) {
            val row = ItemSourceRowBinding.inflate(layoutInflater, builtIn, true)
            row.title.text = source.name
            row.subtitle.text = listOfNotNull(countText(source), source.license).joinToString(" · ")
            row.toggle.isChecked = Prefs.isSourceEnabled(this, source)
            row.toggle.setOnCheckedChangeListener { _, checked ->
                Prefs.setSourceEnabled(this, source, checked)
                selectionChanged()
            }
            // 行のどこを押してもスイッチを切り替える (iOS の設定と同じ)
            row.row.setBackgroundResource(R.drawable.bg_row_pressed)
            row.row.setOnClickListener { row.toggle.toggle() }
            row.separator.isVisible = i != BlockListUpdater.BUILT_IN.lastIndex
        }

        val custom = binding.customCard
        custom.removeAllViews()
        for (url in Prefs.customSourceUrls(this).sorted()) {
            val source = BlockListUpdater.custom(url)
            val row = ItemSourceRowBinding.inflate(layoutInflater, custom, true)
            row.title.text = source.name
            row.subtitle.text = listOfNotNull(countText(source), url).joinToString(" · ")
            row.toggle.isVisible = false
            row.row.setBackgroundResource(R.drawable.bg_row_pressed)
            row.row.setOnClickListener { confirmDelete(url, source.name) }
        }
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
            runOnUiThread { if (!isDestroyed) loadCounts() }
        }
    }

    private fun showAddDialog() {
        val input = DialogRuleBinding.inflate(layoutInflater)
        input.domainInput.setHint(R.string.list_url_hint)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.list_add)
            .setView(input.root)
            .setPositiveButton(R.string.action_add, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        val submit = {
            val url = normalizeUrl(input.domainInput.text.toString())
            if (url == null) {
                input.domainInput.error = getString(R.string.list_url_invalid)
            } else {
                Prefs.setCustomSourceUrls(this, Prefs.customSourceUrls(this) + url)
                selectionChanged()
                show()
                dialog.dismiss()
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { submit() }
        }
        input.domainInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) submit()
            actionId == EditorInfo.IME_ACTION_DONE
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.domainInput.requestFocus()
    }

    /** https の URL だけを受け付ける (平文の http だと途中で書き換えられる恐れがある)。 */
    private fun normalizeUrl(text: String): String? {
        val url = text.trim()
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrEmpty()) return null
        return url
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
