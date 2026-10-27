package io.github.tonbo2339.adblocker

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tonbo2339.adblocker.block.BlockList
import io.github.tonbo2339.adblocker.block.DomainCheck
import io.github.tonbo2339.adblocker.block.UserRules
import io.github.tonbo2339.adblocker.databinding.DialogDomainBinding
import kotlin.concurrent.thread

/** ログやよくブロックしたドメインの行をタップしたときのメニュー (理由・ブロック / 許可 / ルールの削除 / コピー)。 */
object DomainActions {

    /**
     * blocked: 今ブロックされているか (CNAME 隠しはリストを見ても分からないので、ログからは結果を渡す)。
     * 先頭に、ブロックされる (されない) 理由を出す。リストのファイルを読むので、開いてから別のスレッドで調べて差し替える。
     */
    fun show(activity: Activity, domain: String, blocked: Boolean = BlockList.isBlocked(domain)) {
        val actions = mutableListOf<Action>()
        val owner = UserRules.ruleOwner(domain)
        if (owner != null) {
            // 親ドメインのルールが効いている場合もあるので、効いているルールそのものを外す
            val (kind, ruleDomain) = owner
            actions += Action(activity.getString(R.string.action_remove_rule, ruleDomain), destructive = true) {
                UserRules.remove(activity, kind, ruleDomain)
                toast(activity, activity.getString(R.string.rule_removed))
            }
        } else {
            val kind = if (blocked) UserRules.Kind.ALLOW else UserRules.Kind.BLOCK
            val label = if (kind == UserRules.Kind.ALLOW) R.string.action_allow else R.string.action_block
            actions += Action(activity.getString(label)) {
                UserRules.add(activity, kind, domain)
                val done = if (kind == UserRules.Kind.ALLOW) R.string.rule_added_allow else R.string.rule_added_block
                toast(activity, activity.getString(done, domain))
            }
        }
        actions += Action(activity.getString(R.string.action_copy)) {
            val clipboard = activity.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText(domain, domain))
            // Android 13 以上は OS がコピーしたことを表示する
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast(activity, activity.getString(R.string.copied))
        }

        val view = DialogDomainBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(domain)
            .setView(view.root)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        for (action in actions) view.actions.addView(actionRow(activity, action) { dialog.dismiss(); action.run() })
        dialog.show()

        val app = activity.applicationContext
        thread {
            val result = DomainCheck.check(app, domain)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || !dialog.isShowing) return@runOnUiThread
                view.reason.text = headline(activity, result)
                view.reason.setTextColor(activity.getColor(R.color.label_secondary))
            }
        }
    }

    private class Action(val label: String, val destructive: Boolean = false, val run: () -> Unit)

    /** ダイアログの操作の 1 行 (タップで onClick)。 */
    private fun actionRow(activity: Activity, action: Action, onClick: () -> Unit): TextView {
        val density = activity.resources.displayMetrics.density
        return TextView(activity).apply {
            text = action.label
            textSize = 17f
            setTextColor(activity.getColor(if (action.destructive) R.color.sys_red else R.color.accent_blue))
            gravity = Gravity.CENTER_VERTICAL
            minHeight = (48 * density).toInt()
            setPadding((24 * density).toInt(), 0, (24 * density).toInt(), 0)
            foreground = activity.getDrawable(R.drawable.bg_row_pressed_overlay)
            setOnClickListener { onClick() }
        }
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

    /** 「ブロックされます」の見出し (太字) と、続けて理由。 */
    private fun headline(context: Context, r: DomainCheck.Result): CharSequence {
        val head = context.getString(if (r.blocked) R.string.diag_blocked_short else R.string.diag_not_blocked_short)
        val text = SpannableStringBuilder(head)
        text.setSpan(StyleSpan(Typeface.BOLD), 0, head.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(ForegroundColorSpan(context.getColor(R.color.label)), 0, head.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.append("\n").append(explanation(context, r))
        return text
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
