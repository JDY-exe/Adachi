package com.adachi.lockdown.unlock

import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.adachi.lockdown.AdachiApp
import com.adachi.lockdown.R
import com.adachi.lockdown.data.RulesRepository
import com.adachi.lockdown.data.UnlockState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Maintains the UTC watermark and responds to manual clock changes.
 *
 * With DISALLOW_CONFIG_DATE_TIME applied the clock normally can't be changed —
 * except during Travel-mode / unlock windows, which is exactly what this guards.
 * Response to a detected jump: consume the current week's emergency unlock
 * (the user is warned of this before Travel mode lifts the restriction).
 */
object ClockWatchdog {

    private const val TAG = "ClockWatchdog"
    private const val CHECK_INTERVAL_MS = 60_000L
    /**
     * How often the advancing watermark is persisted. Writing on every 60s
     * check would thrash Room (and re-emit unlockState() to every collector)
     * once a minute; checks run against the exact in-memory watermark, so
     * persisting every 15 min loses nothing except after a process restart
     * (bounded, like CLOCK_TOLERANCE_MS). Tamper results always persist.
     */
    private const val PERSIST_INTERVAL_MS = UnlockManager.CLOCK_TOLERANCE_MS

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var started = false

    /**
     * Exact, up-to-the-minute watermark (the DB copy lags by up to
     * PERSIST_INTERVAL_MS). The watchdog owns ONLY these two fields; when
     * persisting, they are merged onto a freshly read row so concurrent
     * writers (unlock spends, provisioning) are never clobbered.
     * wmUtcMs < 0 means "not seeded from the DB yet".
     */
    @Volatile
    private var wmUtcMs = -1L
    @Volatile
    private var wmElapsedMs = 0L
    @Volatile
    private var lastPersistedWatermarkMs = 0L

    fun start(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        scope.launch {
            while (true) {
                check(appContext)
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    /** Run a watermark check now (e.g. on TIME_SET / TIMEZONE_CHANGED broadcasts). */
    fun checkNow(context: Context) {
        val appContext = context.applicationContext
        scope.launch { check(appContext) }
    }

    /** Re-anchor after boot (elapsedRealtime resets). Safe to call repeatedly. */
    fun reanchorNow(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            val repo = RulesRepository.get(appContext)
            val state = repo.unlockStateNow()
            val result = UnlockManager.reanchorAfterBoot(
                state,
                System.currentTimeMillis(),
                SystemClock.elapsedRealtime(),
            )
            repo.saveUnlockState(result.state)
            wmUtcMs = result.state.utcWatermarkMs
            wmElapsedMs = result.state.watermarkElapsedMs
            lastPersistedWatermarkMs = result.state.utcWatermarkMs
            if (result.tampered) onTamper(appContext, repo)
        }
    }

    private suspend fun check(context: Context) {
        val repo = RulesRepository.get(context)
        if (wmUtcMs < 0) {
            val seeded = repo.unlockStateNow()
            wmUtcMs = seeded.utcWatermarkMs
            wmElapsedMs = seeded.watermarkElapsedMs
            lastPersistedWatermarkMs = seeded.utcWatermarkMs
        }
        val firstInit = wmUtcMs <= 0L
        val result = UnlockManager.updateWatermark(
            UnlockState(utcWatermarkMs = wmUtcMs, watermarkElapsedMs = wmElapsedMs),
            System.currentTimeMillis(),
            SystemClock.elapsedRealtime(),
        )
        wmUtcMs = result.state.utcWatermarkMs
        wmElapsedMs = result.state.watermarkElapsedMs
        if (result.tampered || firstInit || wmUtcMs - lastPersistedWatermarkMs >= PERSIST_INTERVAL_MS) {
            // Merge the watermark onto the current row: other fields may have
            // been written by unlock spends etc. since our last DB read.
            val fresh = repo.unlockStateNow()
            repo.saveUnlockState(fresh.copy(utcWatermarkMs = wmUtcMs, watermarkElapsedMs = wmElapsedMs))
            lastPersistedWatermarkMs = wmUtcMs
        }
        if (result.tampered) onTamper(context, repo)
    }

    private suspend fun onTamper(context: Context, repo: RulesRepository) {
        Log.w(TAG, "Clock tampering detected — consuming this week's unlock")
        val consumed = UnlockManager.consumeForTamper(repo.unlockStateNow(), LocalDate.now())
        // Re-anchor so we don't re-fire on the same jump.
        val reanchored = UnlockManager.reanchorAfterBoot(
            consumed,
            System.currentTimeMillis(),
            SystemClock.elapsedRealtime(),
        ).state
        repo.saveUnlockState(reanchored)
        wmUtcMs = reanchored.utcWatermarkMs
        wmElapsedMs = reanchored.watermarkElapsedMs
        lastPersistedWatermarkMs = reanchored.utcWatermarkMs
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(
            AdachiApp.NOTIF_ID_FAILSAFE,
            NotificationCompat.Builder(context, AdachiApp.CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Clock change detected")
                .setContentText("Changing the date consumes this week's emergency unlock.")
                .build(),
        )
    }
}
