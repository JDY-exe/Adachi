package com.adachi.lockdown.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class RuleType { BLOCK, ALLOW, WINDOW, QUOTA }

const val ALL_DAYS_MASK = 0b1111111 // bit0=Mon .. bit6=Sun

@Entity(tableName = "domain_rules")
data class DomainRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** e.g. "reddit.com", "*.reddit.com", or "*" for everything */
    val pattern: String,
    val type: RuleType,
    val daysMask: Int = ALL_DAYS_MASK,
    /** Minutes from midnight, window start (WINDOW rules). */
    val startMin: Int = 0,
    /** Minutes from midnight, window end (WINDOW rules). May be < startMin (wraps midnight). */
    val endMin: Int = 0,
    /** Daily allowance in minutes (QUOTA rules). */
    val quotaMin: Int = 0,
    val enabled: Boolean = true,
    val createdAt: Long = 0,
)

@Entity(tableName = "app_rules")
data class AppRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Android package name, e.g. "com.reddit.frontpage", or "*" for every app. */
    val packageName: String,
    val label: String = "",
    val type: RuleType,
    val daysMask: Int = ALL_DAYS_MASK,
    val startMin: Int = 0,
    val endMin: Int = 0,
    val quotaMin: Int = 0,
    val enabled: Boolean = true,
    val createdAt: Long = 0,
)

/**
 * Singleton row (id = 1): emergency-unlock bookkeeping and clock-tamper watermark.
 */
@Entity(tableName = "unlock_state")
data class UnlockState(
    @PrimaryKey val id: Int = 1,
    /** CSV of consumed ISO weeks, e.g. "2026-W29,2026-W30". Append-only. */
    val consumedWeeks: String = "",
    /** Epoch ms until which the 30-min unlock window is active. 0 = inactive. */
    val activeUntilMs: Long = 0,
    /** Local date (yyyy-MM-dd) of the last malfunction pause; one per day. */
    val malfunctionPauseDate: String = "",
    /** Epoch ms when device-owner provisioning happened (48h grace). 0 = not provisioned. */
    val provisionedAtMs: Long = 0,
    /** Clock-tamper watermark: highest UTC epoch ms ever observed. */
    val utcWatermarkMs: Long = 0,
    /** elapsedRealtime at the moment the watermark was recorded. */
    val watermarkElapsedMs: Long = 0,
)

/**
 * Per-day usage accounting. key = "dom:<ruleId>" or "app:<packageName>".
 */
@Entity(tableName = "usage_ledger", primaryKeys = ["key", "date"])
data class UsageLedger(
    val key: String,
    /** Local date yyyy-MM-dd. */
    val date: String,
    val minutesUsed: Int = 0,
)

@Entity(tableName = "block_log")
data class BlockLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochMs: Long,
    /** "DOMAIN" or "APP" */
    val kind: String,
    val target: String,
    val reason: String,
)
