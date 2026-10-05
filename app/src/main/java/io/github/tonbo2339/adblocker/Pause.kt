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

    private val handler = Handler(Looper.getMainLooper())

    /** 一時停止・再開・期限切れのときに (メインスレッドで) 呼ばれる。 */
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    fun load(context: Context) {
        until = Prefs.pausedUntil(context)
    }

    /** 再開する時刻。 */
    val resumesAt: Long get() = until

    fun isPaused(now: Long = System.currentTimeMillis()): Boolean = now < until

    /** 残り時間 (ミリ秒)。一時停止していなければ 0。 */
    fun remaining(now: Long = System.currentTimeMillis()): Long = (until - now).coerceAtLeast(0)

    fun start(context: Context, durationMs: Long) = set(context, System.currentTimeMillis() + durationMs)

    fun resume(context: Context) = set(context, 0)

    private fun set(context: Context, time: Long) {
        val app = context.applicationContext
        until = time
        Prefs.setPausedUntil(app, time)
        changed(app)
        // 期限が来たらタイルの「一時停止中」を戻す (プロセスが終了していたら、次にパネルを開いたときに戻る)
        handler.removeCallbacksAndMessages(null)
        if (time > System.currentTimeMillis()) {
            handler.postDelayed({ changed(app) }, time - System.currentTimeMillis())
        }
    }

    private fun changed(context: Context) {
        AdBlockTileService.refresh(context)
        for (l in listeners) l()
    }
}
