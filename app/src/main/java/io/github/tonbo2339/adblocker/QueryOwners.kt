package io.github.tonbo2339.adblocker

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.system.OsConstants
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * DNS の問い合わせを出したアプリを調べる (Android 10 以上)。
 *
 * VPN アプリは、VPN に届いたパケットの送り元のソケットがどのアプリのものかを ConnectivityManager.getConnectionOwnerUid で調べられる。
 * 同じ uid を共有するアプリ (システムなど) は区別できないので、共有の名前 (android.uid.system など) になる。
 */
class QueryOwners(private val context: Context) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /** uid → パッケージ名。VPN サービスが動いている間だけ覚える。 */
    private val packages = ConcurrentHashMap<Int, String>()

    /** 問い合わせたアプリのパッケージ名 (分からなければ null)。 */
    fun ownerOf(query: DnsQuery): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val uid = try {
            connectivity.getConnectionOwnerUid(
                OsConstants.IPPROTO_UDP,
                InetSocketAddress(InetAddress.getByAddress(query.srcAddr), query.srcPort),
                InetSocketAddress(InetAddress.getByAddress(query.dstAddr), query.dstPort),
            )
        } catch (_: RuntimeException) {
            // SecurityException (VPN が有効でない) など
            return null
        }
        if (uid == Process.INVALID_UID) return null
        return packages.getOrPut(uid) { packageFor(uid) }
    }

    private fun packageFor(uid: Int): String {
        val pm = context.packageManager
        val names = pm.getPackagesForUid(uid).orEmpty()
        return when {
            names.size == 1 -> names[0]
            else -> pm.getNameForUid(uid) ?: "uid $uid"
        }
    }
}

/** パッケージ名からアプリの名前を引く (画面の表示用)。見つからなければパッケージ名のまま。 */
object AppLabels {
    private val labels = ConcurrentHashMap<String, String>()

    fun of(context: Context, packageName: String): String = labels.getOrPut(packageName) {
        try {
            val pm = context.packageManager
            pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            // 共有 uid の名前 (android.uid.system:1000 など) や、アンインストールしたアプリ
            packageName
        }
    }
}
