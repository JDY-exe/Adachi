package com.adachi.lockdown.unlock

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.adachi.lockdown.AdachiApp
import com.adachi.lockdown.R
import com.adachi.lockdown.data.RulesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Shows a persistent countdown notification while an unlock/malfunction pause
 * window is active, and clears it when enforcement re-arms.
 */
object UnlockNotifier {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        scope.launch {
            RulesRepository.get(appContext).unlockState().collectLatest { state ->
                while (UnlockManager.isActive(state, System.currentTimeMillis())) {
                    notify(appContext, UnlockManager.remainingMs(state, System.currentTimeMillis()))
                    delay(30_000)
                }
                cancel(appContext)
            }
        }
    }

    private fun notify(context: Context, remainingMs: Long) {
        val minutes = (remainingMs / 60_000).coerceAtLeast(1)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(
            AdachiApp.NOTIF_ID_UNLOCK,
            NotificationCompat.Builder(context, AdachiApp.CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Adachi unlocked")
                .setContentText("Lockdown resumes in ~$minutes min")
                .setOngoing(true)
                .build(),
        )
    }

    private fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(AdachiApp.NOTIF_ID_UNLOCK)
    }
}
