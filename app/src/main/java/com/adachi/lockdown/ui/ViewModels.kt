package com.adachi.lockdown.ui

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.adachi.lockdown.data.AppRule
import com.adachi.lockdown.data.DomainRule
import com.adachi.lockdown.data.RelaxationLockedException
import com.adachi.lockdown.data.RulesRepository
import com.adachi.lockdown.data.UnlockState
import com.adachi.lockdown.status.SystemStatus
import com.adachi.lockdown.unlock.UnlockManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

private fun ticker(periodMs: Long): Flow<Long> = kotlinx.coroutines.flow.flow {
    while (true) {
        emit(System.currentTimeMillis())
        delay(periodMs)
    }
}

data class InstalledApp(val label: String, val packageName: String)

class RulesViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = RulesRepository.get(app)

    val domainRules: StateFlow<List<DomainRule>> =
        repo.domainRules().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val appRules: StateFlow<List<AppRule>> =
        repo.appRules().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unlockActive: StateFlow<Boolean> =
        combine(repo.unlockState(), ticker(15_000)) { s, now -> UnlockManager.isActive(s, now) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val message = MutableStateFlow<String?>(null)
    val installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) { installedApps.value = loadInstalledApps() }
    }

    fun clearMessage() {
        message.value = null
    }

    // ---- Domain rules ----

    fun saveDomainRule(old: DomainRule?, new: DomainRule) = runGated {
        if (old == null) repo.addDomainRule(new, unlockActive.value)
        else repo.updateDomainRule(old, new, unlockActive.value)
    }

    fun deleteDomainRule(rule: DomainRule) = runGated {
        repo.deleteDomainRule(rule, unlockActive.value)
    }

    fun toggleDomainRule(rule: DomainRule) = runGated {
        repo.updateDomainRule(rule, rule.copy(enabled = !rule.enabled), unlockActive.value)
    }

    // ---- App rules ----

    fun saveAppRule(old: AppRule?, new: AppRule) = runGated {
        if (old == null) repo.addAppRule(new, unlockActive.value)
        else repo.updateAppRule(old, new, unlockActive.value)
    }

    fun deleteAppRule(rule: AppRule) = runGated {
        repo.deleteAppRule(rule, unlockActive.value)
    }

    fun toggleAppRule(rule: AppRule) = runGated {
        repo.updateAppRule(rule, rule.copy(enabled = !rule.enabled), unlockActive.value)
    }

    private fun runGated(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: RelaxationLockedException) {
                message.value = "Locked: that change loosens a restriction. " +
                    "It needs an active emergency unlock window."
            }
        }
    }

    private fun loadInstalledApps(): List<InstalledApp> {
        val pm = getApplication<Application>().packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}

data class DashboardState(
    val enforcementPaused: Boolean = false,
    val pauseRemainingMs: Long = 0,
    val weeklyUnlockAvailable: Boolean = true,
    val malfunctionPauseAvailable: Boolean = true,
    val vpnRunning: Boolean = false,
    val accessibilityOn: Boolean = false,
    val deviceOwner: Boolean = false,
    val blocksToday: Int = 0,
    val inGracePeriod: Boolean = false,
)

class DashboardViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = RulesRepository.get(app)

    val recentBlocks: StateFlow<List<com.adachi.lockdown.data.BlockLog>> =
        repo.recentBlocks(15).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val state: StateFlow<DashboardState> =
        combine(repo.unlockState(), ticker(5_000)) { s, now -> s to now }
            .let { flow ->
                kotlinx.coroutines.flow.flow {
                    flow.collect { (s, now) -> emit(buildState(s, now)) }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardState())

    private suspend fun buildState(s: UnlockState?, now: Long): DashboardState {
        val app = getApplication<Application>()
        val today = LocalDate.now()
        val startOfToday = LocalDateTime.now().toLocalDate().atStartOfDay()
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        return DashboardState(
            enforcementPaused = UnlockManager.isActive(s, now),
            pauseRemainingMs = UnlockManager.remainingMs(s, now),
            weeklyUnlockAvailable = UnlockManager.canSpendWeeklyUnlock(s, today),
            malfunctionPauseAvailable = UnlockManager.canMalfunctionPause(s, today),
            vpnRunning = SystemStatus.isVpnRunning(app),
            accessibilityOn = SystemStatus.isAccessibilityEnabled(app),
            deviceOwner = SystemStatus.isDeviceOwner(app),
            blocksToday = repo.blocksSince(startOfToday),
            inGracePeriod = UnlockManager.inGracePeriod(s, now),
        )
    }
}

class UnlockViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = RulesRepository.get(app)

    val unlockState: StateFlow<UnlockState?> =
        repo.unlockState().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val nowMs: StateFlow<Long> =
        ticker(1_000).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), System.currentTimeMillis())

    val deviceOwner = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val info = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            while (true) {
                deviceOwner.value = com.adachi.lockdown.admin.DeviceOwnerManager.isDeviceOwner(app)
                kotlinx.coroutines.delay(5_000)
            }
        }
    }

    fun spendWeeklyUnlock() {
        viewModelScope.launch {
            try {
                val s = repo.unlockStateNow()
                repo.saveUnlockState(
                    UnlockManager.spendWeeklyUnlock(s, System.currentTimeMillis(), LocalDate.now()),
                )
            } catch (e: IllegalArgumentException) {
                error.value = e.message
            }
        }
    }

    fun travelMode() {
        com.adachi.lockdown.admin.DeviceOwnerManager.travelMode(getApplication())
        info.value = "Timezone settings unlocked for 5 minutes. " +
            "Changing the DATE will consume this week's unlock."
    }

    fun enableAdb() {
        com.adachi.lockdown.admin.DeviceOwnerManager.enableAdbTemporarily(getApplication())
        info.value = "ADB re-enabled for 30 minutes."
    }

    fun deactivate() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            com.adachi.lockdown.vpn.AdachiVpnService.stop(app)
            com.adachi.lockdown.admin.DeviceOwnerManager.teardown(app)
            info.value = "Adachi has relinquished device ownership and lifted all restrictions. " +
                "You can now uninstall it from Settings."
        }
    }

    fun spendMalfunctionPause() {
        viewModelScope.launch {
            try {
                val s = repo.unlockStateNow()
                repo.saveUnlockState(
                    UnlockManager.spendMalfunctionPause(s, System.currentTimeMillis(), LocalDate.now()),
                )
            } catch (e: IllegalArgumentException) {
                error.value = e.message
            }
        }
    }

    fun clearError() {
        error.value = null
    }

    fun clearInfo() {
        info.value = null
    }
}
