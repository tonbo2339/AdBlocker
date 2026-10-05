package io.github.tonbo2339.adblocker

import android.content.Context
import io.github.tonbo2339.adblocker.Prefs.NotificationKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.concurrent.thread

/**
 * 設定の書き出しと読み込み (端末の引っ越しや、入れ直したとき用)。
 * 自分のルール・例外アプリ・設定を JSON にする。ON/OFF や統計、ダウンロードしたリストは含めない。
 */
object SettingsBackup {
    private const val APP = "AdBlocker"
    private const val FORMAT = 1

    fun export(context: Context): String {
        val settings = JSONObject()
            .put("query_log", Prefs.queryLogEnabled(context))
            .put("update_on_wifi_only", Prefs.updateOnWifiOnly(context))
            .put("auto_install_updates", Prefs.autoInstallUpdates(context))
            .put("encrypted_dns", Prefs.encryptedDns(context)?.id ?: JSONObject.NULL)
            .put("notifications", JSONObject().apply {
                for (kind in NotificationKind.entries) put(kind.key, Prefs.isNotificationEnabled(context, kind))
            })
            .put("blocklists", JSONObject().apply {
                for (source in BlockListUpdater.BUILT_IN) put(source.id, Prefs.isSourceEnabled(context, source))
            })
            .put("custom_blocklists", JSONArray(Prefs.customSourceUrls(context).sorted()))
        return JSONObject()
            .put("app", APP)
            .put("format", FORMAT)
            .put("version", BuildConfig.VERSION_NAME)
            .put("rules", JSONObject()
                .put("blocked", JSONArray(UserRules.list(UserRules.Kind.BLOCK)))
                .put("allowed", JSONArray(UserRules.list(UserRules.Kind.ALLOW))))
            .put("excluded_apps", JSONArray(Prefs.excluded(context).sorted()))
            .put("settings", settings)
            .toString(2)
    }

    /** 読み込んで反映する。このアプリの書き出したファイルでなければ IOException。 */
    fun import(context: Context, text: String) {
        val root = try {
            JSONObject(text)
        } catch (e: org.json.JSONException) {
            throw IOException("not JSON", e)
        }
        if (root.optString("app") != APP || root.optInt("format") != FORMAT) throw IOException("not an AdBlocker backup")
        val app = context.applicationContext

        root.optJSONObject("rules")?.let { rules ->
            for (kind in UserRules.Kind.entries) {
                val domains = strings(rules.optJSONArray(kind.key)).mapNotNull { UserRules.normalize(it) }.toSet()
                UserRules.replace(app, kind, domains)
            }
        }
        val oldExcluded = Prefs.excluded(app)
        root.optJSONArray("excluded_apps")?.let { Prefs.setExcluded(app, strings(it).toSet()) }

        root.optJSONObject("settings")?.let { s ->
            if (s.has("query_log")) Prefs.setQueryLogEnabled(app, s.getBoolean("query_log"))
            if (s.has("update_on_wifi_only")) Prefs.setUpdateOnWifiOnly(app, s.getBoolean("update_on_wifi_only"))
            if (s.has("auto_install_updates")) Prefs.setAutoInstallUpdates(app, s.getBoolean("auto_install_updates"))
            if (s.has("encrypted_dns")) Prefs.setEncryptedDns(app, EncryptedDns.of(s.optString("encrypted_dns")))
            s.optJSONObject("notifications")?.let { n ->
                for (kind in NotificationKind.entries) {
                    if (n.has(kind.key)) Prefs.setNotificationEnabled(app, kind, n.getBoolean(kind.key))
                }
            }
            s.optJSONObject("blocklists")?.let { b ->
                for (source in BlockListUpdater.BUILT_IN) {
                    if (b.has(source.id)) Prefs.setSourceEnabled(app, source, b.getBoolean(source.id))
                }
            }
            s.optJSONArray("custom_blocklists")?.let { urls ->
                Prefs.setCustomSourceUrls(app, strings(urls).filter { it.startsWith("https://") }.toSet())
            }
        }

        // 反映: 自動更新の条件、常駐通知、例外アプリ (VPN を作り直す)、ブロックリスト
        BlockListWorker.schedule(app)
        AppUpdateWorker.schedule(app)
        AdBlockVpnService.refreshNotification(app)
        if (Prefs.excluded(app) != oldExcluded && AdBlockVpnService.isRunning) AdBlockVpnService.start(app, rebuild = true)
        thread {
            BlockListUpdater.applySelection(app)
            if (BlockListUpdater.hasMissing(app)) BlockListWorker.runNow(app)
        }
    }

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
}
