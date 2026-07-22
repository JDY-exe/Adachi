package com.adachi.lockdown.vpn

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Relays DNS queries to an upstream resolver over plain UDP through a socket
 * that bypasses the VPN (via [protect]). Responses are correlated to callbacks
 * by DNS message ID.
 *
 * Health events (protect failure, send/receive errors, timeouts, recovery) are
 * reported via [onEvent] so the caller can surface them in the log feed — a
 * silently dead upstream (e.g. after a network switch) is indistinguishable
 * from "everything is blocked" without this.
 */
class DnsForwarder(
    protect: (DatagramSocket) -> Boolean,
    private val scope: CoroutineScope,
    upstreamIp: String = "1.1.1.1",
    upstreamPort: Int = 53,
    private val timeoutMs: Long = 5000,
    /** Fired once when [DEAD_THRESHOLD] consecutive queries time out — the socket is presumed dead. */
    private val onDead: () -> Unit = {},
    private val onEvent: (level: String, message: String) -> Unit = { _, _ -> },
) {
    private val socket: DatagramSocket = DatagramSocket().also {
        if (!protect(it)) {
            Log.w(TAG, "Failed to protect upstream socket")
            onEvent("ERROR", "failed to protect upstream socket — DNS will loop into the tunnel and fail")
        }
        it.connect(InetAddress.getByName(upstreamIp), upstreamPort)
    }

    private val pending = ConcurrentHashMap<Int, (ByteArray) -> Unit>()

    @Volatile
    private var alive = true

    /** Consecutive queries that got no upstream response; reset on any reply. */
    private val consecutiveTimeouts = AtomicInteger(0)

    /**
     * Consecutive send() failures; reset on a successful send. A dead route
     * (ENETUNREACH after a network switch) fails every send instantly, so
     * timeouts never accumulate — this counter is what catches that case.
     */
    private val consecutiveSendErrors = AtomicInteger(0)

    @Volatile
    private var lastReceiveErrorAt = 0L

    private val reader = thread(name = "adachi-dns-reader", isDaemon = true) {
        val buf = ByteArray(4096)
        while (alive) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                val id = DnsCodec.responseId(buf, packet.length) ?: continue
                pending.remove(id)?.invoke(buf.copyOf(packet.length))
                if (consecutiveTimeouts.getAndSet(0) >= TIMEOUT_WARN_THRESHOLD) {
                    onEvent("INFO", "upstream is responding again")
                }
            } catch (e: Exception) {
                if (alive) {
                    Log.w(TAG, "Upstream receive error", e)
                    val now = System.currentTimeMillis()
                    if (now - lastReceiveErrorAt > 10_000) {
                        lastReceiveErrorAt = now
                        onEvent("ERROR", "upstream receive error: ${e.message}")
                    }
                }
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
            if (pending.remove(id) != null) {
                val n = consecutiveTimeouts.incrementAndGet()
                if (n == TIMEOUT_WARN_THRESHOLD || n % 60 == 0) {
                    onEvent(
                        "ERROR",
                        "no response from upstream for $n consecutive queries " +
                            "(network switch or blocked UDP/53?) — DNS is failing",
                    )
                }
                if (n == DEAD_THRESHOLD) onDead()
            }
        }
        try {
            socket.send(DatagramPacket(query, query.size))
            consecutiveSendErrors.set(0)
        } catch (e: Exception) {
            pending.remove(id)
            val n = consecutiveSendErrors.incrementAndGet()
            Log.w(TAG, "Upstream send error", e)
            if (n == 1 || n % 30 == 0) {
                onEvent("ERROR", "upstream send error x$n: ${e.message}")
            }
            if (n == SEND_ERROR_DEAD_THRESHOLD) onDead()
        }
    }

    fun close() {
        alive = false
        runCatching { socket.close() }
    }

    private companion object {
        const val TAG = "DnsForwarder"
        const val TIMEOUT_WARN_THRESHOLD = 5
        const val DEAD_THRESHOLD = 10
        const val SEND_ERROR_DEAD_THRESHOLD = 3
    }
}
