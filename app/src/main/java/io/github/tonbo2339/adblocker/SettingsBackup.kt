package io.github.tonbo2339.adblocker

import android.content.Context
import io.github.tonbo2339.adblocker.Prefs.NotificationKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.concurrent.thread

/**
 * 設定の書き出しと読み込み (端末の引っ越しや、入れ直したとき用)。
 * ブロック・許可するドメイン、例外アプリ、設定を JSON にする。ON/OFF や統計、ダウンロードしたリストは含めない。
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
            .put("capture_hardcoded_dns", Prefs.captureHardcodedDns(context))
            .put("log_retention_days", Prefs.logRetentionDays(context))
            .put("update_time_minutes", Prefs.updateTimeMinutes(context) ?: JSONObject.NULL)
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
                // ファイルに無い種類は今のまま残す
                val array = rules.optJSONArray(kind.key) ?: continue
                val rules = strings(array).mapNotNull { UserRules.normalize(it, allowIp = kind == UserRules.Kind.BLOCK) }
                UserRules.replace(app, kind, rules.toSet())
            }
        }
        val oldExcluded = Prefs.excluded(app)
        val oldCapture = Prefs.captureHardcodedDns(app)
        val oldUpdateTime = Prefs.updateTimeMinutes(app)
        root.optJSONArray("excluded_apps")?.let { Prefs.setExcluded(app, strings(it).toSet()) }

        // 値の型が違う項目は読み飛ばす (getBoolean の JSONException で途中まで反映したまま止まらないように)
        root.optJSONObject("settings")?.let { s ->
            bool(s, "query_log")?.let { Prefs.setQueryLogEnabled(app, it) }
            bool(s, "update_on_wifi_only")?.let { Prefs.setUpdateOnWifiOnly(app, it) }
            bool(s, "auto_install_updates")?.let { Prefs.setAutoInstallUpdates(app, it) }
            if (s.has("encrypted_dns")) Prefs.setEncryptedDns(app, EncryptedDns.of(s.optString("encrypted_dns")))
            bool(s, "capture_hardcoded_dns")?.let { Prefs.setCaptureHardcodedDns(app, it) }
            (s.opt("log_retention_days") as? Int)?.takeIf { it in Prefs.LOG_RETENTION_CHOICES }?.let { Prefs.setLogRetentionDays(app, it) }
            if (s.has("update_time_minutes")) Prefs.setUpdateTimeMinutes(app, (s.opt("update_time_minutes") as? Int)?.takeIf { it in 0 until 24 * 60 })
            s.optJSONObject("notifications")?.let { n ->
                for (kind in NotificationKind.entries) {
                    bool(n, kind.key)?.let { Prefs.setNotificationEnabled(app, kind, it) }
                }
            }
            s.optJSONObject("blocklists")?.let { b ->
                for (source in BlockListUpdater.BUILT_IN) {
                    bool(b, source.id)?.let { Prefs.setSourceEnabled(app, source, it) }
                }
            }
            s.optJSONArray("custom_blocklists")?.let { urls ->
                Prefs.setCustomSourceUrls(app, strings(urls).mapNotNull { BlockListUpdater.normalizeUrl(it) }.toSet())
            }
        }

        // 反映: 自動更新の条件と時刻、常駐通知、例外アプリと公開 DNS の捕捉 (VPN を作り直す)、ブロックリスト
        val retime = Prefs.updateTimeMinutes(app) != oldUpdateTime
        BlockListWorker.schedule(app, retime)
        AppUpdateWorker.schedule(app, retime)
        AdBlockVpnService.refreshNotification(app)
        val rebuild = Prefs.excluded(app) != oldExcluded || Prefs.captureHardcodedDns(app) != oldCapture
        if (rebuild && AdBlockVpnService.isRunning) AdBlockVpnService.start(app, rebuild = true)
        thread {
            BlockListUpdater.applySelection(app)
            if (BlockListUpdater.hasMissing(app)) BlockListWorker.runNow(app)
        }
    }

    private fun bool(obj: JSONObject, key: String): Boolean? = obj.opt(key) as? Boolean

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
}
