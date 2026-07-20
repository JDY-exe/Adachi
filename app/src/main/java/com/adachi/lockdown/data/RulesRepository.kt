package com.adachi.lockdown.data

import android.content.Context
import com.adachi.lockdown.rules.EditPolicy
import kotlinx.coroutines.flow.Flow

/** Thrown when a change would loosen enforcement outside an unlock window. */
class RelaxationLockedException :
    Exception("This change loosens a restriction. It requires an active emergency unlock window.")

/**
 * Single entry point for rule/unlock/usage data. Enforces the
 * "stricter anytime, relaxing only while unlocked" policy at the data layer,
 * so UI mistakes can't bypass it.
 */
class RulesRepository private constructor(private val db: AdachiDb) {

    // ---- Observation ----

    fun domainRules(): Flow<List<DomainRule>> = db.domainRules().observeAll()
    fun appRules(): Flow<List<AppRule>> = db.appRules().observeAll()
    fun unlockState(): Flow<UnlockState?> = db.unlockState().observe()
    fun recentBlocks(limit: Int = 100): Flow<List<BlockLog>> = db.blockLog().observeRecent(limit)

    suspend fun unlockStateNow(): UnlockState = db.unlockState().get() ?: UnlockState()
    suspend fun saveUnlockState(state: UnlockState) = db.unlockState().upsert(state)

    suspend fun enabledDomainRules(): List<DomainRule> = db.domainRules().getEnabled()
    suspend fun enabledAppRules(): List<AppRule> = db.appRules().getEnabled()

    suspend fun usageFor(date: String): List<UsageLedger> = db.usageLedger().getForDate(date)
    suspend fun saveUsage(entry: UsageLedger) = db.usageLedger().upsert(entry)
    suspend fun pruneUsage(beforeDate: String) = db.usageLedger().pruneBefore(beforeDate)

    suspend fun logBlock(entry: BlockLog) = db.blockLog().insert(entry)
    suspend fun blocksSince(epochMs: Long): Int = db.blockLog().countSince(epochMs)
    suspend fun pruneBlocks(beforeMs: Long) = db.blockLog().pruneBefore(beforeMs)

    // ---- Domain rule mutations (policy-gated) ----

    suspend fun addDomainRule(rule: DomainRule, unlockActive: Boolean): Long {
        requireEditAllowed(null, rule, unlockActive)
        return db.domainRules().insert(rule.copy(createdAt = System.currentTimeMillis()))
    }

    suspend fun updateDomainRule(old: DomainRule, new: DomainRule, unlockActive: Boolean) {
        requireEditAllowed(old, new, unlockActive)
        db.domainRules().update(new)
    }

    suspend fun deleteDomainRule(rule: DomainRule, unlockActive: Boolean) {
        requireEditAllowed(rule, null, unlockActive)
        db.domainRules().delete(rule)
    }

    // ---- App rule mutations (policy-gated) ----

    suspend fun addAppRule(rule: AppRule, unlockActive: Boolean): Long {
        requireEditAllowed(null, rule, unlockActive)
        return db.appRules().insert(rule.copy(createdAt = System.currentTimeMillis()))
    }

    suspend fun updateAppRule(old: AppRule, new: AppRule, unlockActive: Boolean) {
        requireEditAllowed(old, new, unlockActive)
        db.appRules().update(new)
    }

    suspend fun deleteAppRule(rule: AppRule, unlockActive: Boolean) {
        requireEditAllowed(rule, null, unlockActive)
        db.appRules().delete(rule)
    }

    // ---- Gating ----

    private fun requireEditAllowed(old: DomainRule?, new: DomainRule?, unlockActive: Boolean) {
        if (!unlockActive && EditPolicy.isRelaxing(old, new)) throw RelaxationLockedException()
    }

    private fun requireEditAllowed(old: AppRule?, new: AppRule?, unlockActive: Boolean) {
        if (!unlockActive && EditPolicy.isRelaxing(old, new)) throw RelaxationLockedException()
    }

    companion object {
        @Volatile
        private var instance: RulesRepository? = null

        fun get(context: Context): RulesRepository =
            instance ?: synchronized(this) {
                instance ?: RulesRepository(AdachiDb.get(context)).also { instance = it }
            }
    }
}
