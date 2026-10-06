package io.github.tonbo2339.adblocker

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
        private const val ACTION_REFRESH_WIFI = "io.github.tonbo2339.adblocker.REFRESH_WIFI"

        private const val VPN_ADDR4 = "10.111.222.1"
        private const val VPN_DNS4 = "10.111.222.2"
        private const val VPN_ADDR6 = "fd00:6164:626c:6f63::1"
        private const val VPN_DNS6 = "fd00:6164:626c:6f63::2"
        private const val MTU = 16384
        /** 通常の DNS で答えを待つ時間 (すべての転送先を合わせて)。 */
        private const val UPSTREAM_TIMEOUT_MS = 3000

        /** 答えが無ければ次の転送先にも送るまでの時間と、同時に送る台数の上限。 */
        private const val STAGGER_MS = 400L
        private const val MAX_PARALLEL = 3
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

        /**
         * 例外アプリや公開 DNS の捕捉など、VPN を作るときに読む設定を変えたあとに呼ぶ。開始中・動作中なら作り直して true。
         * (止まっていれば何もしない。次に VPN を作るときに保存した設定が使われる)
         */
        fun rebuildIfActive(context: Context): Boolean {
            if (state == State.STOPPED) return false
            return try {
                start(context, rebuild = true)
                true
            } catch (e: IllegalStateException) {
                Log.w(TAG, "rebuild failed", e)
                false
            }
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
        fun refreshNotification(context: Context) = send(context, ACTION_REFRESH_NOTIFICATION)

        /**
         * 位置情報の許可を変えたときに、Wi-Fi の名前を読み直す
         * (名前は見張りを登録したときの許可で伏せられるかが決まり、許可を変えても届き直さない)。
         */
        fun refreshWifi(context: Context) = send(context, ACTION_REFRESH_WIFI)

        private fun send(context: Context, action: String) {
            if (state == State.STOPPED) return
            try {
                context.startService(Intent(context, AdBlockVpnService::class.java).setAction(action))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "$action failed", e)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var upstream: UpstreamDns
    private lateinit var apps: QueryOwners

    /** 転送先の答えのキャッシュ。回線が変わったら消す。 */
    private val cache = DnsCache()

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

    /** デバッグログ用の件数 (5 分ごとにまとめて書き、0 に戻す)。 */
    private object Counters {
        val queries = AtomicInteger()
        val blocked = AtomicInteger()
        val cached = AtomicInteger()
        val forwarded = AtomicInteger()
        val failed = AtomicInteger()
        val dot = AtomicInteger()
        val latencyTotal = AtomicLong()
        val latencyMax = AtomicLong()
    }

    private var flushTicks = 0

    private fun logSummary() {
        val queries = Counters.queries.getAndSet(0)
        val forwarded = Counters.forwarded.getAndSet(0)
        val total = Counters.latencyTotal.getAndSet(0)
        val line = "last 5 min: $queries queries, ${Counters.blocked.getAndSet(0)} blocked, ${Counters.cached.getAndSet(0)} cached, " +
            "$forwarded forwarded (${Counters.dot.getAndSet(0)} over DoT), ${Counters.failed.getAndSet(0)} failed; " +
            "upstream ${if (forwarded > 0) total / forwarded else 0} ms avg, ${Counters.latencyMax.getAndSet(0)} ms max; " +
            "servers ${upstream.servers().map { it.hostAddress }}; unblocked Wi-Fi: ${WifiNetworks.isUnblocked()}"
        if (queries > 0) DebugLog.i(TAG, line)
    }

    /** 日ごとの件数を定期的に保存する。 */
    private val statsFlusher = object : Runnable {
        override fun run() {
            StatsStore.flush(applicationContext)
            QueryLogFiles.flush()
            if (++flushTicks % 10 == 0 && DebugLog.enabled && state == State.RUNNING) logSummary()
            mainHandler.postDelayed(this, STATS_FLUSH_INTERVAL_MS)
        }
    }

    /** 一時停止・再開したとき、広告ブロックをしない Wi-Fi に出入りしたときに、常駐通知の文言と「再開」ボタンを差し替える。 */
    private val statusListener: () -> Unit = {
        if (state == State.RUNNING && Prefs.isNotificationEnabled(this, NotificationKind.RUNNING)) {
            startForegroundWithNotification()
        }
    }

    /** つながっている Wi-Fi の名前 (広告ブロックをしない Wi-Fi の判定に使う)。 */
    private lateinit var ssidMonitor: SsidMonitor

    /** 転送先が 1 つも答えなかった回数と、最後にデバッグログに書いた時刻 (回線が切れている間に何千行も書かないため)。 */
    private val forwardFailures = AtomicInteger()
    @Volatile
    private var forwardFailureLoggedAt = 0L

    override fun onCreate() {
        super.onCreate()
        DebugLog.i(TAG, "service created")
        Pause.addListener(statusListener)
        WifiNetworks.addListener(statusListener)
        locales = resources.configuration.locales
        upstream = UpstreamDns(this) { cache.clear() }.apply { start() }
        ssidMonitor = SsidMonitor(this) { WifiNetworks.setCurrent(this, it) }.apply { start() }
        apps = QueryOwners(this)
        mainHandler.postDelayed(statsFlusher, STATS_FLUSH_INTERVAL_MS)
        registerReceiver(packageAdded, IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") })
    }

    /**
     * 例外アプリは VPN を作るときにアプリの UID で外す。アンインストールして入れ直すと UID が変わり、
     * 作り直すまで VPN を通ってしまうので、例外アプリが入ったら作り直す (更新では UID が変わらないので何もしない)。
     */
    private val packageAdded = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            val pkg = intent.data?.schemeSpecificPart ?: return
            if (state != State.STOPPED && pkg in Prefs.excluded(context)) {
                DebugLog.i(TAG, "excluded app reinstalled: rebuilding")
                restart()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH_WIFI) {
            ssidMonitor.stop()
            WifiNetworks.setCurrent(this, null)
            ssidMonitor.start()
            return START_STICKY
        }
        if (intent?.action != ACTION_REFRESH_NOTIFICATION) DebugLog.i(TAG, "start command: ${intent?.action ?: "restarted by system"}")
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
        DebugLog.i(TAG, "VPN revoked")
        stopVpn()
    }

    override fun onDestroy() {
        DebugLog.i(TAG, "service destroyed")
        unregisterReceiver(packageAdded)
        Pause.removeListener(statusListener)
        WifiNetworks.removeListener(statusListener)
        ssidMonitor.stop()
        WifiNetworks.setCurrent(this, null)
        mainHandler.removeCallbacks(statsFlusher)
        StatsStore.flush(applicationContext)
        QueryLogFiles.flush()
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
        cache.clear()
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
            DebugLog.i(TAG, "blocklist: ${BlockList.size} domains")
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
                DebugLog.i(
                    TAG,
                    if (tun != null) {
                        "VPN established (excluded apps ${Prefs.excluded(this).size}, capture public DNS ${Prefs.captureHardcodedDns(this)}, " +
                            "encrypted DNS ${Prefs.encryptedDns(this)?.id ?: "off"})"
                    } else {
                        "establish() returned null"
                    },
                )
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
            DebugLog.i(TAG, if (stoppedByRequest) "worker stopped" else "tun closed")
        } catch (e: Exception) {
            Log.e(TAG, "VPN loop stopped", e)
            DebugLog.w(TAG, "VPN loop stopped", e)
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
        // DNS サーバーを直接指定しているアプリ対策: 主な公開 DNS 宛ても VPN に通す。
        // 53 番の問い合わせは同じように判定して答え、それ以外 (DNS over HTTPS / TLS) は断って、端末の DNS に戻らせる
        if (Prefs.captureHardcodedDns(this)) {
            for (address in PublicDns.ADDRESSES) builder.addRoute(address, if (':' in address) 128 else 32)
        }
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
            val query = Packets.parse(buf, n)
            if (query == null) {
                // 公開 DNS 宛ての TCP (DNS over HTTPS / TLS など) は、すぐ断ってアプリを待たせない
                Packets.tcpReset(buf, n)?.let { writePacket(out, it) }
                continue
            }
            handleQuery(query, out, executor)
        }
    }

    private fun handleQuery(query: DnsQuery, out: FileOutputStream, executor: ExecutorService) {
        val question = Dns.parseQuestion(query.dns) ?: return
        // どのアプリの問い合わせか。応答を返すとアプリがソケットを閉じて分からなくなるので、先に調べる
        val app = if (QueryLog.enabled) apps.ownerOf(query) else null
        val verdict = Filter.decide(question.name)
        Counters.queries.incrementAndGet()
        if (verdict.blocked) {
            Counters.blocked.incrementAndGet()
            record(question.name, verdict, app = app)
            writePacket(out, Packets.buildResponse(query, Dns.blocked(query.dns, question)))
            return
        }
        // 覚えている答えがあれば、転送せずにすぐ返す (答えの中身による判定はルールが変わることがあるので毎回行う)
        cache.get(query.dns, question)?.let { cached ->
            Counters.cached.incrementAndGet()
            answer(query, question, verdict, cached, app, out)
            return
        }
        executor.execute {
            val started = System.nanoTime()
            val response = forward(query.dns)
            if (response == null) {
                Counters.failed.incrementAndGet()
                noteForwardFailure()
                // 転送先が 1 つも答えなかった。何も返さないとアプリはタイムアウトまで待つので、すぐ失敗を知らせる
                record(question.name, verdict, app = app)
                writePacket(out, Packets.buildResponse(query, Dns.servfail(query.dns, question)))
                return@execute
            }
            val ms = (System.nanoTime() - started) / 1_000_000
            Counters.forwarded.incrementAndGet()
            Counters.latencyTotal.addAndGet(ms)
            Counters.latencyMax.accumulateAndGet(ms, ::maxOf)
            cache.put(query.dns, question, response)
            answer(query, question, verdict, response, app, out)
        }
    }

    /** 転送先 (またはキャッシュ) の答えを返す。答えの中身 (CNAME 隠し・IP) でブロックすることもある。 */
    private fun answer(query: DnsQuery, question: Question, verdict: Verdict, response: ByteArray, app: String?, out: FileOutputStream) {
        if (verdict == Verdict.PASS) {
            Filter.checkResponse(response)?.let { block ->
                record(question.name, block.verdict, block.via, app)
                writePacket(out, Packets.buildResponse(query, Dns.blocked(query.dns, question)))
                return
            }
        }
        record(question.name, verdict, app = app)
        writePacket(out, Packets.buildResponse(query, response))
    }

    /** 転送先ごとに、送れなかった理由 (届かないアドレス・Android 17 の LAN 制限の EPERM など) を 1 分に 1 回残す。 */
    private val sendFailureLoggedAt = java.util.concurrent.ConcurrentHashMap<java.net.InetAddress, Long>()

    private fun noteSendFailure(server: java.net.InetAddress, e: IOException) {
        if (!DebugLog.enabled) return
        val now = System.currentTimeMillis()
        val last = sendFailureLoggedAt[server]
        if (last != null && now - last < 60_000) return
        sendFailureLoggedAt[server] = now
        DebugLog.w(TAG, "send to ${server.hostAddress} failed", e)
    }

    /** 転送の失敗をデバッグログに残す。続いたときは 10 秒に 1 行にまとめる。 */
    private fun noteForwardFailure() {
        if (!DebugLog.enabled) return
        val count = forwardFailures.incrementAndGet()
        val now = System.currentTimeMillis()
        if (now - forwardFailureLoggedAt < 10_000) return
        forwardFailureLoggedAt = now
        forwardFailures.set(0)
        DebugLog.w(TAG, "no upstream answered ($count queries; servers ${upstream.servers().map { it.hostAddress }})")
    }

    private fun record(name: String, verdict: Verdict, via: String? = null, app: String? = null) {
        StatsStore.record(verdict.blocked)
        QueryLog.add(name, verdict, via, app)
        if (verdict.blocked && QueryLog.enabled) {
            SessionStats.recordBlocked(name)
            if (app != null) SessionStats.recordBlockedApp(app)
        }
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
        dotClient()?.query(dns)?.takeIf { it.size <= MAX_RESPONSE }?.let {
            Counters.dot.incrementAndGet()
            return it
        }
        return forwardUdp(dns)
    }

    /**
     * 通常の DNS (UDP) で問い合わせる。1 台目に送り、STAGGER_MS 待っても答えが無ければ 2 台目にも送る、というふうに
     * 最大 MAX_PARALLEL 台まで重ねて送り、最初に届いた答えを使う。応答しないサーバーがあっても、そのタイムアウトを待たない。
     */
    private fun forwardUdp(dns: ByteArray): ByteArray? {
        val servers = upstream.servers().take(MAX_PARALLEL)
        if (servers.isEmpty() || dns.size < 2) return null
        try {
            DatagramSocket().use { socket ->
                protect(socket)
                val sent = HashSet<java.net.InetAddress>()
                val rb = ByteArray(MAX_RESPONSE)
                val rp = DatagramPacket(rb, rb.size)
                val deadline = System.currentTimeMillis() + UPSTREAM_TIMEOUT_MS
                var next = 0
                while (true) {
                    if (next < servers.size) {
                        val server = servers[next++]
                        try {
                            socket.send(DatagramPacket(dns, dns.size, server, 53))
                            sent += server
                        } catch (e: IOException) {
                            // その回線では届かないアドレス (IPv6 が無い等)。待たずに次へ
                            Log.d(TAG, "send to $server failed: $e")
                            noteSendFailure(server, e)
                            continue
                        }
                    }
                    if (sent.isEmpty()) return null // どこにも送れなかった
                    val waitUntil = if (next < servers.size) minOf(System.currentTimeMillis() + STAGGER_MS, deadline) else deadline
                    while (true) {
                        val remaining = waitUntil - System.currentTimeMillis()
                        if (remaining <= 0) break
                        socket.soTimeout = remaining.toInt()
                        // 前の受信で長さが縮んでいるので、毎回バッファ全体に戻す
                        rp.setLength(rb.size)
                        try {
                            socket.receive(rp)
                        } catch (_: java.net.SocketTimeoutException) {
                            break
                        }
                        // 送った相手からの、同じ ID の答えだけを受け取る (ほかからのパケットは無視)
                        if (rp.address in sent && rp.length >= 12 && rb[0] == dns[0] && rb[1] == dns[1]) {
                            upstream.markWorking(rp.address)
                            return rb.copyOf(rp.length)
                        }
                    }
                    if (System.currentTimeMillis() >= deadline) return null
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "upstream query failed: $e")
            return null
        }
    }

    private fun writePacket(out: FileOutputStream, packet: ByteArray) {
        synchronized(out) {
            try {
                out.write(packet)
            } catch (e: IOException) {
                Log.d(TAG, "tun write failed: $e")
                if (DebugLog.enabled) DebugLog.w(TAG, "tun write failed (${packet.size} bytes)", e)
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
