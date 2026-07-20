package com.adachi.lockdown

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.adachi.lockdown.unlock.ClockWatchdog
import com.adachi.lockdown.unlock.UnlockNotifier
import com.adachi.lockdown.unlock.UnlockWindowReactor

class AdachiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        ClockWatchdog.start(this)
        UnlockNotifier.start(this)
        UnlockWindowReactor.start(this)
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ENFORCEMENT,
                "Enforcement",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Persistent enforcement status" }
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
