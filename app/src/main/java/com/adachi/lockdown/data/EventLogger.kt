package com.adachi.lockdown.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Verbose diagnostic logging feeding the in-app log screen.
 *
 * Thread-safe and non-suspending: callers (VPN packet thread, accessibility
 * callbacks) just enqueue. A flusher batches rows into Room every ~1.5s so
 * chatty DNS traffic doesn't thrash the DB, and Room's observable query makes
 * the UI feed live. Everything is mirrored to logcat (tag "Adachi").
 *
 * Callers throttle high-frequency categories via [throttleKey]/[throttleMs]
 * (e.g. one "forwarded" line per domain per minute).
 */
object EventLogger {

    enum class Kind { VPN, DNS, UPSTREAM, APP }
    enum class Level { BLOCK, ALLOW, ERROR, INFO }

    private const val TAG = "Adachi"
    private const val FLUSH_INTERVAL_MS = 1_500L
    private const val FLUSH_THRESHOLD = 25
    private const val RETENTION_MS = 3L * 24 * 3600 * 1000
    private const val MAX_THROTTLE_KEYS = 5_000

    @Volatile private var repo: RulesRepository? = null

    private val queue = ConcurrentLinkedQueue<EventLog>()
    private val throttledAt = ConcurrentHashMap<String, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        if (repo != null) return
        val r = RulesRepository.get(context.applicationContext)
        repo = r
        scope.launch {
            runCatching { r.pruneEvents(System.currentTimeMillis() - RETENTION_MS) }
            while (true) {
                delay(FLUSH_INTERVAL_MS)
                flush()
            }
        }
    }

    fun log(
        kind: Kind,
        level: Level,
        message: String,
        throttleKey: String? = null,
        throttleMs: Long = 0,
    ) {
        when (level) {
            Level.ERROR -> Log.e(TAG, "$kind: $message")
            else -> Log.d(TAG, "$kind/$level: $message")
        }
        if (throttleKey != null) {
            val now = System.currentTimeMillis()
            val last = throttledAt[throttleKey] ?: 0L
            if (now - last < throttleMs) return
            throttledAt[throttleKey] = now
            // Bound the map so a long-lived process can't grow it forever.
            if (throttledAt.size > MAX_THROTTLE_KEYS) throttledAt.clear()
        }
        queue.add(
            EventLog(
                epochMs = System.currentTimeMillis(),
                kind = kind.name,
                level = level.name,
                message = message,
            ),
        )
        if (queue.size >= FLUSH_THRESHOLD) scope.launch { flush() }
    }

    private suspend fun flush() {
        val r = repo ?: return
        val batch = mutableListOf<EventLog>()
        while (true) {
            batch += queue.poll() ?: break
        }
        if (batch.isEmpty()) return
        runCatching { r.logEvents(batch) }
    }
}
