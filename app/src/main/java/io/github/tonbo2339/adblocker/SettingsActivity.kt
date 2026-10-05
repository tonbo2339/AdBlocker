package io.github.tonbo2339.adblocker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/**
 * 設定のトップ (ホームの右上の ≡ から開く)。iOS の設定アプリと同じく、項目ごとのページへの入り口だけを並べる。
 *
 * - ブロック: ブロック・許可するドメイン / ブロックリスト / DNS
 * - ログ・アップデート・通知・バックアップ
 */
class SettingsActivity : SettingsPageActivity() {

    companion object {
        /** 「新しいバージョンがあります」の通知から開いたときに、アップデートのページで更新を確認する。 */
        private const val EXTRA_CHECK_UPDATE = "check_update"

        fun intent(context: Context, checkUpdate: Boolean = false): Intent =
            Intent(context, SettingsActivity::class.java).putExtra(EXTRA_CHECK_UPDATE, checkUpdate)
    }

    override val pageTitle = R.string.title_settings
    override val backLabel = R.string.app_name

    private lateinit var rules: SettingRow
    private lateinit var lists: SettingRow
    private lateinit var dns: SettingRow
    private lateinit var exclusions: SettingRow
    private lateinit var log: SettingRow
    private lateinit var updates: SettingRow

    override fun SettingsBuilder.build(savedInstanceState: Bundle?) {
        header(R.string.section_filtering)
        card {
            rules = link(R.string.title_rules, R.drawable.ic_g_rules, R.color.sys_indigo) { open(RulesActivity::class.java) }
            lists = link(R.string.title_blocklists, R.drawable.ic_g_list, R.color.sys_orange) { open(BlocklistsActivity::class.java) }
            dns = link(R.string.title_dns, R.drawable.ic_g_lock, R.color.sys_green) { open(DnsSettingsActivity::class.java) }
            exclusions = link(R.string.title_exclusions, R.drawable.ic_g_apps, R.color.accent_blue) { open(AppListActivity::class.java) }
        }

        header(R.string.section_vpn)
        card {
            // 端末によっては VPN 設定画面が無いので、ネットワーク設定 → 設定アプリの順に試す
            link(R.string.row_always_on, R.drawable.ic_g_shield, R.color.sys_indigo) {
                openSystemSettings(Settings.ACTION_VPN_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS)
            }
        }
        footer(R.string.always_on_footer)
        card(spaced = true) {
            log = link(R.string.title_log, R.drawable.ic_g_doc, R.color.sys_gray) { open(LogSettingsActivity::class.java) }
            updates = link(R.string.title_updates, R.drawable.ic_g_refresh, R.color.accent_blue) { open(UpdateSettingsActivity::class.java) }
            link(R.string.section_notifications, R.drawable.ic_g_bell, R.color.sys_red) { open(NotificationSettingsActivity::class.java) }
        }
        card(spaced = true) {
            link(R.string.section_backup, R.drawable.ic_g_download, R.color.sys_gray) { open(BackupSettingsActivity::class.java) }
        }
        footer(getString(R.string.settings_footer, BuildConfig.VERSION_NAME))

        // 画面の作り直し (回転など) では繰り返さない
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_CHECK_UPDATE, false)) {
            startActivity(UpdateSettingsActivity.intent(this@SettingsActivity, checkUpdate = true))
        }
    }

    private fun open(page: Class<*>) = startActivity(Intent(this, page))

    override fun refresh() {
        rules.value = getString(R.string.rules_value, UserRules.size)
        lists.value = getString(R.string.rules_value, BlockListUpdater.sources(this).size)
        dns.value = Prefs.encryptedDns(this)?.title ?: getString(R.string.encrypted_dns_off)
        exclusions.value = getString(R.string.exclusion_value, Prefs.installedExcluded(this).size)
        log.value = if (Prefs.queryLogEnabled(this)) logRetentionLabel(Prefs.logRetentionDays(this)) else getString(R.string.log_off_value)
        updates.value = updateTimeLabel()
    }
}

/** ログを残す期間の選択肢の名前 (「保存しない」「7 日」など)。 */
fun Context.logRetentionLabel(days: Int): String = getString(
    when (days) {
        1 -> R.string.log_retention_1
        7 -> R.string.log_retention_7
        30 -> R.string.log_retention_30
        else -> R.string.log_retention_memory
    }
)

/** 自動更新の時刻 (指定なしなら「指定なし」)。表示は端末の 12 / 24 時間の設定に合わせる。 */
fun Context.updateTimeLabel(): String =
    Prefs.updateTimeMinutes(this)?.let { minutes ->
        val time = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, minutes / 60)
            set(java.util.Calendar.MINUTE, minutes % 60)
        }
        android.text.format.DateFormat.getTimeFormat(this).format(time.time)
    } ?: getString(R.string.update_time_any)

/** 端末の設定画面を開く。端末によって無い画面があるので、開けるものが見つかるまで順に試す。 */
fun android.app.Activity.openSystemSettings(vararg actions: String) {
    for (action in actions) {
        try {
            startActivity(Intent(action))
            return
        } catch (_: android.content.ActivityNotFoundException) {
        }
    }
}
