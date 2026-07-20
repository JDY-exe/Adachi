package com.adachi.lockdown

import com.adachi.lockdown.data.ALL_DAYS_MASK
import com.adachi.lockdown.data.AppRule
import com.adachi.lockdown.data.DomainRule
import com.adachi.lockdown.data.RuleType
import com.adachi.lockdown.rules.RuleEngine
import com.adachi.lockdown.rules.RuleEngine.Reason
import com.adachi.lockdown.rules.RuleEngine.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class RuleEngineTest {

    // 2026-07-20 is a Monday.
    private val mon10am: LocalDateTime = LocalDateTime.of(2026, 7, 20, 10, 0)
    private val mon11pm: LocalDateTime = LocalDateTime.of(2026, 7, 20, 23, 0)
    private val tue1am: LocalDateTime = LocalDateTime.of(2026, 7, 21, 1, 0)
    private val tue10am: LocalDateTime = LocalDateTime.of(2026, 7, 21, 10, 0)

    private fun domainRule(
        id: Long = 1,
        pattern: String = "reddit.com",
        type: RuleType = RuleType.BLOCK,
        daysMask: Int = ALL_DAYS_MASK,
        startMin: Int = 0,
        endMin: Int = 0,
        quotaMin: Int = 0,
        enabled: Boolean = true,
    ) = DomainRule(id, pattern, type, daysMask, startMin, endMin, quotaMin, enabled)

    // ---------- matchesDomain ----------

    @Test
    fun `domain matches exact pattern`() {
        assertTrue(RuleEngine.matchesDomain("reddit.com", "reddit.com"))
    }

    @Test
    fun `domain matches subdomain of bare pattern`() {
        assertTrue(RuleEngine.matchesDomain("reddit.com", "old.reddit.com"))
    }

    @Test
    fun `domain matches wildcard prefix pattern`() {
        assertTrue(RuleEngine.matchesDomain("*.reddit.com", "old.reddit.com"))
        assertTrue(RuleEngine.matchesDomain("*.reddit.com", "reddit.com"))
    }

    @Test
    fun `domain does not match partial suffix`() {
        assertFalse(RuleEngine.matchesDomain("reddit.com", "notreddit.com"))
        assertFalse(RuleEngine.matchesDomain("reddit.com", "reddit.com.evil.org"))
    }

    @Test
    fun `domain star matches everything`() {
        assertTrue(RuleEngine.matchesDomain("*", "anything.example.org"))
    }

    @Test
    fun `domain matching is case and trailing-dot insensitive`() {
        assertTrue(RuleEngine.matchesDomain("Reddit.COM", "OLD.Reddit.com."))
    }

    // ---------- matchesApp ----------

    @Test
    fun `app matches exact package and star`() {
        assertTrue(RuleEngine.matchesApp("com.reddit.frontpage", "com.reddit.frontpage"))
        assertTrue(RuleEngine.matchesApp("*", "com.anything"))
        assertFalse(RuleEngine.matchesApp("com.reddit.frontpage", "com.twitter.android"))
    }

    // ---------- inWindow ----------

    @Test
    fun `window simple inside and outside`() {
        val nineToFive = 9 * 60 to 17 * 60
        assertTrue(RuleEngine.inWindow(ALL_DAYS_MASK, nineToFive.first, nineToFive.second, mon10am))
        assertFalse(RuleEngine.inWindow(ALL_DAYS_MASK, nineToFive.first, nineToFive.second, mon11pm))
    }

    @Test
    fun `window respects day mask`() {
        val monOnly = 0b0000001
        assertTrue(RuleEngine.inWindow(monOnly, 9 * 60, 17 * 60, mon10am))
        assertFalse(RuleEngine.inWindow(monOnly, 9 * 60, 17 * 60, tue10am))
    }

    @Test
    fun `wrapping window covers late evening and next morning`() {
        // 22:00 - 02:00, marked Monday only.
        val monOnly = 0b0000001
        assertTrue(RuleEngine.inWindow(monOnly, 22 * 60, 2 * 60, mon11pm))   // Mon 23:00
        assertTrue(RuleEngine.inWindow(monOnly, 22 * 60, 2 * 60, tue1am))    // Tue 01:00 (Mon window wraps)
        assertFalse(RuleEngine.inWindow(monOnly, 22 * 60, 2 * 60, tue10am))  // Tue 10:00
        // Not marked Tuesday, so Tue 23:00 is outside.
        val tue11pm = LocalDateTime.of(2026, 7, 21, 23, 0)
        assertFalse(RuleEngine.inWindow(monOnly, 22 * 60, 2 * 60, tue11pm))
    }

    @Test
    fun `start equals end means all day on masked days`() {
        val monOnly = 0b0000001
        assertTrue(RuleEngine.inWindow(monOnly, 0, 0, mon11pm))
        assertFalse(RuleEngine.inWindow(monOnly, 0, 0, tue1am))
    }

    // ---------- evaluateDomain ----------

    @Test
    fun `no matching rule allows`() {
        val verdict = RuleEngine.evaluateDomain("example.com", listOf(domainRule()), mon10am)
        assertEquals(Verdict.Allow, verdict)
    }

    @Test
    fun `block rule blocks`() {
        val verdict = RuleEngine.evaluateDomain("reddit.com", listOf(domainRule()), mon10am)
        assertEquals(Verdict.Block(Reason.BLOCKED, 1), verdict)
    }

    @Test
    fun `allow rule beats block rule`() {
        val rules = listOf(
            domainRule(id = 1, pattern = "*", type = RuleType.BLOCK),
            domainRule(id = 2, pattern = "wikipedia.org", type = RuleType.ALLOW),
        )
        assertEquals(Verdict.Allow, RuleEngine.evaluateDomain("wikipedia.org", rules, mon10am))
        assertTrue(RuleEngine.evaluateDomain("reddit.com", rules, mon10am) is Verdict.Block)
    }

    @Test
    fun `window rule allows only inside window`() {
        val rule = domainRule(type = RuleType.WINDOW, startMin = 9 * 60, endMin = 17 * 60)
        assertEquals(Verdict.Allow, RuleEngine.evaluateDomain("reddit.com", listOf(rule), mon10am))
        assertEquals(
            Verdict.Block(Reason.OUTSIDE_WINDOW, 1),
            RuleEngine.evaluateDomain("reddit.com", listOf(rule), mon11pm),
        )
    }

    @Test
    fun `quota rule blocks when exhausted`() {
        val rule = domainRule(type = RuleType.QUOTA, quotaMin = 30)
        assertEquals(
            Verdict.Allow,
            RuleEngine.evaluateDomain("reddit.com", listOf(rule), mon10am, mapOf(1L to 12)),
        )
        assertEquals(
            Verdict.Block(Reason.QUOTA_EXHAUSTED, 1),
            RuleEngine.evaluateDomain("reddit.com", listOf(rule), mon10am, mapOf(1L to 30)),
        )
    }

    @Test
    fun `window and quota both must pass`() {
        val rules = listOf(
            domainRule(id = 1, type = RuleType.WINDOW, startMin = 9 * 60, endMin = 17 * 60),
            domainRule(id = 2, type = RuleType.QUOTA, quotaMin = 30),
        )
        assertEquals(
            Verdict.Allow,
            RuleEngine.evaluateDomain("reddit.com", rules, mon10am, mapOf(2L to 5)),
        )
        // Inside window but over quota.
        assertEquals(
            Verdict.Block(Reason.QUOTA_EXHAUSTED, 2),
            RuleEngine.evaluateDomain("reddit.com", rules, mon10am, mapOf(2L to 31)),
        )
        // Under quota but outside window.
        assertEquals(
            Verdict.Block(Reason.OUTSIDE_WINDOW, 1),
            RuleEngine.evaluateDomain("reddit.com", rules, mon11pm, mapOf(2L to 5)),
        )
    }

    @Test
    fun `disabled rules are ignored`() {
        val rule = domainRule(enabled = false)
        assertEquals(Verdict.Allow, RuleEngine.evaluateDomain("reddit.com", listOf(rule), mon10am))
    }

    // ---------- evaluateApp ----------

    @Test
    fun `app star block with allow exception`() {
        val rules = listOf(
            AppRule(1, "*", type = RuleType.BLOCK),
            AppRule(2, "com.google.android.dialer", type = RuleType.ALLOW),
        )
        assertEquals(Verdict.Allow, RuleEngine.evaluateApp("com.google.android.dialer", rules, mon10am))
        assertTrue(RuleEngine.evaluateApp("com.reddit.frontpage", rules, mon10am) is Verdict.Block)
    }

    @Test
    fun `app quota exhausted blocks`() {
        val rules = listOf(AppRule(1, "com.reddit.frontpage", type = RuleType.QUOTA, quotaMin = 15))
        assertEquals(
            Verdict.Block(Reason.QUOTA_EXHAUSTED, 1),
            RuleEngine.evaluateApp("com.reddit.frontpage", rules, mon10am, mapOf(1L to 15)),
        )
    }
}
