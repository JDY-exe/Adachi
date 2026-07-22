package com.adachi.lockdown.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY createdAt ASC") fun observeAll(): Flow<List<Rule>>
    @Query("SELECT * FROM rules WHERE enabled = 1") suspend fun enabled(): List<Rule>
    @Query("SELECT * FROM rules WHERE id = :id") suspend fun get(id: Long): Rule?
    @Insert suspend fun insert(rule: Rule): Long
    @Update suspend fun update(rule: Rule)
    @Delete suspend fun delete(rule: Rule)
}
@Dao interface RuleTargetDao {
    @Query("SELECT * FROM rule_app_targets WHERE ruleId = :id") suspend fun apps(id: Long): List<RuleAppTarget>
    @Query("SELECT * FROM rule_domain_targets WHERE ruleId = :id") suspend fun domains(id: Long): List<RuleDomainTarget>
    @Query("SELECT * FROM rule_app_targets") suspend fun allApps(): List<RuleAppTarget>
    @Query("SELECT * FROM rule_domain_targets") suspend fun allDomains(): List<RuleDomainTarget>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertApps(items: List<RuleAppTarget>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertDomains(items: List<RuleDomainTarget>)
    @Query("DELETE FROM rule_app_targets WHERE ruleId = :id") suspend fun clearApps(id: Long)
    @Query("DELETE FROM rule_domain_targets WHERE ruleId = :id") suspend fun clearDomains(id: Long)
}
@Dao interface CheckInDao {
    @Query("SELECT * FROM rule_check_ins") fun observeAll(): Flow<List<RuleCheckIn>>
    @Query("SELECT * FROM rule_check_ins") suspend fun all(): List<RuleCheckIn>
    @Query("SELECT * FROM rule_check_ins WHERE ruleId = :id") suspend fun get(id: Long): RuleCheckIn?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: RuleCheckIn)
}
@Dao interface UnlockStateDao { @Query("SELECT * FROM unlock_state WHERE id = 1") suspend fun get(): UnlockState?; @Query("SELECT * FROM unlock_state WHERE id = 1") fun observe(): Flow<UnlockState?>; @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(state: UnlockState) }
@Dao interface BlockLogDao { @Insert suspend fun insert(entry: BlockLog); @Query("SELECT * FROM block_log ORDER BY epochMs DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<BlockLog>>; @Query("SELECT COUNT(*) FROM block_log WHERE epochMs >= :sinceMs") suspend fun countSince(sinceMs: Long): Int; @Query("DELETE FROM block_log WHERE epochMs < :beforeMs") suspend fun pruneBefore(beforeMs: Long) }
@Dao interface EventLogDao { @Insert suspend fun insertAll(events: List<EventLog>); @Query("SELECT * FROM event_log ORDER BY id DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<EventLog>>; @Query("DELETE FROM event_log WHERE epochMs < :beforeMs") suspend fun pruneBefore(beforeMs: Long); @Query("DELETE FROM event_log") suspend fun clear() }
