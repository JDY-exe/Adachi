package com.adachi.lockdown.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class RuleMode { BLOCK, ALLOW, TIMED, TIME_FRAMED }
const val ALL_DAYS_MASK = 0b1111111 // bit0=Mon .. bit6=Sun

@Entity(tableName = "rules")
data class Rule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val mode: RuleMode,
    val enabled: Boolean = true,
    /** Daily check-in budget for TIMED rules. */ val timedAllowanceMin: Int = 0,
    val daysMask: Int = ALL_DAYS_MASK,
    val startMin: Int = 0,
    val endMin: Int = 0,
    val createdAt: Long = 0,
)

@Entity(tableName = "rule_app_targets", primaryKeys = ["ruleId", "packageName"], foreignKeys = [ForeignKey(entity = Rule::class, parentColumns = ["id"], childColumns = ["ruleId"], onDelete = ForeignKey.CASCADE)], indices = [Index("ruleId")])
data class RuleAppTarget(val ruleId: Long, val packageName: String, val label: String = "")

@Entity(tableName = "rule_domain_targets", primaryKeys = ["ruleId", "pattern"], foreignKeys = [ForeignKey(entity = Rule::class, parentColumns = ["id"], childColumns = ["ruleId"], onDelete = ForeignKey.CASCADE)], indices = [Index("ruleId")])
data class RuleDomainTarget(val ruleId: Long, val pattern: String)

/** One durable, extendable check-in per rule. Reserved minutes reset by local date. */
@Entity(tableName = "rule_check_ins", foreignKeys = [ForeignKey(entity = Rule::class, parentColumns = ["id"], childColumns = ["ruleId"], onDelete = ForeignKey.CASCADE)], indices = [Index("ruleId")])
data class RuleCheckIn(
    @PrimaryKey val ruleId: Long,
    val localDate: String,
    val reservedMinutes: Int,
    val expiresAtMs: Long,
)

data class RuleWithTargets(
    val rule: Rule,
    val apps: List<RuleAppTarget>,
    val domains: List<RuleDomainTarget>,
)

@Entity(tableName = "unlock_state")
data class UnlockState(@PrimaryKey val id: Int = 1, val consumedWeeks: String = "", val activeUntilMs: Long = 0, val malfunctionPauseDate: String = "", val provisionedAtMs: Long = 0, val utcWatermarkMs: Long = 0, val watermarkElapsedMs: Long = 0)

@Entity(tableName = "block_log")
data class BlockLog(@PrimaryKey(autoGenerate = true) val id: Long = 0, val epochMs: Long, val kind: String, val target: String, val reason: String)

@Entity(tableName = "event_log")
data class EventLog(@PrimaryKey(autoGenerate = true) val id: Long = 0, val epochMs: Long, val kind: String, val level: String, val message: String)
