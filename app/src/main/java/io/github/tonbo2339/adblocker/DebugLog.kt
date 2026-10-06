package io.github.tonbo2339.adblocker

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 開発者モードのデバッグログ (既定はオフ)。VPN の開始・停止、転送先 DNS、失敗などを端末のファイルに残し、
 * 実機で起きた不具合を後から調べられるようにする。
 *
 * どのサイトを見たかが分からないよう、ドメイン名は書かない。端末の外には送らない (書き出しは利用者が行う)。
 * Android の Log にも同じ内容を出す。
 */
object DebugLog {
    private const val FILE = "debug.log"
    private const val OLD_FILE = "debug.log.1"

    /** 1 ファイルの上限。超えたら 1 世代だけ残して新しいファイルにする (合わせて最大 1MB)。 */
    private const val MAX_BYTES = 512 * 1024L

    @Volatile
    var enabled = false
        private set

    private var dir: File? = null

    /** ファイルへの書き込みは 1 本のスレッドで順に行う (呼び出し元を待たせない)。 */
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "DebugLog").apply { isDaemon = true } }
    private val time = SimpleDateFormat("yyyy/MM/dd HH:mm:ss.SSS", Locale.US)

    /** プロセスの開始時と、設定を変えたときに呼ぶ。オフにしたら残したログを消す。 */
    fun configure(context: Context, enabled: Boolean) {
        dir = context.filesDir
        this.enabled = enabled
        if (!enabled) clear()
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        write(tag, message)
    }

    /** 通信の失敗 (IOException) は 1 行、想定外の例外はスタックトレースの先頭も残す。 */
    fun w(tag: String, message: String, e: Throwable? = null) {
        Log.w(tag, message, e)
        if (!enabled) return
        write(
            tag,
            when (e) {
                null -> message
                is IOException -> "$message: $e"
                else -> "$message: $e\n" + e.stackTrace.take(12).joinToString("\n") { "    at $it" }
            },
        )
    }

    private fun write(tag: String, message: String) {
        if (!enabled) return
        val line = synchronized(time) { time.format(Date()) } + " " + tag + ": " + message + "\n"
        writer.execute { append(line) }
    }

    private fun append(line: String) {
        val dir = dir ?: return
        val file = File(dir, FILE)
        try {
            if (file.length() > MAX_BYTES) file.renameTo(File(dir, OLD_FILE))
            FileOutputStream(file, true).use { it.write(line.toByteArray()) }
        } catch (e: IOException) {
            Log.w("DebugLog", "write failed", e)
        }
    }

    /** 残したログの大きさ (バイト)。 */
    fun size(): Long = files().sumOf { it.length() }

    /** header (端末とアプリの状態) に続けて、古い順にログを書き出す。書き込み待ちの行も含める。 */
    fun export(out: OutputStream, header: String) {
        writer.submit {}.get()
        out.write(header.toByteArray())
        out.write("\n".toByteArray())
        for (f in files().reversed()) f.inputStream().use { it.copyTo(out) }
    }

    /** 残したログを消す。onDone は消し終わったあとに書き込み用のスレッドから呼ばれる。 */
    fun clear(onDone: () -> Unit = {}) {
        writer.execute {
            files().forEach { it.delete() }
            onDone()
        }
    }

    /** 新しい順。 */
    private fun files(): List<File> {
        val dir = dir ?: return emptyList()
        return listOf(File(dir, FILE), File(dir, OLD_FILE)).filter { it.exists() }
    }
}
