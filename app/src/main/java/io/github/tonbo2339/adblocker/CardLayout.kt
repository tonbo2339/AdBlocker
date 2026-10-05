package io.github.tonbo2339.adblocker

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout

/**
 * 角丸カード (iOS の inset grouped セクション)。
 * 中の行を押したときの灰色のハイライトが角からはみ出さないよう、背景の形で切り抜く。
 */
class CardLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    init {
        clipToOutline = true
    }
}

/** iOS の inset grouped リストのように、一覧の先頭と末尾の行だけ角を丸めた背景にする。 */
fun View.setGroupedRowBackground(first: Boolean, last: Boolean) {
    setBackgroundResource(
        when {
            first && last -> R.drawable.bg_row_single
            first -> R.drawable.bg_row_top
            last -> R.drawable.bg_row_bottom
            else -> R.drawable.bg_row_middle
        }
    )
}
