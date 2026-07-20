package com.adachi.lockdown.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DomainRuleDao {
    @Query("SELECT * FROM domain_rules ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<DomainRule>>

    @Query("SELECT * FROM domain_rules")
    suspend fun getAll(): List<DomainRule>

    @Query("SELECT * FROM domain_rules WHERE enabled = 1")
    suspend fun getEnabled(): List<DomainRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: DomainRule): Long

    @Update
    suspend fun update(rule: DomainRule)

    @Delete
    suspend fun delete(rule: DomainRule)

    @Query("DELETE FROM domain_rules")
    suspend fun deleteAll()
}

@Dao
interface AppRuleDao {
    @Query("SELECT * FROM app_rules ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<AppRule>>

    @Query("SELECT * FROM app_rules")
    suspend fun getAll(): List<AppRule>

    @Query("SELECT * FROM app_rules WHERE enabled = 1")
    suspend fun getEnabled(): List<AppRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: AppRule): Long

    @Update
    suspend fun update(rule: AppRule)

    @Delete
    suspend fun delete(rule: AppRule)

    @Query("DELETE FROM app_rules")
    suspend fun deleteAll()
}

@Dao
interface UnlockStateDao {
    @Query("SELECT * FROM unlock_state WHERE id = 1")
    suspend fun get(): UnlockState?

    @Query("SELECT * FROM unlock_state WHERE id = 1")
    fun observe(): Flow<UnlockState?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: UnlockState)
}

@Dao
interface UsageLedgerDao {
    @Query("SELECT * FROM usage_ledger WHERE date = :date")
    suspend fun getForDate(date: String): List<UsageLedger>

    @Query("SELECT minutesUsed FROM usage_ledger WHERE key = :key AND date = :date")
    suspend fun getMinutes(key: String, date: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: UsageLedger)

    @Query("DELETE FROM usage_ledger WHERE date < :beforeDate")
    suspend fun pruneBefore(beforeDate: String)
}

@Dao
interface BlockLogDao {
    @Insert
    suspend fun insert(entry: BlockLog)

    @Query("SELECT * FROM block_log ORDER BY epochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<BlockLog>>

    @Query("SELECT COUNT(*) FROM block_log WHERE epochMs >= :sinceMs")
    suspend fun countSince(sinceMs: Long): Int

    @Query("DELETE FROM block_log WHERE epochMs < :beforeMs")
    suspend fun pruneBefore(beforeMs: Long)
}
