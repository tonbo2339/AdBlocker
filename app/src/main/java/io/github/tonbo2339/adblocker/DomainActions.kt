package io.github.tonbo2339.adblocker

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.concurrent.thread

/** ログやよくブロックしたドメインの行をタップしたときのメニュー (ブロック / 許可 / ルールの削除 / コピー)。 */
object DomainActions {

    /** blocked: 今ブロックされているか (CNAME 隠しはリストを見ても分からないので、ログからは結果を渡す)。 */
    fun show(activity: Activity, domain: String, blocked: Boolean = BlockList.isBlocked(domain)) {
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
            val kind = if (blocked) UserRules.Kind.ALLOW else UserRules.Kind.BLOCK
            val label = if (kind == UserRules.Kind.ALLOW) R.string.action_allow else R.string.action_block
            actions += activity.getString(label) to {
                UserRules.add(activity, kind, domain)
                val done = if (kind == UserRules.Kind.ALLOW) R.string.rule_added_allow else R.string.rule_added_block
                toast(activity, activity.getString(done, domain))
            }
        }
        actions += activity.getString(R.string.action_check_domain) to { explain(activity, domain) }
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

    /** なぜブロックされる (されない) のかを調べて、結果を出す。リストのファイルを読むので別のスレッドで調べる。 */
    fun explain(activity: Activity, domain: String) {
        val app = activity.applicationContext
        thread {
            val result = DomainCheck.check(app, domain)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                MaterialAlertDialogBuilder(activity)
                    .setTitle(activity.getString(if (result.blocked) R.string.diag_blocked else R.string.diag_not_blocked, domain))
                    .setMessage(explanation(activity, result))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    /** 結果の理由を 1 行ずつ並べた文。 */
    private fun explanation(context: Context, r: DomainCheck.Result): String {
        val lines = mutableListOf<String>()
        if (r.paused) lines += context.getString(R.string.diag_paused)
        if (r.wifiUnblocked) lines += context.getString(R.string.diag_wifi)
        r.userRule?.let { (kind, rule) ->
            lines += context.getString(if (kind == UserRules.Kind.ALLOW) R.string.diag_user_allow else R.string.diag_user_block, rule)
        }
        r.builtinAllowed?.let { lines += context.getString(R.string.diag_builtin_allow, it) }
        for (hit in r.hits) {
            lines += context.getString(if (hit.allow) R.string.diag_list_allow else R.string.diag_list_block, hit.source, hit.domain)
        }
        if (lines.isEmpty()) lines += context.getString(R.string.diag_no_match)
        if (!r.blocked) lines += context.getString(R.string.diag_cname_note)
        return lines.joinToString("\n")
    }

    private fun toast(activity: Activity, text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    }
}
