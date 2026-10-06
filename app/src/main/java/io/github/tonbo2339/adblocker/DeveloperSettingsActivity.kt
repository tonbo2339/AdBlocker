package io.github.tonbo2339.adblocker

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** 開発者向け (アップデートの画面でバージョンを 5 回タップすると、設定に出る): デバッグログ。 */
class DeveloperSettingsActivity : SettingsPageActivity() {
    override val pageTitle = R.string.title_developer

    private lateinit var exportRow: SettingRow
    private lateinit var sizeFooter: TextView

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportTo(uri)
    }

    override fun SettingsBuilder.build(savedInstanceState: Bundle?) {
        val context = this@DeveloperSettingsActivity
        card(spaced = true) {
            toggle(R.string.row_debug_log, R.drawable.ic_g_doc, R.color.sys_gray, Prefs.debugLog(context)) {
                Prefs.setDebugLog(context, it)
                if (it) DebugLog.i("Developer", "debug log on\n" + diagnostics(context))
                refresh()
            }
        }
        footer(R.string.debug_log_footer)

        card(spaced = true) {
            exportRow = action(R.string.row_export_debug_log, R.drawable.ic_g_download, R.color.sys_gray) {
                exportLauncher.launch("AdBlocker-debug-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".txt")
            }
            action(R.string.row_clear_debug_log, R.drawable.ic_g_refresh, R.color.sys_gray) {
                DebugLog.clear { runOnUiThread { if (!isDestroyed) refresh() } }
            }
        }
        sizeFooter = footer()

        card(spaced = true) {
            action(R.string.row_developer_off) {
                Prefs.setDeveloperMode(context, false)
                Toast.makeText(context, R.string.developer_off, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun refresh() {
        sizeFooter.text = getString(R.string.debug_log_size, Formatter.formatShortFileSize(this, DebugLog.size()))
    }

    private fun exportTo(uri: Uri) {
        val app = applicationContext
        exportRow.busy = true
        thread {
            val ok = try {
                app.contentResolver.openOutputStream(uri, "wt")?.use { DebugLog.export(it, diagnostics(app)) } != null
            } catch (e: IOException) {
                false
            }
            runOnUiThread {
                if (!isDestroyed) exportRow.busy = false
                Toast.makeText(app, if (ok) R.string.export_debug_log_done else R.string.export_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 書き出すログの先頭に付ける、アプリと端末の状態 (ドメイン名や Wi-Fi の名前は含めない)。 */
    private fun diagnostics(context: Context): String = buildString {
        appendLine("AdBlocker v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Time: " + SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US).format(Date()))
        appendLine("Enabled: ${Prefs.isEnabled(context)}, state: ${AdBlockVpnService.state}, paused: ${Pause.isPaused()}, unblocked Wi-Fi now: ${WifiNetworks.isUnblocked()}")
        appendLine("Blocklists: ${BlockListUpdater.sources(context).joinToString { if (it.builtIn) it.id else "custom" }}; domains: ${BlockList.size}")
        appendLine("Rules: block ${UserRules.list(UserRules.Kind.BLOCK).size}, allow ${UserRules.list(UserRules.Kind.ALLOW).size}; excluded apps: ${Prefs.installedExcluded(context).size}")
        appendLine("Unblocked Wi-Fi networks: ${WifiNetworks.list.size}; location permission: ${WifiNetworks.permission(context)}, location on: ${WifiNetworks.locationEnabled(context)}")
        appendLine("Encrypted DNS: ${Prefs.encryptedDns(context)?.id ?: "off"}; capture public DNS: ${Prefs.captureHardcodedDns(context)}")
        appendLine("Query log: ${Prefs.queryLogEnabled(context)} (keep ${Prefs.logRetentionDays(context)} days)")
    }
}
