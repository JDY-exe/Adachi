package com.adachi.lockdown.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adachi.lockdown.data.*

@Composable fun RulesScreen(vm:RulesViewModel,snackbar:SnackbarHostState) { val rules by vm.rules.collectAsStateWithLifecycle(); val msg by vm.message.collectAsStateWithLifecycle(); var adding by remember{mutableStateOf(false)}; LaunchedEffect(msg){msg?.let{snackbar.showSnackbar(it);vm.clearMessage()}}; Scaffold(floatingActionButton={FloatingActionButton({adding=true}){Icon(Icons.Default.Add,"Add rule")}}){pad-> LazyColumn(Modifier.fillMaxSize().padding(pad),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){item{Text("Rules",style=MaterialTheme.typography.headlineSmall);Text("Rules can include apps, domains, or both.")};items(rules,key={it.rule.id}){item->Card{Column(Modifier.padding(16.dp)){Text(item.rule.name,style=MaterialTheme.typography.titleMedium);Text("${item.rule.mode} • ${item.apps.size} apps • ${item.domains.size} domains");Row{TextButton({vm.toggle(item)}){Text(if(item.rule.enabled)"Disable" else "Enable")};TextButton({vm.delete(item)}){Text("Delete")}}}}} } }; if(adding) RuleDialog(vm,{adding=false}){vm.save(null,it);adding=false} }
@Composable private fun RuleDialog(vm:RulesViewModel,onDismiss:()->Unit,onSave:(RuleWithTargets)->Unit){
    val apps by vm.installedApps.collectAsStateWithLifecycle()
    var name by remember{mutableStateOf("")}; var domains by remember{mutableStateOf("")}
    var selected by remember{mutableStateOf(setOf<String>())}; var mode by remember{mutableStateOf(RuleMode.BLOCK)}
    var allowance by remember{mutableStateOf("30")}; var showPicker by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Add rule")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedTextField(name,{name=it},label={Text("Name")})
            OutlinedTextField(domains,{domains=it},label={Text("Domains (comma-separated)")})
            OutlinedButton(onClick={showPicker=true}, modifier=Modifier.fillMaxWidth()) {
                Text(if(selected.isEmpty()) "Choose apps…" else "${selected.size} app${if(selected.size == 1) "" else "s"} selected")
            }
            RuleMode.entries.forEach{m->FilterChip(mode==m,{mode=m},{Text(m.name.replace('_',' '))})}
            if(mode==RuleMode.TIMED) OutlinedTextField(allowance,{allowance=it.filter(Char::isDigit)},label={Text("Daily minutes")})
        }
    },confirmButton={TextButton(enabled=name.isNotBlank()&&(domains.isNotBlank()||selected.isNotEmpty()),onClick={
        onSave(RuleWithTargets(Rule(name=name,mode=mode,timedAllowanceMin=allowance.toIntOrNull()?:0),selected.map{p->RuleAppTarget(0,p,apps.find{it.packageName==p}?.label?:p)},domains.split(',').map{it.trim()}.filter{it.isNotBlank()}.map{RuleDomainTarget(0,it)}))
    }){Text("Save")}},dismissButton={TextButton(onDismiss){Text("Cancel")}})
    if(showPicker) MultiAppPickerDialog(apps, selected, { showPicker=false }) { selected=it; showPicker=false }
}

@Composable private fun MultiAppPickerDialog(apps:List<InstalledApp>, selected:Set<String>, onDismiss:()->Unit, onSave:(Set<String>)->Unit) {
    var query by remember { mutableStateOf("") }; var picks by remember { mutableStateOf(selected) }
    val filtered = apps.filter { query.isBlank() || it.label.contains(query,true) || it.packageName.contains(query,true) }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Choose apps")},text={Column{
        OutlinedTextField(query,{query=it},label={Text("Search")},singleLine=true,modifier=Modifier.fillMaxWidth())
        LazyColumn(Modifier.height(320.dp)) { items(filtered,key={it.packageName}) { app ->
            Row(Modifier.fillMaxWidth().clickable { picks=if(app.packageName in picks) picks-app.packageName else picks+app.packageName }.padding(vertical=8.dp), verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(app.packageName in picks, onCheckedChange={ checked -> picks=if(checked) picks+app.packageName else picks-app.packageName })
                Column { Text(app.label); Text(app.packageName,style=MaterialTheme.typography.bodySmall) }
            }
        }}
    }},confirmButton={TextButton(onClick={onSave(picks)}){Text("Done")}},dismissButton={TextButton(onDismiss){Text("Cancel")}})
}
