package io.github.tonbo2339.adblocker.settings

import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tonbo2339.adblocker.R
import io.github.tonbo2339.adblocker.data.Prefs
import io.github.tonbo2339.adblocker.databinding.DialogDnsServerBinding
import io.github.tonbo2339.adblocker.dns.CustomDns
import io.github.tonbo2339.adblocker.dns.EncryptedDns
import io.github.tonbo2339.adblocker.dns.EncryptedDnsMode
import io.github.tonbo2339.adblocker.vpn.AdBlockVpnService

/** DNS: 暗号化 DNS の転送先と、DNS を自分で指定するアプリへの対策。 */
class DnsSettingsActivity : SettingsPageActivity() {
    override val pageTitle = R.string.title_dns

    private val servers = listOf<EncryptedDns?>(null) + EncryptedDns.BUILT_IN
    private val serverRows = mutableListOf<SettingRow>()
    private lateinit var customRow: SettingRow
    private val modeRows = mutableListOf<SettingRow>()

    override fun SettingsBuilder.build(savedInstanceState: Bundle?) {
        header(R.string.row_encrypted_dns)
        card {
            for (server in servers) {
                serverRows += choice(server?.label ?: getString(R.string.encrypted_dns_off), false) {
                    // VPN を動かしたまま、次の問い合わせから切り替わる
                    Prefs.setEncryptedDns(this@DnsSettingsActivity, server)
                    refresh()
                }
            }
            // 自分で指定するサーバー。まだ無ければ入力のダイアログを開き、あれば選ぶ (直すときは行を長押し)
            customRow = choice(getString(R.string.encrypted_dns_custom), false) {
                val custom = Prefs.customDns(this@DnsSettingsActivity)
                if (custom == null) {
                    showCustomDialog()
                } else {
                    Prefs.setEncryptedDns(this@DnsSettingsActivity, custom)
                    refresh()
                }
            }
            customRow.binding.row.setOnLongClickListener {
                showCustomDialog()
                true
            }
        }
        footer(R.string.encrypted_dns_footer)

        header(R.string.row_encrypted_dns_mode)
        card {
            for (mode in EncryptedDnsMode.entries) {
                modeRows += choice(getString(modeTitle(mode)), false) {
                    Prefs.setEncryptedDnsMode(this@DnsSettingsActivity, mode)
                    refresh()
                }
            }
        }
        footer(R.string.encrypted_dns_mode_footer)

        card(spaced = true) {
            toggle(R.string.row_capture_dns, R.drawable.ic_g_shield, R.color.sys_red, Prefs.captureHardcodedDns(this@DnsSettingsActivity)) {
                Prefs.setCaptureHardcodedDns(this@DnsSettingsActivity, it)
                // VPN のルートを変えるので、動作中なら作り直す
                AdBlockVpnService.rebuildIfActive(this@DnsSettingsActivity)
            }
        }
        footer(R.string.capture_dns_footer)
    }

    override fun refresh() {
        val current = Prefs.encryptedDns(this)
        servers.forEachIndexed { i, server -> serverRows[i].checked = server == current }
        val custom = Prefs.customDns(this)
        customRow.value = custom?.host
        customRow.checked = custom != null && custom == current
        val mode = Prefs.encryptedDnsMode(this)
        EncryptedDnsMode.entries.forEachIndexed { i, m -> modeRows[i].checked = m == mode }
    }

    /** 自分で指定するサーバーの入力。保存したら、そのサーバーを選ぶ。 */
    private fun showCustomDialog() {
        val input = DialogDnsServerBinding.inflate(layoutInflater)
        val current = Prefs.customDns(this)
        current?.let {
            input.serverInput.setText(it.host + it.path.takeIf { p -> p != EncryptedDns.DEFAULT_PATH }.orEmpty())
            input.addressInput.setText(it.addresses.first())
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.encrypted_dns_custom_title)
            .setView(input.root)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
        if (current != null) {
            builder.setNeutralButton(R.string.action_delete) { _, _ ->
                Prefs.setCustomDns(this, null)
                refresh()
            }
        }
        val dialog = builder.create()
        // 正しくない入力ではダイアログを閉じずにエラーを出す
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val server = CustomDns.parse(input.serverInput.text.toString(), input.addressInput.text.toString())
                if (server == null) {
                    val needAddress = input.addressInput.text.isNullOrBlank()
                    (if (needAddress) input.addressInput else input.serverInput).error = getString(R.string.encrypted_dns_custom_invalid)
                } else {
                    Prefs.setCustomDns(this, server)
                    Prefs.setEncryptedDns(this, server)
                    refresh()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun modeTitle(mode: EncryptedDnsMode) = when (mode) {
        EncryptedDnsMode.TLS -> R.string.encrypted_dns_mode_tls
        EncryptedDnsMode.HTTPS -> R.string.encrypted_dns_mode_https
    }
}
