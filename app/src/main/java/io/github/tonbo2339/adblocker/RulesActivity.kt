package io.github.tonbo2339.adblocker

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tonbo2339.adblocker.databinding.ActivityRulesBinding
import io.github.tonbo2339.adblocker.databinding.ItemValueRowBinding
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** ファイル名に付ける日付 (例: AdBlocker-rules-20261026.txt)。 */
private fun fileDate(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

/** 手動で追加するブロック / 許可のルール (「ブロック・許可するドメイン」)。 */
class RulesActivity : AppCompatActivity() {

    private companion object {
        const val KEY_KIND = "kind"

        /** 読み込むファイルの大きさの上限 (バイト)。 */
        const val MAX_IMPORT_BYTES = 1024 * 1024
    }

    private lateinit var binding: ActivityRulesBinding
    private var kind = UserRules.Kind.BLOCK

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportTo(uri)
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityRulesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()

        savedInstanceState?.getString(KEY_KIND)?.let { kind = UserRules.Kind.valueOf(it) }
        binding.backButton.setOnClickListener { finish() }
        binding.segmentBlock.setOnClickListener { selectKind(UserRules.Kind.BLOCK) }
        binding.segmentAllow.setOnClickListener { selectKind(UserRules.Kind.ALLOW) }
        binding.addRow.setOnClickListener { showAddDialog() }
        binding.checkRow.setOnClickListener { showCheckDialog() }
        binding.exportRow.setOnClickListener { exportLauncher.launch("AdBlocker-rules-" + fileDate() + ".txt") }
        binding.importRow.setOnClickListener { importLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) }
        selectKind(kind)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_KIND, kind.name)
    }

    private fun selectKind(kind: UserRules.Kind) {
        this.kind = kind
        binding.segmentBlock.isSelected = kind == UserRules.Kind.BLOCK
        binding.segmentAllow.isSelected = kind == UserRules.Kind.ALLOW
        binding.rulesFooter.setText(
            if (kind == UserRules.Kind.BLOCK) R.string.rules_footer_block else R.string.rules_footer_allow
        )
        showRules()
    }

    private fun showRules() {
        val rules = UserRules.list(kind)
        val card = binding.rulesCard
        card.removeAllViews()
        for ((i, domain) in rules.withIndex()) {
            val row = ItemValueRowBinding.inflate(layoutInflater, card, true)
            row.title.text = domain
            row.separator.isVisible = i != rules.lastIndex
            row.row.setOnClickListener { confirmDelete(domain) }
        }
        card.isVisible = rules.isNotEmpty()
        binding.emptyText.isVisible = rules.isEmpty()
    }

    private fun showAddDialog() {
        showInputDialog(
            title = if (kind == UserRules.Kind.BLOCK) R.string.rule_add_title_block else R.string.rule_add_title_allow,
            hint = R.string.rule_hint,
            // IP アドレスはブロックにだけ使える (許可しても意味が無い)
            error = if (kind == UserRules.Kind.BLOCK) R.string.rule_invalid else R.string.rule_invalid_allow,
            parse = { UserRules.normalize(it, allowIp = kind == UserRules.Kind.BLOCK) },
        ) { domain ->
            UserRules.add(this, kind, domain)
            showRules()
        }
    }

    private fun showCheckDialog() {
        showInputDialog(
            title = R.string.diag_title,
            hint = R.string.diag_hint,
            error = R.string.diag_invalid,
            positive = R.string.action_check,
            // パターンや IP アドレスは調べられない (問い合わせの名前は、ふつうのドメイン)
            parse = { UserRules.normalize(it, allowIp = false)?.takeIf { name -> '*' !in name } },
        ) { domain -> DomainActions.explain(this, domain) }
    }

    /** ブロックと許可のルールを、テキストのファイルに書き出す。 */
    private fun exportTo(uri: Uri) {
        val text = RuleText.format(UserRules.list(UserRules.Kind.BLOCK), UserRules.list(UserRules.Kind.ALLOW))
        val ok = try {
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
        } catch (e: IOException) {
            false
        }
        Toast.makeText(this, if (ok) R.string.rules_exported else R.string.rules_export_failed, Toast.LENGTH_SHORT).show()
    }

    /** テキストのファイルからルールを足す。今あるルールは残す。ファイルを読むので別のスレッドで行う。 */
    private fun importFrom(uri: Uri) {
        val app = applicationContext
        thread {
            val parsed = try {
                contentResolver.openInputStream(uri)?.use { input ->
                    // 大きすぎるファイル (ブロックリストなど) は、途中までしか読まない
                    val buffer = ByteArray(MAX_IMPORT_BYTES)
                    var length = 0
                    while (length < buffer.size) {
                        val n = input.read(buffer, length, buffer.size - length)
                        if (n < 0) break
                        length += n
                    }
                    RuleText.parse(String(buffer, 0, length, Charsets.UTF_8))
                }
            } catch (e: IOException) {
                null
            }
            val added = parsed?.let { UserRules.merge(app, it.block, it.allow) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (parsed == null || added == null) {
                    Toast.makeText(this, R.string.rules_import_failed, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                showRules()
                val message = getString(R.string.rules_imported, added.first, added.second, parsed.skipped)
                MaterialAlertDialogBuilder(this)
                    .setMessage(if (parsed.tooMany) message + "\n" + getString(R.string.rules_import_too_many, RuleText.MAX_RULES) else message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun confirmDelete(domain: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.rule_delete_title, domain))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                UserRules.remove(this, kind, domain)
                showRules()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
