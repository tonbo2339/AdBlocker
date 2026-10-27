package io.github.tonbo2339.adblocker.settings

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import io.github.tonbo2339.adblocker.R
import io.github.tonbo2339.adblocker.data.SettingsBackup
import java.io.IOException

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
            // (全部を読んでから大きさを見ると、大きなファイルを選んだときにメモリが足りなくなるので、上限の少し先までしか読まない)
            val text = contentResolver.openInputStream(uri)?.use { input ->
                val limit = 1_000_000
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > limit) throw IOException("too large")
                }
                out.toString(Charsets.UTF_8.name())
            } ?: throw IOException("cannot open")
            SettingsBackup.import(this, text)
        } catch (e: IOException) {
            Toast.makeText(this, R.string.import_failed, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, R.string.import_done, Toast.LENGTH_SHORT).show()
    }
}
