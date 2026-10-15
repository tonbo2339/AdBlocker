package io.github.tonbo2339.adblocker

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 一時停止。VPN は動かしたまま、期限までブロックせずにすべて通す
 * (VPN を作り直さないので切り替えが速く、プロセスが終了させられても期限は残る)。
 */
object Pause {
    @Volatile
    private var until = 0L

    /** 期限が来たことを (タイル・通知に) 知らせ終えたか。一時停止していない間も true。 */
    @Volatile
    private var expiryShown = true

    private val handler = Handler(Looper.getMainLooper())

    /** 一時停止・再開・期限切れのときに (メインスレッドで) 呼ばれる。 */
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    /** プロセスの開始時に呼ぶ。一時停止中なら、期限が来たときの表示の戻しも予約し直す。 */
    fun load(context: Context) {
        until = Prefs.pausedUntil(context)
        scheduleExpiry(context.applicationContext)
    }

    /** 再開する時刻。 */
    val resumesAt: Long get() = until

    fun isPaused(now: Long = System.currentTimeMillis()): Boolean = now < until

    /** 残り時間 (ミリ秒)。一時停止していなければ 0。 */
    fun remaining(now: Long = System.currentTimeMillis()): Long = (until - now).coerceAtLeast(0)

    fun start(context: Context, durationMs: Long) = set(context, System.currentTimeMillis() + durationMs)

    fun resume(context: Context) = set(context, 0)

    private fun set(context: Context, time: Long) {
        DebugLog.i("Pause", if (time == 0L) "resumed" else "paused for ${(time - System.currentTimeMillis()) / 1000} s")
        val app = context.applicationContext
        until = time
        expiryShown = time == 0L
        Prefs.setPausedUntil(app, time)
        changed(app)
        scheduleExpiry(app)
    }

    /**
     * 期限が来たらタイルの「一時停止中」と常駐通知を戻す (プロセスが終了していたら、次にパネルを開いたときに戻る)。
     * 一時停止中にプロセスが作り直されたとき (常時接続 VPN・再起動後の再開) も、load() から予約し直す。
     * Handler の待ち時間は端末が眠っている間は進まないので、眠っていた間に期限が過ぎたときは notifyIfExpired() が戻す。
     */
    private fun scheduleExpiry(app: Context) {
        handler.removeCallbacksAndMessages(null)
        val delay = until - System.currentTimeMillis()
        expiryShown = delay <= 0
        if (delay > 0) handler.postDelayed({ notifyIfExpired(app) }, delay)
    }

    /**
     * 一時停止の期限が過ぎているのに、まだ表示を戻していなければ戻す。メインスレッドで、定期的に (VPN サービスの保存と一緒に) 呼ぶ。
     * 期限前に呼んでも何もしない。ブロックの再開そのものは isPaused() が時刻で判定するので、これは表示のため。
     */
    fun notifyIfExpired(context: Context) {
        if (expiryShown || System.currentTimeMillis() < until) return
        expiryShown = true
        changed(context.applicationContext)
    }

    private fun changed(context: Context) {
        AdBlockTileService.refresh(context)
        for (l in listeners) l()
    }
}
