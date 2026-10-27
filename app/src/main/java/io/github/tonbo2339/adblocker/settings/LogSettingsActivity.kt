package io.github.tonbo2339.adblocker.settings

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tonbo2339.adblocker.R
import io.github.tonbo2339.adblocker.data.Prefs
import io.github.tonbo2339.adblocker.log.QueryLog
import io.github.tonbo2339.adblocker.log.QueryLogFiles
import io.github.tonbo2339.adblocker.log.logPeriodText
import java.io.IOException
import kotlin.concurrent.thread

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
