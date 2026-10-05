package io.github.tonbo2339.adblocker

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** ログやよくブロックしたドメインの行をタップしたときのメニュー (ブロック / 許可 / ルールの削除 / コピー)。 */
object DomainActions {

    fun show(activity: Activity, domain: String) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        val owner = UserRules.ruleOwner(domain)
        if (owner != null) {
            // 親ドメインのルールが効いている場合もあるので、効いているルールそのものを外す
            val (kind, ruleDomain) = owner
            actions += activity.getString(R.string.action_remove_rule, ruleDomain) to {
                UserRules.remove(activity, kind, ruleDomain)
                toast(activity, activity.getString(R.string.rule_removed))
            }
        } else {
            val kind = if (BlockList.isBlocked(domain)) UserRules.Kind.ALLOW else UserRules.Kind.BLOCK
            val label = if (kind == UserRules.Kind.ALLOW) R.string.action_allow else R.string.action_block
            actions += activity.getString(label) to {
                UserRules.add(activity, kind, domain)
                val done = if (kind == UserRules.Kind.ALLOW) R.string.rule_added_allow else R.string.rule_added_block
                toast(activity, activity.getString(done, domain))
            }
        }
        actions += activity.getString(R.string.action_copy) to {
            val clipboard = activity.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText(domain, domain))
            // Android 13 以上は OS がコピーしたことを表示する
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast(activity, activity.getString(R.string.copied))
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(domain)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun toast(activity: Activity, text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    }
}
