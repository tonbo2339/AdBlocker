package io.github.tonbo2339.adblocker

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tonbo2339.adblocker.databinding.ActivityRulesBinding
import io.github.tonbo2339.adblocker.databinding.ItemValueRowBinding

/** 自分で追加するブロック / 許可のルール。 */
class RulesActivity : AppCompatActivity() {

    private companion object {
        const val KEY_KIND = "kind"
    }

    private lateinit var binding: ActivityRulesBinding
    private var kind = UserRules.Kind.BLOCK

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
            error = R.string.rule_invalid,
            parse = UserRules::normalize,
        ) { domain ->
            UserRules.add(this, kind, domain)
            showRules()
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
