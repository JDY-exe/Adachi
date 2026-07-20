package com.adachi.lockdown.vpn

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Relays DNS queries to an upstream resolver over plain UDP through a socket
 * that bypasses the VPN (via [protect]). Responses are correlated to callbacks
 * by DNS message ID.
 */
class DnsForwarder(
    protect: (DatagramSocket) -> Boolean,
    private val scope: CoroutineScope,
    upstreamIp: String = "1.1.1.1",
    upstreamPort: Int = 53,
    private val timeoutMs: Long = 5000,
) {
    private val socket: DatagramSocket = DatagramSocket().also {
        if (!protect(it)) Log.w(TAG, "Failed to protect upstream socket")
        it.connect(InetAddress.getByName(upstreamIp), upstreamPort)
    }

    private val pending = ConcurrentHashMap<Int, (ByteArray) -> Unit>()

    @Volatile
    private var alive = true

    private val reader = thread(name = "adachi-dns-reader", isDaemon = true) {
        val buf = ByteArray(4096)
        while (alive) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                val id = DnsCodec.responseId(buf, packet.length) ?: continue
                pending.remove(id)?.invoke(buf.copyOf(packet.length))
            } catch (e: Exception) {
                if (alive) Log.w(TAG, "Upstream receive error", e)
            }
        }
    }

    /** Send [query] upstream; [onResponse] fires at most once (response or timeout). */
    fun forward(query: ByteArray, onResponse: (ByteArray) -> Unit) {
        if (!alive) return
        val id = DnsCodec.responseId(query) ?: return
        pending[id] = onResponse
        scope.launch {
            delay(timeoutMs)
            pending.remove(id)
        }
        try {
            socket.send(DatagramPacket(query, query.size))
        } catch (e: Exception) {
            pending.remove(id)
            Log.w(TAG, "Upstream send error", e)
        }
    }

    fun close() {
        alive = false
        runCatching { socket.close() }
    }

    private companion object {
        const val TAG = "DnsForwarder"
    }
}
