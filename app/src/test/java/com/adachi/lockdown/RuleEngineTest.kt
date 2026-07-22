package com.adachi.lockdown
import com.adachi.lockdown.data.*
import com.adachi.lockdown.rules.RuleEngine
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class RuleEngineTest {
 private val now=LocalDateTime.of(2026,7,20,10,0); private fun rule(mode:RuleMode=RuleMode.TIMED)=RuleWithTargets(Rule(1,"Reddit",mode,timedAllowanceMin=30,startMin=9*60,endMin=17*60), listOf(RuleAppTarget(1,"com.reddit")),listOf(RuleDomainTarget(1,"reddit.com")))
 @Test fun domainMatching(){assertTrue(RuleEngine.matchesDomain("reddit.com","old.reddit.com"));assertFalse(RuleEngine.matchesDomain("reddit.com","notreddit.com"))}
 @Test fun inactiveTimedBlocksAndGrantAllows(){assertTrue(RuleEngine.evaluateDomain("reddit.com",listOf(rule()),emptyMap(),now,1000) is RuleEngine.Verdict.Block);assertEquals(RuleEngine.Verdict.Allow,RuleEngine.evaluateDomain("reddit.com",listOf(rule()),mapOf(1L to RuleCheckIn(1,"2026-07-20",10,2000)),now,1000))}
 @Test fun expiredGrantBlocks(){assertTrue(RuleEngine.evaluateApp("com.reddit",listOf(rule()),mapOf(1L to RuleCheckIn(1,"",10,999)),now,1000) is RuleEngine.Verdict.Block)}
 @Test fun timeFrameNeedsScheduleAndGrant(){val r=rule(RuleMode.TIME_FRAMED);assertTrue(RuleEngine.evaluateDomain("reddit.com",listOf(r),mapOf(1L to RuleCheckIn(1,"",0,2000)),now.withHour(20),1000) is RuleEngine.Verdict.Block);assertEquals(RuleEngine.Verdict.Allow,RuleEngine.evaluateDomain("reddit.com",listOf(r),mapOf(1L to RuleCheckIn(1,"",0,2000)),now,1000))}
 @Test fun allowWins(){val block=rule(RuleMode.BLOCK);val allow=rule(RuleMode.ALLOW).copy(rule=Rule(2,"exception",RuleMode.ALLOW));assertEquals(RuleEngine.Verdict.Allow,RuleEngine.evaluateDomain("reddit.com",listOf(block,allow),emptyMap(),now,1))}
}
