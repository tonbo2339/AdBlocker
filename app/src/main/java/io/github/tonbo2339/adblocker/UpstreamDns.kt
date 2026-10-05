package io.github.tonbo2339.adblocker

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * DNS クエリの転送先。
 *
 * VPN 以外のネットワーク (Wi-Fi / モバイル回線) に設定されている DNS サーバーを優先し、
 * それで解決できないときだけ公開 DNS を使う。公開 DNS への通信を塞いでいるネットワークでも名前解決が止まらないようにするため。
 */
class UpstreamDns(context: Context) {

    companion object {
        private const val TAG = "UpstreamDns"
        private val PUBLIC: List<InetAddress> =
            listOf("1.1.1.1", "8.8.8.8").map { InetAddress.getByName(it) }
    }

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val networkDns = ConcurrentHashMap<Network, List<InetAddress>>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            networkDns[network] = linkProperties.dnsServers
        }

        override fun onLost(network: Network) {
            networkDns.remove(network)
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            connectivity.registerNetworkCallback(request, callback)
        } catch (e: RuntimeException) {
            // 登録数の上限など。公開 DNS だけで動かす
            Log.w(TAG, "registerNetworkCallback failed", e)
        }
    }

    fun stop() {
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
            // 登録されていない
        }
        networkDns.clear()
    }

    /** 試す順に並べた転送先。 */
    fun servers(): List<InetAddress> = (networkDns.values.flatten() + PUBLIC).distinct()
}
