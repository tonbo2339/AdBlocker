package io.github.tonbo2339.adblocker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 広告ブロックをしない Wi-Fi (自宅の Wi-Fi で別の広告ブロックを使っている、社内の Wi-Fi で困る、など)。
 * つながっている Wi-Fi の名前 (SSID) が一覧にあれば、一時停止と同じく VPN は動かしたまますべて通す。
 *
 * Wi-Fi の名前を読むには、Android の仕様で位置情報の許可が要る (画面を閉じている間も読むには「常に許可」)。
 * 位置そのものは使わない。
 */
object WifiNetworks {
    private const val TAG = "WifiNetworks"

    @Volatile
    private var ssids: Set<String> = emptySet()

    /** VPN サービスが見ている、今つながっている Wi-Fi の名前 (VPN が止まっているとき・読めないときは null)。 */
    @Volatile
    private var current: String? = null

    private val handler = Handler(Looper.getMainLooper())

    /** 一覧やつながっている Wi-Fi が変わって、素通しにするかが変わったときに (メインスレッドで) 呼ばれる。 */
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    /** プロセスの開始時に呼ぶ。 */
    fun load(context: Context) {
        ssids = Prefs.unblockedWifi(context)
    }

    val list: Set<String> get() = ssids

    /** 今の Wi-Fi では広告をブロックしないか。 */
    fun isUnblocked(): Boolean = current?.let { it in ssids } == true

    fun add(context: Context, ssid: String) = replace(context, ssids + ssid)

    fun remove(context: Context, ssid: String) = replace(context, ssids - ssid)

    fun replace(context: Context, value: Set<String>) {
        val before = isUnblocked()
        ssids = value
        Prefs.setUnblockedWifi(context, value)
        if (isUnblocked() != before) changed(context.applicationContext)
    }

    /** VPN サービスの SsidMonitor から呼ぶ。 */
    fun setCurrent(context: Context, ssid: String?) {
        val before = isUnblocked()
        current = ssid
        if (isUnblocked() != before) handler.post { changed(context.applicationContext) }
    }

    private fun changed(context: Context) {
        DebugLog.i(TAG, if (isUnblocked()) "on an unblocked Wi-Fi: blocking off" else "blocking on")
        AdBlockTileService.refresh(context)
        for (l in listeners) l()
    }

    /** Wi-Fi の名前を読む許可。画面を閉じている間も読むには、Android 10 以上は「常に許可」も要る。 */
    enum class Permission { NONE, WHILE_IN_USE, ALWAYS }

    fun permission(context: Context): Permission {
        fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        return when {
            !granted(Manifest.permission.ACCESS_FINE_LOCATION) -> Permission.NONE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> Permission.WHILE_IN_USE
            else -> Permission.ALWAYS
        }
    }

    /** 端末の位置情報がオンか (オフだと許可があっても Wi-Fi の名前を読めない)。 */
    fun locationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled else true
    }
}

/**
 * つながっている Wi-Fi の名前を見張る。onChange は名前が変わったときに別のスレッドから呼ばれる
 * (読めないとき・Wi-Fi でないときは null)。
 */
class SsidMonitor(context: Context, private val onChange: (String?) -> Unit) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val ssids = ConcurrentHashMap<Network, String>()
    private var registered = false

    @Volatile
    private var last: String? = null

    /** Wi-Fi につながっているのに名前が伏せられているか (デバッグログに変わったときだけ書く)。 */
    @Volatile
    private var hidden = false

    /** Android 12 以上は、位置情報を含めるよう求めないと Wi-Fi の名前が伏せられる。 */
    private val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                update(network, (caps.transportInfo as? WifiInfo)?.ssid)

            override fun onLost(network: Network) = update(network, null)
        }
    } else {
        object : ConnectivityManager.NetworkCallback() {
            @Suppress("DEPRECATION")
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                update(network, wifi?.connectionInfo?.ssid)

            override fun onLost(network: Network) = update(network, null)
        }
    }

    val ssid: String? get() = ssids.values.firstOrNull()

    private fun update(network: Network, raw: String?) {
        val name = clean(raw)
        val nowHidden = raw != null && name == null
        if (nowHidden != hidden) {
            hidden = nowHidden
            if (nowHidden) DebugLog.w("SsidMonitor", "Wi-Fi name is hidden (location permission missing or location off)")
        }
        if (name != null) ssids[network] = name else ssids.remove(network)
        val now = ssid
        if (now != last) {
            last = now
            onChange(now)
        }
    }

    fun start() {
        if (registered) return
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        try {
            connectivity.registerNetworkCallback(request, callback)
            registered = true
        } catch (e: RuntimeException) {
            DebugLog.w("SsidMonitor", "registerNetworkCallback failed", e)
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
        }
        ssids.clear()
        last = null
    }

    companion object {
        /** "MyWifi" (引用符付き) → MyWifi。読めないとき ("<unknown ssid>") は null。 */
        fun clean(raw: String?): String? {
            if (raw == null || raw == WifiManager.UNKNOWN_SSID) return null
            val name = if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) raw.substring(1, raw.length - 1) else raw
            return name.ifEmpty { null }
        }
    }
}
