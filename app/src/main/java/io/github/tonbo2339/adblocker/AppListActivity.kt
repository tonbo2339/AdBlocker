package io.github.tonbo2339.adblocker

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.tonbo2339.adblocker.databinding.ActivityAppListBinding
import io.github.tonbo2339.adblocker.databinding.ItemAppBinding
import java.text.Collator
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** 広告ブロック (VPN) を通さないアプリを選ぶ画面。 */
class AppListActivity : AppCompatActivity() {

    private class AppEntry(val packageName: String, val label: String, val isSystem: Boolean, val info: ApplicationInfo)

    private lateinit var binding: ActivityAppListBinding
    private val adapter = AppAdapter()
    private var allApps: List<AppEntry> = emptyList()
    private lateinit var excluded: MutableSet<String>
    private lateinit var savedExcluded: Set<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityAppListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()

        savedExcluded = Prefs.excluded(this)
        excluded = savedExcluded.toMutableSet()

        binding.backButton.setOnClickListener { finish() }
        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = adapter
        binding.searchBox.doAfterTextChanged { applyFilter() }
        binding.systemAppsSwitch.setOnCheckedChangeListener { _, _ -> applyFilter() }

        val pinned = savedExcluded
        thread {
            val apps = loadApps(pinned)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                allApps = apps
                binding.progress.isVisible = false
                applyFilter()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        saveIfChanged()
    }

    private fun saveIfChanged() {
        if (excluded == savedExcluded) return
        Prefs.setExcluded(this, excluded)
        savedExcluded = excluded.toSet()
        // 動作中・開始中なら VPN を作り直して新しい例外設定を反映する (開始中に変えた分も取りこぼさない)
        // (開始できなくても、次に VPN を作るときに保存した設定が使われる)
        if (AdBlockVpnService.state != AdBlockVpnService.State.STOPPED) {
            try {
                AdBlockVpnService.start(this)
                Toast.makeText(this, R.string.exclusions_applied, Toast.LENGTH_SHORT).show()
            } catch (e: IllegalStateException) {
                Log.w("AppList", "restart failed", e)
            }
        }
    }

    /** バックグラウンドで呼ぶ。pinned (例外にしているアプリ) を先頭に並べる。 */
    private fun loadApps(pinned: Set<String>): List<AppEntry> {
        val pm = packageManager
        val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }
        return infos
            .filter { it.packageName != packageName }
            .map {
                val system = it.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                    it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
                AppEntry(it.packageName, it.loadLabel(pm).toString(), system, it)
            }
            // 名前は言語の並び順で (日本語ならかな、英語なら大文字小文字を区別しないアルファベット順)
            .sortedWith(compareBy<AppEntry> { it.packageName !in pinned }.thenBy(Collator.getInstance()) { it.label })
    }

    private fun applyFilter() {
        val query = binding.searchBox.text.toString().trim().lowercase()
        val showSystem = binding.systemAppsSwitch.isChecked
        adapter.submit(
            allApps.filter { app ->
                (showSystem || !app.isSystem || app.packageName in excluded) &&
                    (query.isEmpty() || app.label.lowercase().contains(query) || app.packageName.contains(query))
            }
        )
    }

    override fun onDestroy() {
        iconLoader.shutdownNow()
        super.onDestroy()
    }

    /** アイコンの読み込みはディスクを読むので、表示する行の分だけバックグラウンドで読む。 */
    private val iconLoader = Executors.newSingleThreadExecutor()

    private inner class AppAdapter : RecyclerView.Adapter<AppAdapter.Holder>() {
        private var items: List<AppEntry> = emptyList()
        private val iconCache = HashMap<String, Drawable>() // メインスレッドからだけ触る

        private fun bindIcon(holder: Holder, app: AppEntry) {
            val view = holder.b.appIcon
            view.tag = app.packageName
            iconCache[app.packageName]?.let {
                view.setImageDrawable(it)
                return
            }
            view.setImageDrawable(null)
            iconLoader.execute {
                val icon = app.info.loadIcon(packageManager)
                runOnUiThread {
                    iconCache[app.packageName] = icon
                    // 読み込み中に別のアプリの行として再利用されていたら差し替えない
                    if (view.tag == app.packageName) view.setImageDrawable(icon)
                }
            }
        }

        inner class Holder(val b: ItemAppBinding) : RecyclerView.ViewHolder(b.root) {
            init {
                // 押したときの灰色のハイライトを、先頭・末尾の行の角丸で切り抜く
                b.root.clipToOutline = true
                // スイッチ自体はタッチを受けず (clickable=false)、行全体のタップで切り替える
                b.root.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                    val pkg = items[pos].packageName
                    if (!excluded.remove(pkg)) excluded.add(pkg)
                    b.excludedSwitch.isChecked = pkg in excluded
                }
            }
        }

        @Suppress("NotifyDataSetChanged")
        fun submit(list: List<AppEntry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = items[position]
            val b = holder.b
            b.appLabel.text = app.label
            b.appPackage.text = app.packageName
            bindIcon(holder, app)
            b.excludedSwitch.isChecked = app.packageName in excluded
            b.excludedSwitch.jumpDrawablesToCurrentState() // 再利用時にアニメーションさせない

            val last = position == items.lastIndex
            b.root.setGroupedRowBackground(first = position == 0, last = last)
            b.separator.isVisible = !last
        }
    }
}
