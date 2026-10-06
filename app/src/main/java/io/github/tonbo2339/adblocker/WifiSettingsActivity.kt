package io.github.tonbo2339.adblocker

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tonbo2339.adblocker.WifiNetworks.Permission

/**
 * 広告ブロックをしない Wi-Fi: 今の Wi-Fi の追加、名前での追加、一覧 (タップで削除)、位置情報の許可。
 * Wi-Fi の名前を読むには位置情報の許可が要る。画面を閉じている間も切り替えるには「常に許可」。
 */
class WifiSettingsActivity : SettingsPageActivity() {
    private companion object {
        const val NO_DIALOG_MS = 500L
    }

    override val pageTitle = R.string.title_unblocked_wifi

    private lateinit var permissionRow: SettingRow
    private lateinit var permissionFooter: TextView
    private lateinit var currentRow: SettingRow
    private lateinit var addCurrentRow: SettingRow
    private lateinit var listCard: SettingsBuilder.Card
    private lateinit var monitor: SsidMonitor

    /** 許可を尋ねた時刻。これより NO_DIALOG_MS 以内に断られたら、ダイアログは出ていない。 */
    private var requestedAt = 0L

    /** この画面で見ている、今つながっている Wi-Fi の名前。 */
    private var current: String? = null

    private val foregroundPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            permissionChanged()
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                askAlways()
            } else if (SystemClock.elapsedRealtime() - requestedAt < NO_DIALOG_MS) {
                // すぐに断られた = 2 回断ったなどで、もうダイアログを出せない。アプリの設定で許可してもらう
                // (ダイアログを閉じただけのときは何もしない)
                openAppSettings()
            }
        }

    private val backgroundPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { permissionChanged() }

    override fun SettingsBuilder.build(savedInstanceState: Bundle?) {
        card(spaced = true) {
            permissionRow = link(R.string.row_location_permission, R.drawable.ic_g_lock, R.color.accent_blue) { requestPermission() }
        }
        permissionFooter = footer()

        header(R.string.section_current_wifi)
        card {
            currentRow = info(R.string.row_current_wifi, R.drawable.ic_g_wifi, R.color.accent_blue)
            addCurrentRow = action(R.string.wifi_add_current) { current?.let { add(it) } }
        }

        header(R.string.section_unblocked_wifi)
        listCard = card {}
        footer(R.string.unblocked_wifi_footer)

        // 画面を開いている間だけ見る (VPN サービスとは別に。VPN が止まっていても今の Wi-Fi を出す)
        monitor = SsidMonitor(this@WifiSettingsActivity) { ssid ->
            runOnUiThread {
                current = ssid
                if (!isDestroyed) refresh()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        monitor.start()
    }

    override fun onStop() {
        monitor.stop()
        super.onStop()
    }

    override fun refresh() {
        val permission = WifiNetworks.permission(this)
        permissionRow.value = getString(
            when (permission) {
                Permission.NONE -> R.string.location_permission_none
                Permission.WHILE_IN_USE -> R.string.location_permission_while_in_use
                Permission.ALWAYS -> R.string.location_permission_always
            }
        )
        permissionFooter.setText(if (permission == Permission.ALWAYS) R.string.wifi_permission_footer_ok else R.string.wifi_permission_footer)

        currentRow.value = current ?: getString(
            when {
                permission == Permission.NONE -> R.string.wifi_name_needs_permission
                !WifiNetworks.locationEnabled(this) -> R.string.wifi_name_location_off
                else -> R.string.wifi_not_connected
            }
        )
        val ssid = current
        // 読めない・追加済みなら押せなくする (行を隠すと区切り線だけが残る)
        val canAdd = ssid != null && ssid !in WifiNetworks.list
        addCurrentRow.binding.row.isEnabled = canAdd
        addCurrentRow.binding.title.alpha = if (canAdd) 1f else 0.4f

        listCard.clear()
        for (name in WifiNetworks.list.sortedWith(String.CASE_INSENSITIVE_ORDER)) {
            listCard.choice(name, checked = false) { confirmRemove(name) }
        }
        listCard.action(R.string.wifi_add_by_name) {
            showInputDialog(R.string.wifi_add_title, R.string.wifi_name_hint, R.string.wifi_name_invalid, ::parseSsid) { add(it) }
        }
    }

    /** SSID は 1〜32 バイト。 */
    private fun parseSsid(text: String): String? {
        val name = text.trim()
        return name.takeIf { it.isNotEmpty() && it.toByteArray().size <= 32 }
    }

    private fun add(ssid: String) {
        WifiNetworks.add(this, ssid)
        refresh()
        // 画面を閉じている間も切り替えるには「常に許可」が要る
        if (WifiNetworks.permission(this) != Permission.ALWAYS) requestPermission()
    }

    private fun confirmRemove(ssid: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.wifi_remove_title, ssid))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                WifiNetworks.remove(this, ssid)
                refresh()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun requestPermission() {
        when (WifiNetworks.permission(this)) {
            Permission.NONE -> {
                requestedAt = SystemClock.elapsedRealtime()
                foregroundPermission.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            }
            Permission.WHILE_IN_USE -> askAlways()
            // 許可をやめるときなど
            Permission.ALWAYS -> openAppSettings()
        }
    }

    /** 「常に許可」が要る理由を説明してから尋ねる (Android 11 以上は、位置情報の設定画面が開く)。 */
    private fun askAlways() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || WifiNetworks.permission(this) == Permission.ALWAYS) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.location_always_title)
            .setMessage(R.string.location_always_message)
            .setPositiveButton(R.string.location_always_open) { _, _ ->
                backgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    /** 許可が変わったら、この画面と VPN サービスの両方で Wi-Fi の名前を読み直す。 */
    private fun permissionChanged() {
        shownPermission = WifiNetworks.permission(this)
        monitor.stop()
        monitor.start()
        AdBlockVpnService.refreshWifi(this)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // アプリの設定画面で許可を変えて戻ってきたとき
        val permission = WifiNetworks.permission(this)
        if (permission != shownPermission) {
            if (shownPermission != null) permissionChanged()
            shownPermission = permission
        }
    }

    private var shownPermission: Permission? = null
}
