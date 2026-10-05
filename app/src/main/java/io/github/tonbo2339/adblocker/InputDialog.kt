package io.github.tonbo2339.adblocker

import android.app.Activity
import android.content.DialogInterface
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tonbo2339.adblocker.databinding.DialogRuleBinding

/**
 * 1 行だけ入力するダイアログ (ルールの追加、リストの URL の追加)。
 * parse が null を返したら、閉じずに error を表示する。正しければ onValid に整えた値を渡して閉じる。
 */
fun Activity.showInputDialog(
    title: Int,
    hint: Int,
    error: Int,
    parse: (String) -> String?,
    onValid: (String) -> Unit,
) {
    val input = DialogRuleBinding.inflate(layoutInflater)
    input.domainInput.setHint(hint)
    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setView(input.root)
        .setPositiveButton(R.string.action_add, null)
        .setNegativeButton(R.string.action_cancel, null)
        .create()
    // 正しくない入力ではダイアログを閉じずにエラーを出すため、ボタンの動作を差し替える
    val submit = {
        val value = parse(input.domainInput.text.toString())
        if (value == null) {
            input.domainInput.error = getString(error)
        } else {
            onValid(value)
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
