package io.github.tonbo2339.adblocker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import io.github.tonbo2339.adblocker.Prefs.NotificationKind
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * DNS だけを通すローカル VPN。
 *
 * 偽の DNS サーバー (VPN_DNS4 / VPN_DNS6) を端末に設定し、そのアドレスだけを VPN にルーティングする。
 * 届いた DNS クエリのうち広告ドメインは NXDOMAIN を返し、それ以外は本物の DNS サーバーへ転送する。
 * それ以外の通信は VPN を通らないので速度に影響しない。
 * 例外アプリは addDisallowedApplication で VPN から外すので、通常の DNS をそのまま使う。
 */
class AdBlockVpnService : VpnService() {

    enum class State { STOPPED, STARTING, RUNNING }

    companion object {
        private const val TAG = "AdBlockVpn"
        private const val ACTION_START = "io.github.tonbo2339.adblocker.START"
        private const val ACTION_STOP = "io.github.tonbo2339.adblocker.STOP"
        private const val ACTION_REFRESH_NOTIFICATION = "io.github.tonbo2339.adblocker.REFRESH_NOTIFICATION"

        private const val VPN_ADDR4 = "10.111.222.1"
        private const val VPN_DNS4 = "10.111.222.2"
        private const val VPN_ADDR6 = "fd00:6164:626c:6f63::1"
        private const val VPN_DNS6 = "fd00:6164:626c:6f63::2"
        private const val MTU = 16384
        private const val UPSTREAM_TIMEOUT_MS = 2000
        private const val FORWARD_THREADS = 8
        private const val STATS_FLUSH_INTERVAL_MS = 30_000L

        /** 転送待ちの問い合わせの上限。回線が切れている間に再送が積み上がってメモリを使い続けないようにする。 */
        private const val MAX_PENDING_QUERIES = 256

        /** 実際の動作状態。プロセスが落ちると STOPPED に戻る (ユーザーの ON/OFF は Prefs.isEnabled)。 */
        @Volatile
        var state = State.STOPPED
            private set

        val isRunning: Boolean get() = state == State.RUNNING

        /**
         * 開始する。動作中なら例外設定を読み直して作り直す。先に VpnService.prepare() が済んでいること。
         * バックグラウンドからの開始が許されない場合は IllegalStateException。
         */
        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, AdBlockVpnService::class.java).setAction(ACTION_START)
            )
            if (state == State.STOPPED) state = State.STARTING
        }

        fun stop(context: Context) {
            if (state == State.STOPPED) return
            try {
                context.startService(
                    Intent(context, AdBlockVpnService::class.java).setAction(ACTION_STOP)
                )
            } catch (e: IllegalStateException) {
                Log.w(TAG, "stop failed", e)
            }
        }

        /** 「動作中の表示」の設定を変えたときに、常駐通知を差し替える。 */
        fun refreshNotification(context: Context) {
            if (state == State.STOPPED) return
            try {
                context.startService(
                    Intent(context, AdBlockVpnService::class.java).setAction(ACTION_REFRESH_NOTIFICATION)
                )
            } catch (e: IllegalStateException) {
                Log.w(TAG, "refresh notification failed", e)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var upstream: UpstreamDns

    /** 現在のワーカー。作り直しで置き換わった古いワーカーは、終了時に状態を書き換えない。 */
    @Volatile
    private var worker: Thread? = null

    /** 現在のワーカーへ停止を伝えるパイプの書き込み側。 */
    private var stopSignal: FileDescriptor? = null

    /** 常駐通知を出したときの言語。 */
    private lateinit var locales: LocaleList

    /** 日ごとの件数を定期的に保存する。 */
    private val statsFlusher = object : Runnable {
        override fun run() {
            StatsStore.flush(applicationContext)
            mainHandler.postDelayed(this, STATS_FLUSH_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        locales = resources.configuration.locales
        upstream = UpstreamDns(this).apply { start() }
        mainHandler.postDelayed(statsFlusher, STATS_FLUSH_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REFRESH_NOTIFICATION) {
            if (Prefs.isNotificationEnabled(this, NotificationKind.RUNNING)) {
                startForegroundWithNotification()
            } else {
                applyRunningNotificationSetting()
            }
            return START_STICKY
        }
        // ACTION_START、常時接続 VPN でシステムから起動された場合 (action は android.net.VpnService)、
        // START_STICKY での再起動 (intent は null) のいずれか
        startForegroundWithNotification()
        Prefs.setEnabled(this, true)
        if (state != State.RUNNING) SessionStats.reset()
        restart()
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 言語が変わったら、出ている常駐通知とチャンネル名を新しい言語で出し直す (回転などでは何もしない)
        if (newConfig.locales == locales) return
        locales = newConfig.locales
        if (state != State.STOPPED && Prefs.isNotificationEnabled(this, NotificationKind.RUNNING)) {
            startForegroundWithNotification()
        }
    }

    override fun onRevoke() {
        // 別の VPN が有効になった、またはユーザーが設定から切断した
        stopVpn()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(statsFlusher)
        StatsStore.flush(applicationContext)
        shutdown()
        state = State.STOPPED
        upstream.stop()
        super.onDestroy()
    }

    /** 停止して OFF 状態にする (ユーザー操作・VPN の取り消し・異常終了で共通)。 */
    private fun stopVpn() {
        Prefs.setEnabled(this, false)
        shutdown()
        state = State.STOPPED
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 新しいワーカーで VPN を作り直す。古いワーカーの終了は待たない
     * (新しい VPN を establish() した時点で古いインターフェースは置き換わり、古いワーカーは自分の tun を閉じて終わる)。
     */
    @Synchronized
    private fun restart() {
        shutdown()
        state = State.STARTING
        val pipe = Os.pipe()
        stopSignal = pipe[1]
        worker = Thread({ runVpn(pipe[0]) }, "AdBlockVpn").also { it.start() }
    }

    /** 現在のワーカーに停止を伝える (終了は待たない)。 */
    @Synchronized
    private fun shutdown() {
        val signal = stopSignal ?: return
        stopSignal = null
        worker = null
        try {
            Os.write(signal, byteArrayOf(1), 0, 1)
        } catch (e: ErrnoException) {
            Log.w(TAG, "stop signal failed", e)
        }
        // 読み取り側はワーカーが終了時に閉じる
        closeQuietly(signal)
    }

    private fun runVpn(stopFd: FileDescriptor) {
        val me = Thread.currentThread()
        // 上限を超えたら古い問い合わせから捨てる (アプリ側は応答が無ければ再送する)
        val executor = ThreadPoolExecutor(
            FORWARD_THREADS, FORWARD_THREADS, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(MAX_PENDING_QUERIES), ThreadPoolExecutor.DiscardOldestPolicy(),
        )
        var tun: ParcelFileDescriptor? = null
        var stoppedByRequest = false
        try {
            BlockList.load(this)
            // 停止・作り直しが要求されていたら VPN を作らない (新しいワーカーの VPN を上書きしないため)。
            // restart() / shutdown() と同じロックの中で確認と作成をまとめて行う
            synchronized(this) {
                if (worker !== me) {
                    stoppedByRequest = true
                    return
                }
                tun = buildInterface()
                if (tun != null) {
                    state = State.RUNNING
                    mainHandler.post { applyRunningNotificationSetting() }
                }
            }
            val fd = tun?.fileDescriptor ?: run {
                Log.w(TAG, "establish() returned null (VPN permission missing?)")
                return
            }
            stoppedByRequest = loop(fd, stopFd, executor)
        } catch (e: Exception) {
            Log.e(TAG, "VPN loop stopped", e)
        } finally {
            executor.shutdownNow()
            try {
                tun?.close()
            } catch (_: IOException) {
            }
            closeQuietly(stopFd)
            // 停止要求以外で終わった (権限が無い・エラー) 場合はサービスごと止めて OFF に戻す
            if (!stoppedByRequest) mainHandler.post { if (worker === me) stopVpn() }
        }
    }

    private fun buildInterface(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(MTU)
            .addAddress(VPN_ADDR4, 24)
            .addDnsServer(VPN_DNS4)
            .addRoute(VPN_DNS4, 32)
            .addAddress(VPN_ADDR6, 64)
            .addDnsServer(VPN_DNS6)
            .addRoute(VPN_DNS6, 128)
            .setConfigureIntent(mainActivityIntent())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        for (pkg in Prefs.excluded(this)) {
            try {
                builder.addDisallowedApplication(pkg)
            } catch (e: PackageManager.NameNotFoundException) {
                // アンインストール済みのアプリは無視
            }
        }
        return builder.establish()
    }

    /** tun からクエリを読み続ける。停止要求で抜けたら true、tun が閉じられたら false。 */
    private fun loop(tunFd: FileDescriptor, stopFd: FileDescriptor, executor: ExecutorService): Boolean {
        val out = FileOutputStream(tunFd)
        val buf = ByteArray(MTU)
        val tunPoll = StructPollfd().apply {
            fd = tunFd
            events = OsConstants.POLLIN.toShort()
        }
        val stopPoll = StructPollfd().apply {
            fd = stopFd
            events = OsConstants.POLLIN.toShort()
        }
        val fds = arrayOf(tunPoll, stopPoll)

        while (true) {
            try {
                Os.poll(fds, -1)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                throw e
            }
            if (stopPoll.revents.toInt() != 0) return true
            val ev = tunPoll.revents.toInt()
            if (ev and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) return false
            if (ev and OsConstants.POLLIN == 0) continue

            val n = try {
                Os.read(tunFd, buf, 0, buf.size)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue
                throw e
            }
            val query = Packets.parse(buf, n) ?: continue
            handleQuery(query, out, executor)
        }
    }

    private fun handleQuery(query: DnsQuery, out: FileOutputStream, executor: ExecutorService) {
        val question = Dns.parseQuestion(query.dns) ?: return
        val verdict = Filter.decide(question.name)
        StatsStore.record(verdict.blocked)
        QueryLog.add(question.name, verdict)
        if (verdict.blocked) {
            SessionStats.recordBlocked(question.name)
            writePacket(out, Packets.buildResponse(query, Dns.nxdomain(query.dns, question)))
            return
        }
        executor.execute {
            val response = forward(query.dns) ?: return@execute
            writePacket(out, Packets.buildResponse(query, response))
        }
    }

    /** 本物の DNS サーバーに問い合わせる。protect() でこのソケット自体は VPN を通らないようにする。 */
    private fun forward(dns: ByteArray): ByteArray? {
        for (server in upstream.servers()) {
            try {
                DatagramSocket().use { socket ->
                    protect(socket)
                    // 問い合わせ先に固定して、ほかの相手からのパケットを受け取らないようにする
                    socket.connect(server, 53)
                    socket.soTimeout = UPSTREAM_TIMEOUT_MS
                    socket.send(DatagramPacket(dns, dns.size))
                    val rb = ByteArray(MTU - 48) // IPv6 + UDP ヘッダー分を引いた最大サイズ
                    val rp = DatagramPacket(rb, rb.size)
                    socket.receive(rp)
                    upstream.markWorking(server)
                    return rb.copyOf(rp.length)
                }
            } catch (e: IOException) {
                Log.d(TAG, "upstream $server failed: $e")
            }
        }
        return null
    }

    private fun writePacket(out: FileOutputStream, packet: ByteArray) {
        synchronized(out) {
            try {
                out.write(packet)
            } catch (e: IOException) {
                Log.d(TAG, "tun write failed: $e")
            }
        }
    }

    private fun closeQuietly(fd: FileDescriptor) {
        if (!fd.valid()) return
        try {
            Os.close(fd)
        } catch (_: ErrnoException) {
        }
    }

    /**
     * 「動作中の表示」がオフなら、VPN の確立後に常駐通知を外す。
     *
     * startForegroundService() で起動した以上 startForeground() は必須なので、起動時にはいったん通知を出す。
     * VPN を確立した後は OS (ConnectivityService) が BIND_FOREGROUND_SERVICE でこのサービスに結び付いているので、
     * フォアグラウンドサービスをやめてもプロセスの優先度は下がらない (エミュレーターの Android 16 で確認)。
     * 通知チャンネルを IMPORTANCE_NONE / MIN にする方法は、Android 16 ではフォアグラウンドサービスの通知が
     * LOW に引き上げられて効かなかった。
     */
    private fun applyRunningNotificationSetting() {
        if (state != State.RUNNING) return
        if (!Prefs.isNotificationEnabled(this, NotificationKind.RUNNING)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun startForegroundWithNotification() {
        val notification = Notifications.running(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Notifications.ID_RUNNING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifications.ID_RUNNING, notification)
        }
    }

    private fun mainActivityIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
