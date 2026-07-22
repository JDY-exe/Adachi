package com.adachi.lockdown.rules
import com.adachi.lockdown.data.*

/** Conservative access-broadening detector for the unified model. */
object EditPolicy {
 fun isRelaxing(old: RuleWithTargets?, new: RuleWithTargets?): Boolean {
  if(old==null) return new?.rule?.mode==RuleMode.ALLOW
  if(new==null) return old.rule.mode!=RuleMode.ALLOW
  val a=old.rule; val b=new.rule
  if(a.enabled&&!b.enabled) return a.mode!=RuleMode.ALLOW
  if(!a.enabled&&b.enabled) return a.mode==RuleMode.ALLOW
  if(a.mode!=b.mode) return b.mode==RuleMode.ALLOW || a.mode==RuleMode.BLOCK || (a.mode!=RuleMode.ALLOW && b.mode!=RuleMode.BLOCK)
  val oldTargets=old.apps.map { "a:${it.packageName}" }.toSet()+old.domains.map { "d:${it.pattern.lowercase()}" }
  val newTargets=new.apps.map { "a:${it.packageName}" }.toSet()+new.domains.map { "d:${it.pattern.lowercase()}" }
  return when(b.mode) { RuleMode.ALLOW -> !newTargets.all { it in oldTargets }; RuleMode.BLOCK -> !oldTargets.all { it in newTargets }; RuleMode.TIMED -> !oldTargets.all { it in newTargets } || b.timedAllowanceMin>a.timedAllowanceMin; RuleMode.TIME_FRAMED -> !oldTargets.all { it in newTargets } || !windowSubset(b,a) }
 }
 private fun windowSubset(n: Rule,o: Rule):Boolean { for(d in 0..6) for(m in 0..1439) if(covered(n,d,m)&&!covered(o,d,m)) return false; return true }
 private fun covered(r:Rule,d:Int,m:Int):Boolean { val bit=1 shl d; val prev=1 shl((d+6)%7); return when { r.startMin==r.endMin -> r.daysMask and bit != 0; r.startMin<r.endMin -> r.daysMask and bit != 0 && m in r.startMin until r.endMin; else -> r.daysMask and bit != 0 && m>=r.startMin || r.daysMask and prev != 0 && m<r.endMin } }
}
