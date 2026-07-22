package com.adachi.lockdown

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.adachi.lockdown.data.EventLogger
import com.adachi.lockdown.unlock.ClockWatchdog
import com.adachi.lockdown.unlock.UnlockNotifier
import com.adachi.lockdown.unlock.UnlockWindowReactor

class AdachiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        EventLogger.init(this)
        createNotificationChannels()
        ClockWatchdog.start(this)
        UnlockNotifier.start(this)
        UnlockWindowReactor.start(this)
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        // Channel importance is immutable once created; delete + recreate so
        // existing installs drop to IMPORTANCE_MIN (no status-bar icon, collapsed
        // at the bottom of the shade). The notification itself must still exist —
        // startForeground requires it to keep the VPN service alive.
        nm.deleteNotificationChannel(CHANNEL_ENFORCEMENT)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ENFORCEMENT,
                "Enforcement",
                NotificationManager.IMPORTANCE_MIN,
            ).apply { description = "Persistent enforcement status (hidden)" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                "Alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Unlock countdowns and malfunction alerts" }
        )
    }

    companion object {
        const val CHANNEL_ENFORCEMENT = "enforcement"
        const val CHANNEL_ALERTS = "alerts"
        const val NOTIF_ID_VPN = 1
        const val NOTIF_ID_UNLOCK = 2
        const val NOTIF_ID_FAILSAFE = 3
    }
}
