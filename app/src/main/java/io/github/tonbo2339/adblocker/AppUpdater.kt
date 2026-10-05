package io.github.tonbo2339.adblocker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases の最新版を確認して、自分自身を更新する。
 *
 * - リリースのタグ (v1.3 など) をアプリの versionName と比べる
 * - APK は PackageInstaller でインストールする。Android 12 以上で、前回もこのアプリ自身が更新していれば
 *   確認なしで入る。そうでなければ確認画面が必要 (InstallResultReceiver が出す)
 * - パッケージ名・バージョン・署名を確かめてから入れる
 */
object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val REPO = "tonbo2339/AdBlocker"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_URL = "https://github.com/$REPO/releases"
    private const val TIMEOUT_MS = 30_000

    class Release(val version: String, val apkUrl: String)

    sealed interface Result {
        data object UpToDate : Result
        data class Available(val version: String) : Result
        data class Installing(val version: String) : Result
        data class Failed(val message: String) : Result
    }

    /**
     * 最新版を確認し、install なら更新まで行う。ネットワークを使うのでメインスレッドで呼ばない。
     * 定期実行と手動の確認が重なっても、同じ一時ファイルに同時に書かないよう 1 つずつ実行する。
     */
    @Synchronized
    fun check(context: Context, install: Boolean): Result {
        val apk = File(context.cacheDir, "update.apk")
        return try {
            val release = fetchLatest()
            when {
                !Versions.isNewer(release.version, BuildConfig.VERSION_NAME) -> Result.UpToDate
                !install -> Result.Available(release.version)
                else -> {
                    download(release.apkUrl, apk)
                    verify(context, apk)
                    installApk(context, apk)
                    Result.Installing(release.version)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "update failed", e)
            Result.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            // インストーラーには中身をコピー済み。検証で弾いた場合も含めて消す
            apk.delete()
        }
    }

    private fun fetchLatest(): Release {
        val json = JSONObject(httpGet(LATEST_URL) { it.readText() })
        val version = json.getString("tag_name").removePrefix("v")
        val assets = json.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name").endsWith(".apk")) {
                return Release(version, asset.getString("browser_download_url"))
            }
        }
        throw IOException("no APK in release $version")
    }

    private fun download(url: String, file: File) {
        httpGet(url) { input -> file.outputStream().use { input.copyTo(it) } }
    }

    /** 同じアプリで、新しいバージョンで、同じ鍵で署名された APK かを確かめる。 */
    private fun verify(context: Context, apk: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: throw IOException("not an APK")
        val current = pm.getPackageInfo(context.packageName, flags)
        if (archive.packageName != context.packageName) throw IOException("package mismatch: ${archive.packageName}")
        val newCode = PackageInfoCompat.getLongVersionCode(archive)
        if (newCode <= PackageInfoCompat.getLongVersionCode(current)) throw IOException("not newer: $newCode")
        // Android 8 では署名を読み出せないが、違う鍵の APK はどのみち OS がインストールを拒否する
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val installed = signers(current, withHistory = false)
            // 鍵を切り替えた (v3 の鍵ローテーション) APK は、署名の履歴に今の鍵が入っていれば OS が受け入れる
            if (installed.isEmpty() || !signers(archive, withHistory = true).containsAll(installed)) {
                throw IOException("signature mismatch")
            }
        }
    }

    /** 署名した鍵。withHistory なら、鍵ローテーションの履歴にある過去の鍵も含める。 */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun signers(info: PackageInfo, withHistory: Boolean): Set<String> {
        val signing = info.signingInfo ?: return emptySet()
        val certs = if (withHistory && !signing.hasMultipleSigners()) signing.signingCertificateHistory else signing.apkContentsSigners
        return certs.orEmpty().map { it.toCharsString() }.toSet()
    }

    private fun installApk(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                // 結果 (確認が必要・成功・失敗) は InstallResultReceiver に届く。
                // PackageInstaller が結果を書き込むので MUTABLE にする (明示的なインテントなので安全)
                val callback = PendingIntent.getBroadcast(
                    context, sessionId,
                    Intent(context, InstallResultReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
            }
        } catch (e: Exception) {
            // commit まで行かなかったセッションは OS 側に残り続けるので破棄する
            installer.abandonSession(sessionId)
            throw e
        }
    }

    private fun <T> httpGet(url: String, read: (java.io.InputStream) -> T): T {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "AdBlocker-Android")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code: $url")
            return conn.inputStream.use(read)
        } finally {
            conn.disconnect()
        }
    }

    private fun java.io.InputStream.readText(): String = bufferedReader().readText()
}

/** "1.10" > "1.9" のように数字ごとに比べる。 */
object Versions {
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parts(v: String): List<Int> =
        v.trim().removePrefix("v").split('.').map { p -> p.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
}
