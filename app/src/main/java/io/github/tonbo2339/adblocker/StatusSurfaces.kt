package io.github.tonbo2339.adblocker

import android.content.Context

/** アプリの外に出ている状態の表示 (クイック設定のタイル・ホーム画面のウィジェット) をまとめて更新する。 */
object StatusSurfaces {
    fun refresh(context: Context) {
        AdBlockTileService.refresh(context)
        AdBlockWidget.refresh(context)
    }
}
