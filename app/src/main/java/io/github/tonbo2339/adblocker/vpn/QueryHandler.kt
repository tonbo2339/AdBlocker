package io.github.tonbo2339.adblocker.vpn

import android.util.Log
import io.github.tonbo2339.adblocker.block.Filter
import io.github.tonbo2339.adblocker.block.Verdict
import io.github.tonbo2339.adblocker.data.StatsStore
import io.github.tonbo2339.adblocker.dns.Dns
import io.github.tonbo2339.adblocker.dns.DnsCache
import io.github.tonbo2339.adblocker.dns.DnsDebugStats
import io.github.tonbo2339.adblocker.dns.DnsQuery
import io.github.tonbo2339.adblocker.dns.Packets
import io.github.tonbo2339.adblocker.dns.Question
import io.github.tonbo2339.adblocker.dns.UpstreamDns
import io.github.tonbo2339.adblocker.log.DebugLog
import io.github.tonbo2339.adblocker.log.QueryLog
import io.github.tonbo2339.adblocker.log.SessionStats
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService

/**
 * tun から読んだ DNS の問い合わせ 1 件の処理: 判定 (ブロック・キャッシュ・転送) と、答えを tun に書くこと、統計とログの記録。
 * VPN の作り直しをまたいで使い回してよい (状態は cache と forwarder が持つ)。
 */
internal class QueryHandler(
    private val forwarder: DnsForwarder,
    private val cache: DnsCache,
    private val upstream: UpstreamDns,
    private val apps: QueryOwners,
) {

    /**
     * どのアプリの問い合わせか (ログがオンのときだけ)。応答を返すとアプリがソケットを閉じて分からなくなるので、返す前に調べる。
     * binder 呼び出しなので、tun の読み取りスレッドで呼ぶのは、その場で答えるとき (ブロック・キャッシュ) だけにする。
     */
    private fun ownerOf(query: DnsQuery): String? = if (QueryLog.enabled) apps.ownerOf(query) else null

    fun handle(query: DnsQuery, out: FileOutputStream, executor: ExecutorService) {
        val question = Dns.parseQuestion(query.dns)
        if (question == null) {
            // 質問を読めない問い合わせ。黙って捨てるとアプリがタイムアウトまで待つので、FORMERR で知らせる (応答は無視)
            Dns.formerr(query.dns)?.let { writePacket(out, Packets.buildResponse(query, it)) }
            return
        }
        val verdict = Filter.decide(question.name)
        DnsDebugStats.query(verdict.blocked)
        if (verdict.blocked) {
            record(question.name, verdict, app = ownerOf(query))
            writePacket(out, Packets.buildResponse(query, Dns.blocked(query.dns, question)))
            return
        }
        // 覚えている答えがあれば、転送せずにすぐ返す (答えの中身による判定はルールが変わることがあるので毎回行う)
        val key = Dns.cacheKey(query.dns, question)
        cache.get(query.dns, question, key)?.let { cached ->
            DnsDebugStats.cached()
            SessionStats.recordCacheHit()
            answer(query, question, verdict, cached, ownerOf(query), out, QueryLog.FROM_CACHE)
            return
        }
        executor.execute {
            // 転送する間、アプリは答えを待ってソケットを開けたままなので、読み取りスレッドの外で調べてよい
            val app = ownerOf(query)
            val started = System.nanoTime()
            val response = forwarder.forward(query.dns)
            if (response == null) {
                DnsDebugStats.failed { upstream.servers() }
                // 転送先が 1 つも答えなかった。何も返さないとアプリはタイムアウトまで待つので、すぐ失敗を知らせる
                SessionStats.recordNoAnswer()
                record(question.name, verdict, app = app, ms = QueryLog.NO_ANSWER)
                writePacket(out, Packets.buildResponse(query, Dns.servfail(query.dns, question)))
                return@execute
            }
            val ms = (System.nanoTime() - started) / 1_000_000
            DnsDebugStats.forwarded(ms)
            SessionStats.recordForwarded(ms)
            cache.put(query.dns, question, response, key)
            answer(query, question, verdict, response, app, out, ms.toInt())
        }
    }

    /** 転送先 (またはキャッシュ) の答えを返す。答えの中身 (CNAME 隠し・IP) でブロックすることもある。 */
    private fun answer(query: DnsQuery, question: Question, verdict: Verdict, response: ByteArray, app: String?, out: FileOutputStream, ms: Int) {
        if (verdict == Verdict.PASS) {
            Filter.checkResponse(response)?.let { block ->
                record(question.name, block.verdict, block.via, app, ms)
                writePacket(out, Packets.buildResponse(query, Dns.blocked(query.dns, question)))
                return
            }
        }
        record(question.name, verdict, app = app, ms = ms)
        writePacket(out, Packets.buildResponse(query, response))
    }

    private fun record(name: String, verdict: Verdict, via: String? = null, app: String? = null, ms: Int = QueryLog.NO_TIME) {
        StatsStore.record(verdict.blocked)
        QueryLog.add(name, verdict, via, app, ms)
        if (verdict.blocked && QueryLog.enabled) {
            SessionStats.recordBlocked(name)
            if (app != null) SessionStats.recordBlockedApp(app)
        }
    }

    /** tun にパケットを書く (読み取りスレッドと転送のスレッドから呼ばれるので、書くたびに out をロックする)。 */
    fun writePacket(out: FileOutputStream, packet: ByteArray) {
        synchronized(out) {
            try {
                out.write(packet)
            } catch (e: IOException) {
                Log.d(VPN_TAG, "tun write failed: $e")
                if (DebugLog.enabled) DebugLog.w(VPN_TAG, "tun write failed (${packet.size} bytes)", e)
            }
        }
    }
}
