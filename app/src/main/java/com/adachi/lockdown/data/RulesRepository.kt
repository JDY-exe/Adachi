package com.adachi.lockdown.data

import android.content.Context
import com.adachi.lockdown.rules.EditPolicy
import com.adachi.lockdown.rules.RuleEngine
import com.adachi.lockdown.unlock.UnlockManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.*

class RelaxationLockedException : Exception("This change loosens a restriction. It requires an active emergency unlock window.")
class CheckInRejectedException(message: String) : Exception(message)

class RulesRepository private constructor(private val db: AdachiDb) {
    fun rules(): Flow<List<RuleWithTargets>> = combine(db.rules().observeAll(), db.checkIns().observeAll()) { rules, _ -> loadWithTargets(rules) }
    fun checkIns(): Flow<List<RuleCheckIn>> = db.checkIns().observeAll()
    fun unlockState(): Flow<UnlockState?> = db.unlockState().observe()
    fun recentBlocks(limit: Int=100): Flow<List<BlockLog>> = db.blockLog().observeRecent(limit)
    suspend fun rulesNow(): List<RuleWithTargets> = loadWithTargets(db.rules().enabled())
    private suspend fun loadWithTargets(rules: List<Rule>) = rules.map { RuleWithTargets(it, db.targets().apps(it.id), db.targets().domains(it.id)) }
    suspend fun unlockStateNow() = db.unlockState().get() ?: UnlockState()
    suspend fun saveUnlockState(state: UnlockState) = db.unlockState().upsert(state)
    suspend fun logBlock(entry: BlockLog) = db.blockLog().insert(entry)
    suspend fun blocksSince(epochMs: Long) = db.blockLog().countSince(epochMs)
    suspend fun pruneBlocks(beforeMs: Long) = db.blockLog().pruneBefore(beforeMs)
    fun recentEvents(limit: Int=300): Flow<List<EventLog>> = db.eventLog().observeRecent(limit)
    suspend fun logEvents(events: List<EventLog>) = db.eventLog().insertAll(events)
    suspend fun pruneEvents(beforeMs: Long) = db.eventLog().pruneBefore(beforeMs)
    suspend fun clearEvents() = db.eventLog().clear()

    suspend fun addRule(item: RuleWithTargets, unlockActive: Boolean): Long { validate(item); if (!unlockedNow() && EditPolicy.isRelaxing(null,item)) throw RelaxationLockedException(); val id=db.rules().insert(item.rule.copy(createdAt=System.currentTimeMillis())); saveTargets(id,item); return id }
    suspend fun updateRule(old: RuleWithTargets, item: RuleWithTargets, unlockActive: Boolean) { validate(item); if (!unlockedNow() && EditPolicy.isRelaxing(old,item)) throw RelaxationLockedException(); db.rules().update(item.rule); db.targets().clearApps(item.rule.id); db.targets().clearDomains(item.rule.id); saveTargets(item.rule.id,item) }
    suspend fun deleteRule(item: RuleWithTargets, unlockActive: Boolean) { if (!unlockedNow() && EditPolicy.isRelaxing(item,null)) throw RelaxationLockedException(); db.rules().delete(item.rule) }
    /** The database is authoritative; UI state may be inactive while its Flow is unsubscribed. */
    private suspend fun unlockedNow() = UnlockManager.isActive(db.unlockState().get(), System.currentTimeMillis())
    private suspend fun saveTargets(id: Long, item: RuleWithTargets) { db.targets().insertApps(item.apps.map { it.copy(ruleId=id) }); db.targets().insertDomains(item.domains.map { it.copy(ruleId=id) }) }
    private fun validate(item: RuleWithTargets) { require(item.apps.isNotEmpty() || item.domains.isNotEmpty()) { "A rule needs at least one app or domain." }; if (item.rule.mode==RuleMode.TIMED) require(item.rule.timedAllowanceMin in 1..1440); if (item.rule.mode==RuleMode.TIME_FRAMED) require(item.rule.daysMask != 0 && item.rule.startMin in 0..1439 && item.rule.endMin in 0..1439) }

    /** Intentional daily-use action; it is never subject to the edit unlock gate. */
    suspend fun checkIn(ruleId: Long, minutes: Int, now: ZonedDateTime = ZonedDateTime.now()): RuleCheckIn {
        require(minutes in CHECK_IN_MINUTES) { "Choose one of the offered durations." }
        val rule = db.rules().get(ruleId) ?: throw CheckInRejectedException("This rule no longer exists.")
        if (!rule.enabled || rule.mode !in setOf(RuleMode.TIMED,RuleMode.TIME_FRAMED)) throw CheckInRejectedException("This rule is not available for check-in.")
        if (rule.mode==RuleMode.TIME_FRAMED && !RuleEngine.inWindow(rule.daysMask,rule.startMin,rule.endMin,now.toLocalDateTime())) throw CheckInRejectedException("Outside this rule's time frame.")
        val today=now.toLocalDate().toString(); val old=db.checkIns().get(ruleId); val reserved=if(old?.localDate==today) old.reservedMinutes else 0
        if (rule.mode==RuleMode.TIMED && reserved+minutes>rule.timedAllowanceMin) throw CheckInRejectedException("That full duration does not fit in today's remaining allowance.")
        val base=maxOf(now.toInstant().toEpochMilli(), old?.expiresAtMs ?: 0L)
        var expiry=base + minutes*60_000L
        if(rule.mode==RuleMode.TIME_FRAMED) expiry=minOf(expiry, scheduleBoundary(rule,now).toInstant().toEpochMilli())
        val grant=RuleCheckIn(ruleId,today,if(rule.mode==RuleMode.TIMED) reserved+minutes else reserved,expiry)
        db.checkIns().upsert(grant); return grant
    }
    private fun scheduleBoundary(rule: Rule, now: ZonedDateTime): ZonedDateTime { var p=now; repeat(2881) { if (!RuleEngine.inWindow(rule.daysMask,rule.startMin,rule.endMin,p.toLocalDateTime())) return p; p=p.plusMinutes(1) }; return now }
    companion object { val CHECK_IN_MINUTES=setOf(1,5,10,20,30,45,60); @Volatile private var instance: RulesRepository?=null; fun get(context: Context)=instance ?: synchronized(this) { instance ?: RulesRepository(AdachiDb.get(context)).also { instance=it } } }
}
