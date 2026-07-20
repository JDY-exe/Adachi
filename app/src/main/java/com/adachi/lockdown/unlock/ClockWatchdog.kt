package com.adachi.lockdown.unlock

import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.adachi.lockdown.AdachiApp
import com.adachi.lockdown.R
import com.adachi.lockdown.data.RulesRepository
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var started = false

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
            if (result.tampered) onTamper(appContext, repo)
        }
    }

    private suspend fun check(context: Context) {
        val repo = RulesRepository.get(context)
        val state = repo.unlockStateNow()
        val result = UnlockManager.updateWatermark(
            state,
            System.currentTimeMillis(),
            SystemClock.elapsedRealtime(),
        )
        repo.saveUnlockState(result.state)
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
