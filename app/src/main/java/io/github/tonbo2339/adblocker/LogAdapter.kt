package io.github.tonbo2339.adblocker

import android.app.Activity
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
import io.github.tonbo2339.adblocker.databinding.ItemLogBinding
import io.github.tonbo2339.adblocker.databinding.ItemLogHeaderBinding
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** ログのタブ。1 行目が見出し (タイトル・検索・絞り込み)、2 行目以降が問い合わせ (新しい順)。 */
class LogAdapter(private val activity: Activity) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ENTRY = 1
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** 表示中の見出し (スクロールで画面外に出て作り直されると差し替わる)。 */
    var header: ItemLogHeaderBinding? = null
        private set
    private var entries: List<QueryLog.Entry> = emptyList()
    private var shownVersion = -1L
    private var query = ""
    private var blockedOnly = false

    /** ログが変わっていれば表示し直す。force なら変わっていなくても (絞り込みを変えたとき・設定から戻ったとき)。 */
    fun refresh(force: Boolean = false) {
        val version = QueryLog.version
        if (!force && version == shownVersion) return
        shownVersion = version
        val q = query
        val list = QueryLog.snapshot().filter { e ->
            (!blockedOnly || e.verdict.blocked) && (q.isEmpty() || e.domain.contains(q))
        }
        val old = entries.size
        entries = list
        // 見出しは作り直さない (検索欄の入力中にフォーカスやキーボードが外れないように)
        val new = list.size
        if (new > old) notifyItemRangeInserted(1 + old, new - old)
        if (new < old) notifyItemRangeRemoved(1 + new, old - new)
        if (minOf(old, new) > 0) notifyItemRangeChanged(1, minOf(old, new))
        updateHeaderTexts()
    }

    private fun updateHeaderTexts() {
        val h = header ?: return
        h.filterAll.isSelected = !blockedOnly
        h.filterBlocked.isSelected = blockedOnly
        val enabled = QueryLog.enabled
        h.logFooter.isVisible = enabled
        h.logFooter.text = activity.getString(
            R.string.log_footer, NumberFormat.getIntegerInstance().format(QueryLog.CAPACITY),
        )
        h.emptyText.isVisible = entries.isEmpty()
        h.emptyText.setText(
            when {
                !enabled -> R.string.log_disabled
                query.isNotEmpty() || blockedOnly -> R.string.log_no_results
                else -> R.string.log_empty
            }
        )
    }

    override fun getItemCount() = 1 + entries.size

    override fun getItemViewType(position: Int) = if (position == 0) TYPE_HEADER else TYPE_ENTRY

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        if (viewType == TYPE_HEADER) {
            val h = ItemLogHeaderBinding.inflate(inflater, parent, false)
            header = h
            // 作り直された見出しにも、今の検索語を入れておく (表示と絞り込みがずれないように)
            h.searchBox.setText(query)
            h.searchBox.doAfterTextChanged {
                val q = it.toString().trim().lowercase()
                if (q == query) return@doAfterTextChanged
                query = q
                refresh(force = true)
            }
            h.filterAll.setOnClickListener { setBlockedOnly(false) }
            h.filterBlocked.setOnClickListener { setBlockedOnly(true) }
            updateHeaderTexts()
            return object : RecyclerView.ViewHolder(h.root) {}
        }
        return EntryHolder(ItemLogBinding.inflate(inflater, parent, false))
    }

    private fun setBlockedOnly(value: Boolean) {
        blockedOnly = value
        refresh(force = true)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder !is EntryHolder) return
        val index = position - 1
        val e = entries[index]
        val b = holder.b
        b.domain.text = e.domain
        val verdict = activity.getString(
            when (e.verdict) {
                Verdict.LIST -> R.string.verdict_list
                Verdict.USER_BLOCK -> R.string.verdict_user_block
                Verdict.USER_ALLOW -> R.string.verdict_user_allow
                Verdict.PAUSED -> R.string.verdict_paused
                Verdict.PASS -> R.string.verdict_pass
            }
        )
        b.detail.text = if (e.count > 1) activity.getString(R.string.log_detail_count, verdict, e.count) else verdict
        b.time.text = timeFormat.format(Date(e.time))
        b.dot.backgroundTintList = ColorStateList.valueOf(
            activity.getColor(
                when (e.verdict) {
                    Verdict.LIST, Verdict.USER_BLOCK -> R.color.sys_red
                    Verdict.USER_ALLOW -> R.color.sys_green
                    Verdict.PAUSED -> R.color.sys_orange
                    Verdict.PASS -> R.color.status_off
                }
            )
        )
        val last = index == entries.lastIndex
        b.root.setGroupedRowBackground(first = index == 0, last = last)
        b.separator.isVisible = !last
    }

    private inner class EntryHolder(val b: ItemLogBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            // 押したときのハイライトを、先頭・末尾の行の角丸で切り抜く
            b.root.clipToOutline = true
            b.root.setOnClickListener {
                val entry = entries.getOrNull(bindingAdapterPosition - 1) ?: return@setOnClickListener
                DomainActions.show(activity, entry.domain)
            }
        }
    }
}
