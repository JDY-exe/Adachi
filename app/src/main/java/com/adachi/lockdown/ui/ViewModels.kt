package com.adachi.lockdown.ui

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.adachi.lockdown.data.*
import com.adachi.lockdown.unlock.UnlockManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate

private fun ticker(period:Long)= flow { while(true) { emit(System.currentTimeMillis()); delay(period) } }
data class InstalledApp(val label:String,val packageName:String)
class RulesViewModel(app:Application):AndroidViewModel(app) {
 private val repo=RulesRepository.get(app)
 val rules=repo.rules().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
 val checkIns=repo.checkIns().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
 val unlockActive=combine(repo.unlockState(),ticker(15000)){s,n->UnlockManager.isActive(s,n)}.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),false)
 val message=MutableStateFlow<String?>(null); val installedApps=MutableStateFlow<List<InstalledApp>>(emptyList())
 init { viewModelScope.launch(Dispatchers.IO) { val pm=getApplication<Application>().packageManager; val i=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER); installedApps.value=pm.queryIntentActivities(i,PackageManager.MATCH_ALL).map{InstalledApp(it.loadLabel(pm).toString(),it.activityInfo.packageName)}.distinctBy{it.packageName}.sortedBy{it.label.lowercase()} } }
 fun clearMessage(){message.value=null}
 fun save(old:RuleWithTargets?, item:RuleWithTargets)=run { if(old==null) repo.addRule(item,unlockActive.value) else repo.updateRule(old,item,unlockActive.value) }
 fun delete(item:RuleWithTargets)=run { repo.deleteRule(item,unlockActive.value) }
 fun toggle(item:RuleWithTargets)=save(item,item.copy(rule=item.rule.copy(enabled=!item.rule.enabled)))
 fun checkIn(id:Long,min:Int)=run { repo.checkIn(id,min) }
 private fun run(block:suspend()->Unit)=viewModelScope.launch { try { block() } catch(e:Exception) { message.value=e.message ?: "Unable to complete that action." } }
}
enum class LogFilter { ALL,BLOCKS,ERRORS }
class LogViewModel(app:Application):AndroidViewModel(app) { private val repo=RulesRepository.get(app); val filter=MutableStateFlow(LogFilter.ALL); val events=combine(repo.recentEvents(),filter){e,f->when(f){LogFilter.ALL->e;LogFilter.BLOCKS->e.filter{it.level=="BLOCK"};LogFilter.ERRORS->e.filter{it.level=="ERROR"}}}.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList()); fun clear()=viewModelScope.launch{repo.clearEvents()} }
class UnlockViewModel(app:Application):AndroidViewModel(app) { private val repo=RulesRepository.get(app); val unlockState=repo.unlockState().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null); val nowMs=ticker(1000).stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),System.currentTimeMillis()); val deviceOwner=MutableStateFlow(false); val error=MutableStateFlow<String?>(null); val info=MutableStateFlow<String?>(null); init { viewModelScope.launch { while(true){deviceOwner.value=com.adachi.lockdown.admin.DeviceOwnerManager.isDeviceOwner(app);delay(5000)}} }; fun spendWeeklyUnlock()=viewModelScope.launch{runCatching{repo.saveUnlockState(UnlockManager.spendWeeklyUnlock(repo.unlockStateNow(),System.currentTimeMillis(),LocalDate.now()))}.onFailure{error.value=it.message}}; fun travelMode(){com.adachi.lockdown.admin.DeviceOwnerManager.travelMode(getApplication());info.value="Timezone settings unlocked for 5 minutes."}; fun enableAdb(){com.adachi.lockdown.admin.DeviceOwnerManager.enableAdbTemporarily(getApplication());info.value="ADB re-enabled for 30 minutes."}; fun deactivate()=viewModelScope.launch{val app=getApplication<Application>();com.adachi.lockdown.vpn.AdachiVpnService.stop(app);com.adachi.lockdown.admin.DeviceOwnerManager.teardown(app);info.value="Restrictions lifted."}; fun spendMalfunctionPause()=viewModelScope.launch{runCatching{repo.saveUnlockState(UnlockManager.spendMalfunctionPause(repo.unlockStateNow(),System.currentTimeMillis(),LocalDate.now()))}.onFailure{error.value=it.message}}; fun clearError(){error.value=null}; fun clearInfo(){info.value=null} }
