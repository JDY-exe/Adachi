package com.adachi.lockdown.status

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * Read-only view of how fully the system is armed. Components are referenced
 * by class-name strings to keep this file free of service dependencies.
 */
object SystemStatus {

    private const val ACCESSIBILITY_SERVICE_CLASS = "com.adachi.lockdown.apps.AppBlockerService"
    private const val PREFS = "adachi_runtime"
    private const val KEY_VPN_RUNNING = "vpn_running"

    fun isDeviceOwner(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(context.packageName) == true

    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context.packageName, ACCESSIBILITY_SERVICE_CLASS).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun isVpnRunning(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_VPN_RUNNING, false)

    fun setVpnRunning(context: Context, running: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_VPN_RUNNING, running).apply()
    }

    fun isAlwaysOnVpnLocked(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        val admin = ComponentName(context.packageName, "com.adachi.lockdown.admin.AdminReceiver")
        return try {
            dpm.isAlwaysOnVpnLockdownEnabled(admin)
        } catch (e: SecurityException) {
            false
        }
    }
}
