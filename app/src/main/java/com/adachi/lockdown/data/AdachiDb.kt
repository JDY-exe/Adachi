package com.adachi.lockdown.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class RuleModeConverter { @TypeConverter fun from(value: RuleMode) = value.name; @TypeConverter fun to(value: String) = RuleMode.valueOf(value) }

@Database(entities = [Rule::class, RuleAppTarget::class, RuleDomainTarget::class, RuleCheckIn::class, UnlockState::class, BlockLog::class, EventLog::class], version = 3, exportSchema = false)
@TypeConverters(RuleModeConverter::class)
abstract class AdachiDb : RoomDatabase() {
    abstract fun rules(): RuleDao
    abstract fun targets(): RuleTargetDao
    abstract fun checkIns(): CheckInDao
    abstract fun unlockState(): UnlockStateDao
    abstract fun blockLog(): BlockLogDao
    abstract fun eventLog(): EventLogDao
    companion object {
        @Volatile private var instance: AdachiDb? = null
        private val MIGRATION_1_2 = object : Migration(1, 2) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("CREATE TABLE IF NOT EXISTS event_log (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, epochMs INTEGER NOT NULL, kind TEXT NOT NULL, level TEXT NOT NULL, message TEXT NOT NULL)") } }
        /** Legacy rules and approximate usage are deliberately discarded. */
        private val MIGRATION_2_3 = object : Migration(2, 3) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS domain_rules"); db.execSQL("DROP TABLE IF EXISTS app_rules"); db.execSQL("DROP TABLE IF EXISTS usage_ledger")
            db.execSQL("CREATE TABLE rules (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, mode TEXT NOT NULL, enabled INTEGER NOT NULL, timedAllowanceMin INTEGER NOT NULL, daysMask INTEGER NOT NULL, startMin INTEGER NOT NULL, endMin INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE rule_app_targets (ruleId INTEGER NOT NULL, packageName TEXT NOT NULL, label TEXT NOT NULL, PRIMARY KEY(ruleId, packageName), FOREIGN KEY(ruleId) REFERENCES rules(id) ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX index_rule_app_targets_ruleId ON rule_app_targets(ruleId)")
            db.execSQL("CREATE TABLE rule_domain_targets (ruleId INTEGER NOT NULL, pattern TEXT NOT NULL, PRIMARY KEY(ruleId, pattern), FOREIGN KEY(ruleId) REFERENCES rules(id) ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX index_rule_domain_targets_ruleId ON rule_domain_targets(ruleId)")
            db.execSQL("CREATE TABLE rule_check_ins (ruleId INTEGER NOT NULL PRIMARY KEY, localDate TEXT NOT NULL, reservedMinutes INTEGER NOT NULL, expiresAtMs INTEGER NOT NULL, FOREIGN KEY(ruleId) REFERENCES rules(id) ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX index_rule_check_ins_ruleId ON rule_check_ins(ruleId)")
        } }
        fun get(context: Context): AdachiDb = instance ?: synchronized(this) { instance ?: Room.databaseBuilder(context.applicationContext, AdachiDb::class.java, "adachi.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it } }
    }
}
