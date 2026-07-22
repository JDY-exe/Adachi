package com.adachi.lockdown.rules

import com.adachi.lockdown.data.*
import java.time.LocalDateTime

/** Pure evaluator shared by DNS and app enforcement. */
object RuleEngine {
    sealed interface Verdict { data object Allow : Verdict; data class Block(val reason: Reason, val ruleId: Long) : Verdict }
    enum class Reason { BLOCKED, CHECK_IN_REQUIRED, OUTSIDE_TIME_FRAME }
    fun matchesDomain(pattern: String, domain: String): Boolean { val d=domain.trim().trimEnd('.').lowercase(); val p=pattern.trim().lowercase().removePrefix("*."); return d.isNotEmpty() && p.isNotEmpty() && (p=="*" || d==p || d.endsWith(".$p")) }
    fun matchesApp(pattern: String, packageName: String) = pattern.trim() == "*" || pattern.trim() == packageName.trim()
    fun inWindow(daysMask: Int, startMin: Int, endMin: Int, now: LocalDateTime): Boolean { val bit=1 shl(now.dayOfWeek.value-1); val prev=1 shl((now.dayOfWeek.value+5)%7); val min=now.hour*60+now.minute; return when { startMin==endMin -> daysMask and bit != 0; startMin<endMin -> daysMask and bit != 0 && min in startMin until endMin; else -> daysMask and bit != 0 && min>=startMin || daysMask and prev != 0 && min<endMin } }
    fun evaluateDomain(domain: String, rules: List<RuleWithTargets>, checkIns: Map<Long, RuleCheckIn>, now: LocalDateTime, nowMs: Long): Verdict = evaluate(rules.filter { r -> r.rule.enabled && r.domains.any { matchesDomain(it.pattern, domain) } }, checkIns, now, nowMs)
    fun evaluateApp(pkg: String, rules: List<RuleWithTargets>, checkIns: Map<Long, RuleCheckIn>, now: LocalDateTime, nowMs: Long): Verdict = evaluate(rules.filter { r -> r.rule.enabled && r.apps.any { matchesApp(it.packageName, pkg) } }, checkIns, now, nowMs)
    private fun evaluate(matches: List<RuleWithTargets>, checkIns: Map<Long, RuleCheckIn>, now: LocalDateTime, nowMs: Long): Verdict {
        if (matches.any { it.rule.mode == RuleMode.ALLOW }) return Verdict.Allow
        matches.firstOrNull { it.rule.mode == RuleMode.BLOCK }?.let { return Verdict.Block(Reason.BLOCKED, it.rule.id) }
        for (item in matches) when (item.rule.mode) {
            RuleMode.TIMED -> if (checkIns[item.rule.id]?.expiresAtMs ?: 0 <= nowMs) return Verdict.Block(Reason.CHECK_IN_REQUIRED, item.rule.id)
            RuleMode.TIME_FRAMED -> { if (!inWindow(item.rule.daysMask,item.rule.startMin,item.rule.endMin,now)) return Verdict.Block(Reason.OUTSIDE_TIME_FRAME,item.rule.id); if (checkIns[item.rule.id]?.expiresAtMs ?: 0 <= nowMs) return Verdict.Block(Reason.CHECK_IN_REQUIRED,item.rule.id) }
            else -> Unit
        }
        return Verdict.Allow
    }
}
