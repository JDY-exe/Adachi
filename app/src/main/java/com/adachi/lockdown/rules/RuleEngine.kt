package com.adachi.lockdown.rules

import com.adachi.lockdown.data.AppRule
import com.adachi.lockdown.data.DomainRule
import com.adachi.lockdown.data.RuleType
import java.time.LocalDateTime

/**
 * Pure, side-effect-free rule evaluation. No Android dependencies -> fully unit-testable.
 *
 * Semantics:
 *  - No matching rule           -> ALLOW (blocklist mode)
 *  - Any ALLOW rule matches     -> ALLOW (allowlist always wins)
 *  - Any BLOCK rule matches     -> BLOCK (fail closed on contradictory config)
 *  - WINDOW rules: ALL matching window rules must contain `now`, else BLOCK
 *  - QUOTA rules: ALL matching quota rules must have usage left today, else BLOCK
 *
 * Day masks: bit0=Mon .. bit6=Sun. A wrapping window (22:00-02:00) marked Monday is
 * active Monday 22:00-24:00 AND Tuesday 00:00-02:00.
 */
object RuleEngine {

    sealed interface Verdict {
        data object Allow : Verdict
        data class Block(val reason: Reason, val ruleId: Long) : Verdict
    }

    enum class Reason { BLOCKED, OUTSIDE_WINDOW, QUOTA_EXHAUSTED }

    /** Matches "reddit.com" against patterns like "reddit.com", "*.reddit.com", "*". */
    fun matchesDomain(pattern: String, domain: String): Boolean {
        val d = domain.trim().trimEnd('.').lowercase()
        if (d.isEmpty()) return false
        val p = pattern.trim().lowercase().removePrefix("*.")
        if (p.isEmpty()) return false
        if (p == "*") return true
        return d == p || d.endsWith(".$p")
    }

    fun matchesApp(pattern: String, packageName: String): Boolean {
        val p = pattern.trim()
        if (p == "*") return true
        return p == packageName.trim()
    }

    /**
     * True if `now` is inside the window described by [daysMask], [startMin], [endMin]
     * (minutes from midnight). startMin == endMin means "all day" on the masked days.
     */
    fun inWindow(daysMask: Int, startMin: Int, endMin: Int, now: LocalDateTime): Boolean {
        val dayBit = 1 shl (now.dayOfWeek.value - 1)          // Mon=1 -> bit0
        val prevDayBit = 1 shl ((now.dayOfWeek.value + 5) % 7)
        val m = now.hour * 60 + now.minute
        return if (startMin == endMin) {
            daysMask and dayBit != 0
        } else if (startMin < endMin) {
            daysMask and dayBit != 0 && m in startMin until endMin
        } else {
            (daysMask and dayBit != 0 && m >= startMin) ||
                (daysMask and prevDayBit != 0 && m < endMin)
        }
    }

    fun evaluateDomain(
        domain: String,
        rules: List<DomainRule>,
        now: LocalDateTime,
        usedMinTodayByRule: Map<Long, Int> = emptyMap(),
    ): Verdict {
        val matches = rules.filter { it.enabled && matchesDomain(it.pattern, domain) }
        return evaluate(matches.map { it.toGeneric() }, usedMinTodayByRule, now)
    }

    fun evaluateApp(
        packageName: String,
        rules: List<AppRule>,
        now: LocalDateTime,
        usedMinTodayByRule: Map<Long, Int> = emptyMap(),
    ): Verdict {
        val matches = rules.filter { it.enabled && matchesApp(it.packageName, packageName) }
        return evaluate(matches.map { it.toGeneric() }, usedMinTodayByRule, now)
    }

    /** Rule shape shared by domain and app rules for evaluation. */
    data class GenericRule(
        val id: Long,
        val type: RuleType,
        val daysMask: Int,
        val startMin: Int,
        val endMin: Int,
        val quotaMin: Int,
    )

    private fun DomainRule.toGeneric() = GenericRule(id, type, daysMask, startMin, endMin, quotaMin)
    private fun AppRule.toGeneric() = GenericRule(id, type, daysMask, startMin, endMin, quotaMin)

    private fun evaluate(
        matches: List<GenericRule>,
        usedMinTodayByRule: Map<Long, Int>,
        now: LocalDateTime,
    ): Verdict {
        if (matches.isEmpty()) return Verdict.Allow
        if (matches.any { it.type == RuleType.ALLOW }) return Verdict.Allow
        matches.firstOrNull { it.type == RuleType.BLOCK }
            ?.let { return Verdict.Block(Reason.BLOCKED, it.id) }
        for (rule in matches) {
            when (rule.type) {
                RuleType.WINDOW ->
                    if (!inWindow(rule.daysMask, rule.startMin, rule.endMin, now)) {
                        return Verdict.Block(Reason.OUTSIDE_WINDOW, rule.id)
                    }
                RuleType.QUOTA ->
                    if ((usedMinTodayByRule[rule.id] ?: 0) >= rule.quotaMin) {
                        return Verdict.Block(Reason.QUOTA_EXHAUSTED, rule.id)
                    }
                else -> Unit
            }
        }
        return Verdict.Allow
    }
}
