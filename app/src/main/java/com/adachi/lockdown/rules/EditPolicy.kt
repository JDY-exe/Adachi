package com.adachi.lockdown.rules

import com.adachi.lockdown.data.AppRule
import com.adachi.lockdown.data.DomainRule
import com.adachi.lockdown.data.RuleType

/**
 * Classifies rule edits as "relaxing" (requires an active unlock window) or
 * "restricting/neutral" (allowed anytime).
 *
 * When in doubt, an edit is classified as RELAXING (fail closed).
 */
object EditPolicy {

    fun isRelaxing(old: DomainRule?, new: DomainRule?): Boolean = isRelaxing(
        old?.let { Shape(it.type, it.enabled, it.daysMask, it.startMin, it.endMin, it.quotaMin, it.pattern) },
        new?.let { Shape(it.type, it.enabled, it.daysMask, it.startMin, it.endMin, it.quotaMin, it.pattern) },
    )

    fun isRelaxing(old: AppRule?, new: AppRule?): Boolean = isRelaxing(
        old?.let { Shape(it.type, it.enabled, it.daysMask, it.startMin, it.endMin, it.quotaMin, it.packageName) },
        new?.let { Shape(it.type, it.enabled, it.daysMask, it.startMin, it.endMin, it.quotaMin, it.packageName) },
    )

    data class Shape(
        val type: RuleType,
        val enabled: Boolean,
        val daysMask: Int,
        val startMin: Int,
        val endMin: Int,
        val quotaMin: Int,
        val pattern: String,
    )

    fun isRelaxing(old: Shape?, new: Shape?): Boolean {
        if (old == null && new == null) return false
        if (old == null) return new!!.type == RuleType.ALLOW
        if (new == null) return old.type != RuleType.ALLOW

        // Toggling enabled.
        if (old.enabled && !new.enabled) return old.type != RuleType.ALLOW
        if (!old.enabled && new.enabled) return old.type == RuleType.ALLOW

        // Type changes.
        if (old.type != new.type) {
            if (new.type == RuleType.ALLOW) return true
            if (old.type == RuleType.ALLOW) return false
            if (new.type == RuleType.BLOCK) return false      // switching to unconditional block = stricter
            if (old.type == RuleType.BLOCK) return true       // block -> conditional = looser
            return true                                       // WINDOW <-> QUOTA: ambiguous, fail closed
        }

        // Same type: pattern dimension, then parameter dimension.
        return when (new.type) {
            RuleType.ALLOW -> !patternSubset(new.pattern, old.pattern)
            RuleType.BLOCK -> !patternSubset(old.pattern, new.pattern)
            RuleType.WINDOW ->
                !patternSubset(old.pattern, new.pattern) || !windowCoverageSubset(new, old)
            RuleType.QUOTA ->
                !patternSubset(old.pattern, new.pattern) || new.quotaMin > old.quotaMin
        }
    }

    private fun normalize(pattern: String): String =
        pattern.trim().lowercase().removePrefix("*.")

    /**
     * True if every target matching [a] also matches [b] (a's match-set is a subset of b's).
     * Only understands domain-style suffix patterns and "*"; anything else -> false (safe).
     */
    fun patternSubset(a: String, b: String): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return false
        if (nb == "*") return true
        if (na == "*") return false
        return na == nb || na.endsWith(".$nb")
    }

    /** True if [new]'s (day, minute) coverage is a subset of [old]'s — i.e. tightening or equal. */
    fun windowCoverageSubset(new: Shape, old: Shape): Boolean {
        for (day in 0 until 7) {
            for (minute in 0 until 1440) {
                if (covered(new.daysMask, new.startMin, new.endMin, day, minute) &&
                    !covered(old.daysMask, old.startMin, old.endMin, day, minute)
                ) return false
            }
        }
        return true
    }

    private fun covered(daysMask: Int, startMin: Int, endMin: Int, day: Int, minute: Int): Boolean {
        val dayBit = 1 shl day
        val prevDayBit = 1 shl ((day + 6) % 7)
        return if (startMin == endMin) {
            daysMask and dayBit != 0
        } else if (startMin < endMin) {
            daysMask and dayBit != 0 && minute in startMin until endMin
        } else {
            (daysMask and dayBit != 0 && minute >= startMin) ||
                (daysMask and prevDayBit != 0 && minute < endMin)
        }
    }
}
