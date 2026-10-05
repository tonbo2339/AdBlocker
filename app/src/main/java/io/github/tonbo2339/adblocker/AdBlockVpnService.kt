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
        private const val ACTION_REBUILD = "io.github.tonbo2339.adblocker.REBUILD"
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
        private const val PREVIOUS_WORKER_WAIT_MS = 3000L

        /** tun に書ける DNS 応答の最大サイズ (MTU から IPv6 + UDP のヘッダー分を引く)。 */
        private const val MAX_RESPONSE = MTU - 48

        /** 転送待ちの問い合わせの上限。回線が切れている間に再送が積み上がってメモリを使い続けないようにする。 */
        private const val MAX_PENDING_QUERIES = 256

        /** 実際の動作状態。プロセスが落ちると STOPPED に戻る (ユーザーの ON/OFF は Prefs.isEnabled)。 */
        @Volatile
        var state = State.STOPPED
            private set

        val isRunning: Boolean get() = state == State.RUNNING

        /**
         * 開始する。開始中・動作中なら何もしない (rebuild なら例外設定を読み直して作り直す)。
         * 先に VpnService.prepare() が済んでいること。バックグラウンドからの開始が許されない場合は IllegalStateException。
         */
        fun start(context: Context, rebuild: Boolean = false) {
            context.startForegroundService(
                Intent(context, AdBlockVpnService::class.java).setAction(if (rebuild) ACTION_REBUILD else ACTION_START)
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

    /** 暗号化 DNS のクライアント。設定が変わったら作り直す (VPN は作り直さない)。 */
    @Volatile
    private var dot: DotClient? = null
    private val dotLock = Any()

    /** onDestroy() の後か (dotLock の中で読み書きする)。 */
    private var destroyed = false

    /** 現在のワーカー。作り直しで置き換わった古いワーカーは、終了時に状態を書き換えない。 */
    @Volatile
    private var worker: Thread? = null

    /** 最後に停止を伝えたワーカー (次のワーカーは、これが終わるのを待ってから tun を作る)。 */
    private var stopping: Thread? = null

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

    /** 一時停止・再開したら、常駐通知の文言と「再開」ボタンを差し替える。 */
    private val pauseListener: () -> Unit = {
        if (state == State.RUNNING && Prefs.isNotificationEnabled(this, NotificationKind.RUNNING)) {
            startForegroundWithNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Pause.addListener(pauseListener)
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
        // 開始の要求は重なることがある (アプリの更新直後は BootReceiver と画面の両方から届く)。
        // そのたびに作り直すと、古い tun の後片付けと新しい tun の作成が重なり、問い合わせが届かなくなることがあった
        if (intent?.action != ACTION_REBUILD && worker != null) {
            applyRunningNotificationSetting()
            return START_STICKY
        }
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
        Pause.removeListener(pauseListener)
        mainHandler.removeCallbacks(statsFlusher)
        StatsStore.flush(applicationContext)
        shutdown()
        state = State.STOPPED
        upstream.stop()
        synchronized(dotLock) {
            destroyed = true
            dot?.close()
            dot = null
        }
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

    /** 新しいワーカーで VPN を作り直す。新しいワーカーは、古いワーカーが終わってから tun を作る。 */
    @Synchronized
    private fun restart() {
        shutdown()
        val previous = stopping
        state = State.STARTING
        val pipe = Os.pipe()
        stopSignal = pipe[1]
        worker = Thread({ runVpn(pipe[0], previous) }, "AdBlockVpn").also { it.start() }
    }

    /** 現在のワーカーに停止を伝える (終了は待たない)。 */
    @Synchronized
    private fun shutdown() {
        val signal = stopSignal ?: return
        stopSignal = null
        stopping = worker
        worker = null
        try {
            Os.write(signal, byteArrayOf(1), 0, 1)
        } catch (e: ErrnoException) {
            Log.w(TAG, "stop signal failed", e)
        }
        // 読み取り側はワーカーが終了時に閉じる
        closeQuietly(signal)
    }

    /** previous: 作り直す前のワーカー。それが古い tun を閉じてから新しい tun を作る。 */
    private fun runVpn(stopFd: FileDescriptor, previous: Thread?) {
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
            // 古い tun が開いたまま新しい tun を作ると、端末がどちらに問い合わせを送るかが不安定になる
            previous?.join(PREVIOUS_WORKER_WAIT_MS)
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
        if (verdict.blocked) {
            record(question.name, verdict)
            writePacket(out, Packets.buildResponse(query, Dns.nxdomain(query.dns, question)))
            return
        }
        executor.execute {
            val response = forward(query.dns)
            // 自社のサブドメインに見せかけたトラッカー (CNAME 隠し) も、行き先で止める
            if (response != null && verdict == Verdict.PASS) {
                val cloaked = Filter.cnameBlocked(Dns.cnameTargets(response))
                if (cloaked != null) {
                    record(question.name, Verdict.CNAME, cloaked)
                    writePacket(out, Packets.buildResponse(query, Dns.nxdomain(query.dns, question)))
                    return@execute
                }
            }
            record(question.name, verdict)
            if (response != null) writePacket(out, Packets.buildResponse(query, response))
        }
    }

    private fun record(name: String, verdict: Verdict, via: String? = null) {
        StatsStore.record(verdict.blocked)
        QueryLog.add(name, verdict, via)
        if (verdict.blocked && QueryLog.enabled) SessionStats.recordBlocked(name)
    }

    /** 設定に合った暗号化 DNS のクライアント (オフなら null)。 */
    private fun dotClient(): DotClient? {
        val server = Prefs.encryptedDns(this)
        val current = dot
        if (current?.server == server) return current
        // VPN の作り直しと同じロック (this) を使うと、tun を作っている間の問い合わせが待たされる
        synchronized(dotLock) {
            // 終了後に残った転送が新しいクライアントを作ると、その接続は誰も閉じない
            if (destroyed) return null
            val again = dot
            if (again?.server == server) return again
            again?.close()
            return server?.let { DotClient(this, it) }.also { dot = it }
        }
    }

    /**
     * 本物の DNS サーバーに問い合わせる。暗号化 DNS がオンならそれを使い、失敗したときだけ通常の DNS にする。
     * protect() でこのソケット自体は VPN を通らないようにする。
     */
    private fun forward(dns: ByteArray): ByteArray? {
        // tun に書けるのは MTU まで。DoT (TCP) の応答はそれより大きいことがあるので、そのときは UDP で取り直す
        dotClient()?.query(dns)?.takeIf { it.size <= MAX_RESPONSE }?.let { return it }
        for (server in upstream.servers()) {
            try {
                DatagramSocket().use { socket ->
                    protect(socket)
                    // 問い合わせ先に固定して、ほかの相手からのパケットを受け取らないようにする
                    socket.connect(server, 53)
                    socket.soTimeout = UPSTREAM_TIMEOUT_MS
                    socket.send(DatagramPacket(dns, dns.size))
                    val rb = ByteArray(MAX_RESPONSE)
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
