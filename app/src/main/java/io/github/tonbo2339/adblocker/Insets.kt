package io.github.tonbo2339.adblocker

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/** edge-to-edge 表示でステータスバー・ナビゲーションバー・ノッチと重ならないよう、元の padding に足す。 */
fun View.padForSystemBars() {
    val left = paddingLeft
    val top = paddingTop
    val right = paddingRight
    val bottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        v.updatePadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
        insets
    }
}
