package com.adachi.lockdown.unlock

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.UserManager
import com.adachi.lockdown.admin.DeviceOwnerManager
import com.adachi.lockdown.data.RulesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * While an unlock/pause window is open, the date-time restriction is lifted
 * (so the user can adjust the timezone). It is re-applied when the window
 * closes. Debugging is also force re-applied at window close.
 */
object UnlockWindowReactor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        scope.launch {
            RulesRepository.get(appContext).unlockState().collectLatest { state ->
                if (UnlockManager.isActive(state, System.currentTimeMillis())) {
                    setRestriction(appContext, UserManager.DISALLOW_CONFIG_DATE_TIME, lifted = true)
                    // Watch for the window closing.
                    while (UnlockManager.isActive(state, System.currentTimeMillis())) {
                        delay(10_000)
                    }
                    setRestriction(appContext, UserManager.DISALLOW_CONFIG_DATE_TIME, lifted = false)
                    setRestriction(appContext, UserManager.DISALLOW_DEBUGGING_FEATURES, lifted = false)
                }
            }
        }
    }

    private fun setRestriction(context: Context, restriction: String, lifted: Boolean) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!DeviceOwnerManager.isDeviceOwner(context)) return
        val admin = DeviceOwnerManager.adminComponent(context)
        runCatching {
            if (lifted) dpm.clearUserRestriction(admin, restriction)
            else dpm.addUserRestriction(admin, restriction)
        }
    }
}
