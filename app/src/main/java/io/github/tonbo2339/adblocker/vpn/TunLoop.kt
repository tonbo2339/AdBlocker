package io.github.tonbo2339.adblocker.vpn

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import io.github.tonbo2339.adblocker.dns.Packets
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService

/**
 * tun からクエリを読み続けて [QueryHandler] に渡す。停止要求 (stopFd に書き込みがあった) で抜けたら true、tun が閉じられたら false。
 * 呼ぶスレッドを占有する (VPN のワーカースレッドが呼ぶ)。
 */
internal fun runTunLoop(tunFd: FileDescriptor, stopFd: FileDescriptor, executor: ExecutorService, handler: QueryHandler): Boolean {
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
            Packets.tcpReset(buf, n)?.let { handler.writePacket(out, it) }
            continue
        }
        handler.handle(query, out, executor)
    }
}
