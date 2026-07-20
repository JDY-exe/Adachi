package com.adachi.lockdown.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adachi.lockdown.data.ALL_DAYS_MASK
import com.adachi.lockdown.data.DomainRule
import com.adachi.lockdown.data.RuleType

@Composable
fun DomainRulesScreen(vm: RulesViewModel, snackbar: SnackbarHostState) {
    val rules by vm.domainRules.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<DomainRule?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add domain rule")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Domain rules",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (rules.isEmpty()) {
                    Text(
                        "No rules yet. Add a domain to block, e.g. reddit.com — " +
                            "it also covers subdomains. Use * to block everything.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(rules, key = { it.id }) { rule ->
                RuleCard(
                    title = rule.pattern,
                    type = rule.type,
                    detail = when (rule.type) {
                        RuleType.WINDOW -> windowSummary(rule.daysMask, rule.startMin, rule.endMin)
                        RuleType.QUOTA -> quotaSummary(rule.quotaMin)
                        else -> null
                    },
                    enabled = rule.enabled,
                    onToggle = { vm.toggleDomainRule(rule) },
                    onEdit = { editing = rule },
                    onDelete = { vm.deleteDomainRule(rule) },
                )
            }
        }
    }

    if (showAdd) {
        DomainRuleDialog(
            initial = null,
            onDismiss = { showAdd = false },
            onSave = { vm.saveDomainRule(null, it); showAdd = false },
        )
    }
    editing?.let { rule ->
        DomainRuleDialog(
            initial = rule,
            onDismiss = { editing = null },
            onSave = { vm.saveDomainRule(rule, it); editing = null },
        )
    }
}

@Composable
fun DomainRuleDialog(
    initial: DomainRule?,
    onDismiss: () -> Unit,
    onSave: (DomainRule) -> Unit,
) {
    var pattern by remember { mutableStateOf(initial?.pattern ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: RuleType.BLOCK) }
    var daysMask by remember { mutableStateOf(initial?.daysMask ?: ALL_DAYS_MASK) }
    var start by remember { mutableStateOf(initial?.let { formatMin(it.startMin) } ?: "9:00") }
    var end by remember { mutableStateOf(initial?.let { formatMin(it.endMin) } ?: "17:00") }
    var quota by remember { mutableStateOf(initial?.quotaMin?.takeIf { it > 0 }?.toString() ?: "30") }

    val valid = pattern.isNotBlank() && when (type) {
        RuleType.WINDOW -> parseHm(start) != null && parseHm(end) != null
        RuleType.QUOTA -> quota.toIntOrNull()?.let { it in 1..1440 } == true
        else -> true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add domain rule" else "Edit domain rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text("Domain (e.g. reddit.com, *.reddit.com, *)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TypeChips(type) { type = it }
                when (type) {
                    RuleType.WINDOW -> {
                        Text("Allowed on:", style = MaterialTheme.typography.labelLarge)
                        DayChips(daysMask) { daysMask = it }
                        OutlinedTextField(
                            value = start,
                            onValueChange = { start = it },
                            label = { Text("From (HH:MM)") },
                            singleLine = true,
                            isError = parseHm(start) == null,
                        )
                        OutlinedTextField(
                            value = end,
                            onValueChange = { end = it },
                            label = { Text("Until (HH:MM)") },
                            singleLine = true,
                            isError = parseHm(end) == null,
                        )
                        Text(
                            "A window may wrap midnight, e.g. 20:00–2:00.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    RuleType.QUOTA -> {
                        OutlinedTextField(
                            value = quota,
                            onValueChange = { quota = it.filter(Char::isDigit) },
                            label = { Text("Minutes per day") },
                            singleLine = true,
                            isError = quota.toIntOrNull()?.let { it !in 1..1440 } != false,
                        )
                    }
                    else -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        DomainRule(
                            id = initial?.id ?: 0,
                            pattern = pattern.trim(),
                            type = type,
                            daysMask = daysMask,
                            startMin = parseHm(start) ?: 0,
                            endMin = parseHm(end) ?: 0,
                            quotaMin = quota.toIntOrNull() ?: 0,
                            enabled = initial?.enabled ?: true,
                            createdAt = initial?.createdAt ?: 0,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
