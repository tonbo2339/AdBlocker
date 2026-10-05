package io.github.tonbo2339.adblocker

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * 設定の「プライベート DNS」が「ホスト名を指定」になっているかを見張る。
 * その状態だと端末は指定のサーバーに暗号化して問い合わせ、この VPN の DNS を使わないので、ブロックが効かない。
 * (「自動」は VPN の DNS へ普通に問い合わせるので問題ない)
 *
 * VPN 以外の回線の LinkProperties.privateDnsServerName を見る (Android 9 以上。それより前にはこの設定が無い)。
 */
class PrivateDnsMonitor(context: Context, private val onChange: (hostname: String?) -> Unit) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val hostnames = ConcurrentHashMap<Network, String>()
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
            val name = linkProperties.privateDnsServerName
            if (name != null) hostnames[network] = name else hostnames.remove(network)
            notifyChange()
        }

        override fun onLost(network: Network) {
            hostnames.remove(network)
            notifyChange()
        }
    }

    /** 「ホスト名を指定」のときのホスト名。そうでなければ null。 */
    val hostname: String? get() = hostnames.values.firstOrNull()

    private fun notifyChange() {
        handler.post { onChange(hostname) }
    }

    fun start() {
        if (registered || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            connectivity.registerNetworkCallback(request, callback)
            registered = true
        } catch (e: RuntimeException) {
            Log.w("PrivateDnsMonitor", "registerNetworkCallback failed", e)
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
        }
        hostnames.clear()
    }
}
