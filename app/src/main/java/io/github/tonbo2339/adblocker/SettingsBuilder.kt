package io.github.tonbo2339.adblocker

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isEmpty
import androidx.core.view.isVisible
import io.github.tonbo2339.adblocker.databinding.ActivitySettingsPageBinding
import io.github.tonbo2339.adblocker.databinding.ItemSettingRowBinding

/** 設定の 1 行。値・チェック・進み具合・押せるかを、作った後から変えられる。 */
class SettingRow(val binding: ItemSettingRowBinding) {
    var value: CharSequence?
        get() = binding.value.text
        set(v) {
            binding.value.text = v
            binding.value.isVisible = !v.isNullOrEmpty()
        }

    var title: CharSequence
        get() = binding.title.text
        set(v) {
            binding.title.text = v
        }

    /** チェックマーク (選んでいる項目)。 */
    var checked: Boolean
        get() = binding.check.isVisible
        set(v) {
            binding.check.isVisible = v
        }

    /** 処理中の表示。処理中は押せなくする。 */
    var busy: Boolean
        get() = binding.progress.isVisible
        set(v) {
            binding.progress.isVisible = v
            binding.row.isEnabled = !v
        }
}

/**
 * iOS の設定アプリと同じ「見出し・角丸のカード・説明」の並びを、コードで組み立てる。
 * 行の種類: 別のページへ (link)、スイッチ (toggle)、チェックで選ぶ (choice)、青い文字のボタン (action)、値を見るだけ (info)。
 */
class SettingsBuilder(private val activity: Activity, private val content: LinearLayout) {

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.resources.displayMetrics).toInt()

    fun header(@StringRes text: Int): TextView {
        val view = TextView(activity, null, 0, R.style.SectionHeader).apply { setText(text) }
        content.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(24f)
        })
        return view
    }

    /** カードの下の説明。後から文言を変えられるように TextView を返す。 */
    fun footer(text: CharSequence = ""): TextView {
        val view = TextView(activity, null, 0, R.style.SectionFooter).apply { this.text = text }
        content.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return view
    }

    fun footer(@StringRes text: Int): TextView = footer(activity.getString(text))

    /** 角丸のカード。見出しの無いカードは spaced で上を空ける。 */
    fun card(spaced: Boolean = false, build: Card.() -> Unit): Card {
        val layout = CardLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
        }
        content.addView(layout, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            if (spaced) topMargin = dp(20f)
        })
        return Card(layout).apply(build)
    }

    inner class Card(val layout: LinearLayout) {

        /** 2 行目からは区切り線を入れる (アイコンのある行は、アイコンの右から)。 */
        private fun separator(withIcon: Boolean) {
            if (layout.isEmpty()) return
            val line = View(activity).apply { setBackgroundColor(activity.getColor(R.color.separator)) }
            layout.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(0.5f)).apply {
                marginStart = dp(if (withIcon) 58f else 16f)
            })
        }

        /** 中身を空にする (一覧を作り直すとき)。 */
        fun clear() = layout.removeAllViews()

        /** 決まった形でない行 (ブロックリストの 2 行の行など) を足す。 */
        fun custom(view: View) {
            separator(withIcon = false)
            layout.addView(view)
        }

        private fun add(@StringRes title: Int?, @DrawableRes icon: Int?, @ColorRes color: Int): SettingRow {
            separator(withIcon = icon != null)
            val b = ItemSettingRowBinding.inflate(activity.layoutInflater, layout, true)
            if (title != null) b.title.setText(title)
            if (icon != null) {
                b.icon.setImageResource(icon)
                b.icon.backgroundTintList = ColorStateList.valueOf(activity.getColor(color))
            } else {
                b.icon.isVisible = false
            }
            b.value.isVisible = false
            b.progress.isVisible = false
            b.toggle.isVisible = false
            b.check.isVisible = false
            b.chevron.isVisible = false
            return SettingRow(b)
        }

        private fun clickable(row: SettingRow, onClick: () -> Unit) {
            row.binding.row.setBackgroundResource(R.drawable.bg_row_pressed)
            row.binding.row.setOnClickListener { onClick() }
        }

        /** 別のページを開く行 (値と「>」)。 */
        fun link(@StringRes title: Int, @DrawableRes icon: Int, @ColorRes color: Int, onClick: () -> Unit): SettingRow =
            add(title, icon, color).also { row ->
                row.binding.chevron.isVisible = true
                clickable(row, onClick)
            }

        /** スイッチの行。行のどこを押しても切り替わる。onChange は利用者が切り替えたときだけ呼ばれる。 */
        fun toggle(
            @StringRes title: Int,
            @DrawableRes icon: Int,
            @ColorRes color: Int,
            checked: Boolean,
            onChange: (Boolean) -> Unit,
        ): SettingRow = add(title, icon, color).also { row ->
            val toggle = row.binding.toggle
            toggle.isVisible = true
            // 値を入れてからリスナーを付ける (付けた後だと、値を入れただけで設定を書き直してしまう)
            toggle.isChecked = checked
            toggle.jumpDrawablesToCurrentState()
            toggle.setOnCheckedChangeListener { _, value -> onChange(value) }
            clickable(row) { toggle.toggle() }
        }

        /** チェックで選ぶ行 (同じカードの中から 1 つ)。 */
        fun choice(title: CharSequence, checked: Boolean, onClick: () -> Unit): SettingRow =
            add(null, null, R.color.sys_gray).also { row ->
                row.title = title
                row.checked = checked
                clickable(row, onClick)
            }

        /** 青い文字のボタンの行 (今すぐ更新・書き出しなど)。 */
        fun action(@StringRes title: Int, @DrawableRes icon: Int? = null, @ColorRes color: Int = R.color.sys_gray, onClick: () -> Unit): SettingRow =
            add(title, icon, color).also { row ->
                row.binding.title.setTextColor(activity.getColor(R.color.accent_blue))
                clickable(row, onClick)
            }

        /** 値を見るだけの行。 */
        fun info(@StringRes title: Int, @DrawableRes icon: Int? = null, @ColorRes color: Int = R.color.sys_gray): SettingRow =
            add(title, icon, color)
    }
}

/**
 * 設定のページの共通部分 (戻るボタン・大きなタイトル・中身)。中身は build() で SettingsBuilder を使って並べ、
 * 画面に戻るたびに refresh() で値を入れ直す。
 */
abstract class SettingsPageActivity : AppCompatActivity() {

    @get:StringRes
    protected abstract val pageTitle: Int

    /** 戻るボタンの文字 (戻り先の画面の名前)。 */
    @get:StringRes
    protected open val backLabel: Int = R.string.title_settings

    protected lateinit var page: ActivitySettingsPageBinding

    protected abstract fun SettingsBuilder.build(savedInstanceState: Bundle?)

    /** 表示している値を入れ直す (画面に戻ったときなど)。 */
    protected open fun refresh() {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        page = ActivitySettingsPageBinding.inflate(layoutInflater)
        setContentView(page.root)
        page.root.padForSystemBars()
        page.backButton.setOnClickListener { finish() }
        page.backLabel.setText(backLabel)
        page.pageTitle.setText(pageTitle)
        setTitle(pageTitle)
        SettingsBuilder(this, page.content).build(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }
}
