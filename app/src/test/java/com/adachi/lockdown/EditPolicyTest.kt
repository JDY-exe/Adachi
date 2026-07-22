package com.adachi.lockdown
import com.adachi.lockdown.data.*
import com.adachi.lockdown.rules.EditPolicy
import org.junit.Assert.*
import org.junit.Test
class EditPolicyTest { private fun r(mode:RuleMode=RuleMode.BLOCK, targets:List<String> = listOf("a"), allowance:Int=30)=RuleWithTargets(Rule(1,"x",mode,timedAllowanceMin=allowance),targets.map{RuleAppTarget(1,it)},emptyList()); @Test fun deletingBlockRelaxes(){assertTrue(EditPolicy.isRelaxing(r(),null))}; @Test fun expandingBlockTargetsRestricts(){assertFalse(EditPolicy.isRelaxing(r(targets=listOf("a")),r(targets=listOf("a","b"))))}; @Test fun raisingTimedAllowanceRelaxes(){assertTrue(EditPolicy.isRelaxing(r(RuleMode.TIMED,allowance=10),r(RuleMode.TIMED,allowance=20)))} }
