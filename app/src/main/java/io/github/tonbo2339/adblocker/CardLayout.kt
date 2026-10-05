package io.github.tonbo2339.adblocker

import android.content.Context
import android.util.AttributeSet
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
