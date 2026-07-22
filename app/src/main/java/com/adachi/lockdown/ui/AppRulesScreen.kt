package com.adachi.lockdown.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adachi.lockdown.data.RuleMode
import java.time.LocalDate

@Composable
fun CheckInsScreen(vm: RulesViewModel, snackbar: SnackbarHostState) {
    val rules by vm.rules.collectAsStateWithLifecycle()
    val grants by vm.checkIns.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(msg) { msg?.let { snackbar.showSnackbar(it); vm.clearMessage() } }
    val now = System.currentTimeMillis()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Check-ins", style = MaterialTheme.typography.headlineSmall)
            Text("Choose a duration to reserve time and allow a conditional rule.")
        }
        items(rules.filter { it.rule.enabled && it.rule.mode in setOf(RuleMode.TIMED, RuleMode.TIME_FRAMED) }, key = { it.rule.id }) { item ->
            val grant = grants.find { it.ruleId == item.rule.id }
            val remaining = if (item.rule.mode == RuleMode.TIMED) item.rule.timedAllowanceMin - (if (grant?.localDate == LocalDate.now().toString()) grant.reservedMinutes else 0) else null
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.rule.name, style = MaterialTheme.typography.titleMedium)
                    Text("${item.apps.size} apps • ${item.domains.size} domains")
                    Text(if ((grant?.expiresAtMs ?: 0) > now) "Active for ${((grant!!.expiresAtMs - now) / 60000) + 1} min" else "Not checked in")
                    if (remaining != null) Text("$remaining minutes remaining today")
                    CheckInDurationRow(listOf(1, 5, 10, 20), remaining) { vm.checkIn(item.rule.id, it) }
                    CheckInDurationRow(listOf(30, 45, 60), remaining) { vm.checkIn(item.rule.id, it) }
                }
            }
        }
    }
}

@Composable private fun CheckInDurationRow(minutes:List<Int>, remaining:Int?, onCheckIn:(Int)->Unit) {
    Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
        minutes.forEach { minute ->
            AssistChip(onClick={onCheckIn(minute)}, label={Text("${minute}m")}, enabled=remaining==null||minute<=remaining)
        }
    }
}
