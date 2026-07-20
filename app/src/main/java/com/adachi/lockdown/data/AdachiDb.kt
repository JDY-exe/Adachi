package com.adachi.lockdown.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class RuleTypeConverter {
    @TypeConverter
    fun fromType(type: RuleType): String = type.name

    @TypeConverter
    fun toType(value: String): RuleType = RuleType.valueOf(value)
}

@Database(
    entities = [
        DomainRule::class,
        AppRule::class,
        UnlockState::class,
        UsageLedger::class,
        BlockLog::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(RuleTypeConverter::class)
abstract class AdachiDb : RoomDatabase() {
    abstract fun domainRules(): DomainRuleDao
    abstract fun appRules(): AppRuleDao
    abstract fun unlockState(): UnlockStateDao
    abstract fun usageLedger(): UsageLedgerDao
    abstract fun blockLog(): BlockLogDao

    companion object {
        @Volatile
        private var instance: AdachiDb? = null

        fun get(context: Context): AdachiDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AdachiDb::class.java,
                    "adachi.db",
                ).build().also { instance = it }
            }
    }
}
