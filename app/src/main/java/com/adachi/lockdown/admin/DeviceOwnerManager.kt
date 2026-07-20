package com.adachi.lockdown.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.util.Log
import com.adachi.lockdown.data.RulesRepository
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * All device-owner policy operations: applying the lockdown restrictions,
 * temporary lifts (Travel mode, ADB window), and the full ordered teardown.
 *
 * Nothing in here works unless the app is provisioned as device owner via
 * `adb shell dpm set-device-owner <pkg>/.admin.AdminReceiver`.
 */
object DeviceOwnerManager {

    private const val TAG = "DeviceOwnerManager"

    /** Restrictions applied during lockdown. NOT included (deliberately):
     *  DISALLOW_FACTORY_RESET — factory reset is the user's ultimate escape hatch. */
    private val LOCKDOWN_RESTRICTIONS = listOf(
        UserManager.DISALLOW_DEBUGGING_FEATURES,   // no ADB
        UserManager.DISALLOW_SAFE_BOOT,            // no safe-mode bypass
        UserManager.DISALLOW_APPS_CONTROL,         // no force-stop / clear-data
        UserManager.DISALLOW_CONFIG_VPN,           // VPN settings locked
        UserManager.DISALLOW_ADD_USER,             // no fresh user profile escape
        UserManager.DISALLOW_CONFIG_DATE_TIME,     // no clock-gaming schedules/unlocks
    )

    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    fun adminComponent(context: Context): ComponentName =
        ComponentName(context.packageName, AdminReceiver::class.java.name)

    fun isDeviceOwner(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)
            ?.isDeviceOwnerApp(context.packageName) == true

    /**
     * Apply the full lockdown. Records the provisioning time (starts the 48h
     * grace period) — idempotent, re-recording only if never provisioned.
     */
    suspend fun applyLockdown(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
            ?: error("DevicePolicyManager unavailable")
        val admin = adminComponent(context)
        require(isDeviceOwner(context)) { "Adachi is not device owner" }

        // Automatic date/time/timezone ON first (travel keeps working), then lock the screen.
        dpm.setAutoTimeEnabled(admin, true)
        dpm.setAutoTimeZoneEnabled(admin, true)

        for (restriction in LOCKDOWN_RESTRICTIONS) {
            dpm.addUserRestriction(admin, restriction)
        }
        dpm.setUninstallBlocked(admin, context.packageName, true)

        // Always-on VPN WITHOUT lockdown flag: if the VPN dies, traffic still
        // flows (fail-open beats bricked internet; VPN can't be killed by the
        // user anyway under DISALLOW_APPS_CONTROL).
        dpm.setAlwaysOnVpnPackage(admin, context.packageName, false)

        val repo = RulesRepository.get(context)
        val state = repo.unlockStateNow()
        if (state.provisionedAtMs <= 0) {
            repo.saveUnlockState(state.copy(provisionedAtMs = System.currentTimeMillis()))
        }
        Log.i(TAG, "Lockdown restrictions applied")
    }

    // ---------------- Temporary lifts ----------------

    /**
     * Travel mode: lift the date-time restriction so the timezone can be set
     * manually; re-applies automatically after [minutes] (default 5). Changing
     * the *date* during this window is caught by the ClockWatchdog and consumes
     * the weekly unlock.
     */
    fun travelMode(context: Context, minutes: Long = 5) {
        liftRestriction(context, UserManager.DISALLOW_CONFIG_DATE_TIME)
        scheduleReapply(context, UserManager.DISALLOW_CONFIG_DATE_TIME, minutes)
    }

    /** Re-enable ADB for [minutes] (debugging during an unlock window). */
    fun enableAdbTemporarily(context: Context, minutes: Long = 30) {
        liftRestriction(context, UserManager.DISALLOW_DEBUGGING_FEATURES)
        scheduleReapply(context, UserManager.DISALLOW_DEBUGGING_FEATURES, minutes)
    }

    private fun liftRestriction(context: Context, restriction: String) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!isDeviceOwner(context)) return
        runCatching { dpm.clearUserRestriction(adminComponent(context), restriction) }
    }

    private fun scheduleReapply(context: Context, restriction: String, minutes: Long) {
        val appContext = context.applicationContext
        scheduler.schedule({
            val dpm = appContext.getSystemService(DevicePolicyManager::class.java) ?: return@schedule
            if (isDeviceOwner(appContext)) {
                runCatching { dpm.addUserRestriction(adminComponent(appContext), restriction) }
            }
        }, minutes, TimeUnit.MINUTES)
    }

    // ---------------- Teardown ----------------

    /**
     * Full, ordered deactivation: remove always-on VPN, lift every restriction,
     * unblock uninstall, then relinquish device ownership. After this the app
     * can be uninstalled normally. Irreversible without re-running the ADB
     * provisioning command.
     */
    fun teardown(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        val admin = adminComponent(context)
        if (!isDeviceOwner(context)) return

        runCatching { dpm.setAlwaysOnVpnPackage(admin, null, false) }
        for (restriction in LOCKDOWN_RESTRICTIONS) {
            runCatching { dpm.clearUserRestriction(admin, restriction) }
        }
        runCatching { dpm.setUninstallBlocked(admin, context.packageName, false) }
        @Suppress("DEPRECATION")
        runCatching { dpm.clearDeviceOwnerApp(context.packageName) }
            .onFailure { Log.e(TAG, "clearDeviceOwnerApp failed", it) }
        Log.i(TAG, "Teardown complete — device ownership relinquished")
    }
}
