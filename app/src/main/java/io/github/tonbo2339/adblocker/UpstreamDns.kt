package io.github.tonbo2339.adblocker

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * DNS クエリの転送先。
 *
 * VPN 以外のネットワーク (Wi-Fi / モバイル回線) に設定されている DNS サーバーを優先し、
 * それで解決できないときだけ公開 DNS を使う。公開 DNS への通信を塞いでいるネットワークでも名前解決が止まらないようにするため。
 */
/** onChange: 回線の DNS が変わったとき (別のスレッドから呼ばれる)。 */
class UpstreamDns(context: Context, private val onChange: () -> Unit = {}) {

    companion object {
        private const val TAG = "UpstreamDns"
        private val PUBLIC: List<InetAddress> =
            listOf("1.1.1.1", "8.8.8.8").map { InetAddress.getByName(it) }
    }

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val networkDns = ConcurrentHashMap<Network, List<InetAddress>>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            val servers = linkProperties.dnsServers
            if (networkDns.put(network, servers) != servers) {
                if (DebugLog.enabled) logNetwork(network, linkProperties)
                onChange()
            }
        }

        override fun onLost(network: Network) {
            if (networkDns.remove(network) != null) {
                DebugLog.i(TAG, "network lost; network DNS: ${networkDns.values.flatten().map { it.hostAddress }}")
                onChange()
            }
        }
    }

    /** 回線の種類・DNS サーバー・プライベート DNS の状態をデバッグログに残す。 */
    private fun logNetwork(network: Network, linkProperties: LinkProperties) {
        val type = connectivity.getNetworkCapabilities(network)?.let { caps ->
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
        } ?: "unknown"
        val privateDns = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> "n/a"
            linkProperties.privateDnsServerName != null -> "hostname"
            linkProperties.isPrivateDnsActive -> "automatic (active)"
            else -> "off or inactive"
        }
        DebugLog.i(
            TAG,
            "$type: DNS ${linkProperties.dnsServers.map { it.hostAddress }}, private DNS $privateDns; " +
                "all network DNS ${networkDns.values.flatten().map { it.hostAddress }}",
        )
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
            DebugLog.w(TAG, "registerNetworkCallback failed", e)
        }
    }

    fun stop() {
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
            // 登録されていない
        }
        networkDns.clear()
        lastWorking = null
    }

    /** 最後に応答した転送先。応答しないサーバーのタイムアウトを毎回待たないよう、次からはこれを先に試す。 */
    @Volatile
    private var lastWorking: InetAddress? = null

    /** 試す順に並べた転送先。 */
    fun servers(): List<InetAddress> {
        val all = (networkDns.values.flatten() + PUBLIC).distinct()
        val preferred = lastWorking ?: return all
        // 回線が変わって今の候補に無いサーバーは使わない
        return if (preferred in all) listOf(preferred) + (all - preferred) else all
    }

    fun markWorking(server: InetAddress) {
        lastWorking = server
    }
}

/** 主な公開 DNS のアドレス。「DNS を直接指定しているアプリもブロック」がオンのとき、ここ宛ての通信を VPN に通す。 */
object PublicDns {
    val ADDRESSES = listOf(
        // Google
        "8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844",
        // Cloudflare
        "1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001",
        // Quad9
        "9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9",
        // OpenDNS
        "208.67.222.222", "208.67.220.220", "2620:119:35::35", "2620:119:53::53",
        // AdGuard DNS
        "94.140.14.14", "94.140.15.15", "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff",
    )
}
