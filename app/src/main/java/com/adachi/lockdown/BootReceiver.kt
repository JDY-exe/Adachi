package com.adachi.lockdown

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adachi.lockdown.unlock.ClockWatchdog
import com.adachi.lockdown.vpn.AdachiVpnService

/**
 * Re-arms enforcement after boot / app update. Clock-tamper re-anchoring
 * lands in the unlock batch.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                ClockWatchdog.reanchorNow(context)
                // Starting a foreground service from boot may throw on newer
                // Android versions pre-provisioning; never crash the receiver.
                runCatching { AdachiVpnService.start(context) }
            }
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> ClockWatchdog.checkNow(context)
        }
    }
}
