package io.github.tonbo2339.adblocker

import android.content.Context
import androidx.core.content.edit

/** 設定の保存先。 */
object Prefs {
    private const val NAME = "settings"
    private const val STATE_NAME = "state"
    private const val KEY_EXCLUDED = "excluded_packages"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BLOCKLIST_CHECKED_AT = "blocklist_checked_at"
    private const val KEY_AUTO_INSTALL = "auto_install_updates"
    private const val KEY_WIFI_ONLY = "update_on_wifi_only"
    private const val KEY_NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked"
    private const val KEY_QUERY_LOG = "query_log"
    private const val KEY_PAUSED_UNTIL = "paused_until"
    private const val KEY_ETAG = "etag_"
    private const val KEY_LAST_MODIFIED = "last_modified_"
    private val STATE_KEYS = setOf(KEY_ENABLED, KEY_NOTIFICATION_PERMISSION_ASKED, KEY_PAUSED_UNTIL, KEY_BLOCKLIST_CHECKED_AT)

    /** 例外アプリ (VPN を通さないアプリ) のパッケージ名。 */
    fun excluded(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_EXCLUDED, null)?.toSet() ?: emptySet()

    fun setExcluded(context: Context, packages: Set<String>) {
        prefs(context).edit { putStringSet(KEY_EXCLUDED, HashSet(packages)) }
    }

    /** ユーザーが広告ブロックを ON にしているか (実際に動作中かどうかは AdBlockVpnService.state)。 */
    fun isEnabled(context: Context): Boolean = state(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = state(context)
        if (prefs.getBoolean(KEY_ENABLED, false) == enabled) return
        prefs.edit { putBoolean(KEY_ENABLED, enabled) }
        // 次にオンにしたとき、前の一時停止の続きにならないようにする
        if (!enabled && Pause.isPaused()) Pause.resume(context)
        AdBlockTileService.refresh(context)
    }

    /** 通知の許可をもう尋ねたか (断られても毎回は尋ねない)。 */
    fun notificationPermissionAsked(context: Context): Boolean =
        state(context).getBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, false)

    fun setNotificationPermissionAsked(context: Context) {
        state(context).edit { putBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, true) }
    }

    /** 一時停止の期限 (ミリ秒)。一時停止していなければ 0。普段は Pause を通して使う。 */
    fun pausedUntil(context: Context): Long = state(context).getLong(KEY_PAUSED_UNTIL, 0)

    fun setPausedUntil(context: Context, time: Long) {
        state(context).edit { putLong(KEY_PAUSED_UNTIL, time) }
    }

    /** 問い合わせのログ (メモリ上だけ) を取るか。 */
    fun queryLogEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_QUERY_LOG, true)

    fun setQueryLogEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_QUERY_LOG, enabled) }
        QueryLog.enabled = enabled
    }

    /** 自動更新 (ブロックリスト・アプリ) を Wi-Fi などの従量制でない回線のときだけ行うか。手動の更新には効かない。 */
    fun updateOnWifiOnly(context: Context): Boolean = prefs(context).getBoolean(KEY_WIFI_ONLY, false)

    fun setUpdateOnWifiOnly(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_WIFI_ONLY, enabled) }
    }

    /** 新しいバージョンを見つけたら自動でインストールするか (false なら通知だけ)。 */
    fun autoInstallUpdates(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_INSTALL, true)

    fun setAutoInstallUpdates(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_AUTO_INSTALL, enabled) }
    }

    /** 通知の種類ごとのオン / オフ (既定はすべてオン)。 */
    enum class NotificationKind(val key: String, val default: Boolean = true) {
        APP_UPDATE("notify_app_update"),
        BLOCKLIST_UPDATE("notify_blocklist_update"),
        RUNNING("notify_running"),
    }

    fun isNotificationEnabled(context: Context, kind: NotificationKind): Boolean =
        prefs(context).getBoolean(kind.key, kind.default)

    fun setNotificationEnabled(context: Context, kind: NotificationKind, enabled: Boolean) {
        prefs(context).edit { putBoolean(kind.key, enabled) }
    }

    /** ブロックリストをすべての取得元で最後に確認できた日時 (ミリ秒)。未確認なら 0。 */
    fun blocklistCheckedAt(context: Context): Long = state(context).getLong(KEY_BLOCKLIST_CHECKED_AT, 0)

    fun setBlocklistCheckedAt(context: Context, time: Long) {
        state(context).edit { putLong(KEY_BLOCKLIST_CHECKED_AT, time) }
    }

    /** 条件付きリクエスト (変更が無ければダウンロードしない) に使う、前回の応答ヘッダー。 */
    class SourceCache(val etag: String?, val lastModified: String?)

    fun sourceCache(context: Context, id: String): SourceCache? {
        val prefs = state(context)
        val etag = prefs.getString(KEY_ETAG + id, null)
        val lastModified = prefs.getString(KEY_LAST_MODIFIED + id, null)
        return if (etag == null && lastModified == null) null else SourceCache(etag, lastModified)
    }

    fun setSourceCache(context: Context, id: String, cache: SourceCache) {
        state(context).edit {
            putString(KEY_ETAG + id, cache.etag)
            putString(KEY_LAST_MODIFIED + id, cache.lastModified)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /**
     * 端末ごとの一時的な状態 (ON/OFF、通知の許可を尋ねたか、ETag など)。
     * 別の端末に復元されると食い違うので、バックアップの対象から外している (res/xml/backup_rules.xml)。
     */
    private fun state(context: Context) =
        context.getSharedPreferences(STATE_NAME, Context.MODE_PRIVATE)

    /** v0.1 までは状態も settings に入れていたので、state に移す。 */
    fun migrate(context: Context) {
        val old = prefs(context)
        val keys = old.all.keys.filter {
            it in STATE_KEYS || it.startsWith(KEY_ETAG) || it.startsWith(KEY_LAST_MODIFIED)
        }
        if (keys.isEmpty()) return
        val all = old.all
        state(context).edit {
            for (k in keys) {
                when (val v = all[k]) {
                    is Boolean -> putBoolean(k, v)
                    is Long -> putLong(k, v)
                    is String -> putString(k, v)
                }
            }
        }
        old.edit { keys.forEach { remove(it) } }
    }
}
