package com.adachi.lockdown.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.adachi.lockdown.data.AppRule
import com.adachi.lockdown.data.RuleType

@Composable
fun AppRulesScreen(vm: RulesViewModel, snackbar: SnackbarHostState) {
    val rules by vm.appRules.collectAsStateWithLifecycle()
    val installed by vm.installedApps.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<AppRule?>(null) }
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
                Icon(Icons.Default.Add, contentDescription = "Add app rule")
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
                    "App rules",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (rules.isEmpty()) {
                    Text(
                        "No rules yet. Add an app to block or limit. " +
                            "Tip: blocking * with a few Allow rules is total lockdown mode.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(rules, key = { it.id }) { rule ->
                val label = if (rule.packageName == "*") "*" else
                    rule.label.ifBlank { installed.firstOrNull { it.packageName == rule.packageName }?.label }
                        ?: rule.packageName
                RuleCard(
                    title = label,
                    type = rule.type,
                    detail = when (rule.type) {
                        RuleType.WINDOW -> windowSummary(rule.daysMask, rule.startMin, rule.endMin)
                        RuleType.QUOTA -> quotaSummary(rule.quotaMin)
                        else -> rule.packageName.takeIf { it != label }
                    },
                    enabled = rule.enabled,
                    onToggle = { vm.toggleAppRule(rule) },
                    onEdit = { editing = rule },
                    onDelete = { vm.deleteAppRule(rule) },
                )
            }
        }
    }

    if (showAdd) {
        AppRuleDialog(
            vm = vm,
            initial = null,
            onDismiss = { showAdd = false },
            onSave = { vm.saveAppRule(null, it); showAdd = false },
        )
    }
    editing?.let { rule ->
        AppRuleDialog(
            vm = vm,
            initial = rule,
            onDismiss = { editing = null },
            onSave = { vm.saveAppRule(rule, it); editing = null },
        )
    }
}

@Composable
fun AppRuleDialog(
    vm: RulesViewModel,
    initial: AppRule?,
    onDismiss: () -> Unit,
    onSave: (AppRule) -> Unit,
) {
    val installed by vm.installedApps.collectAsStateWithLifecycle()
    var selected by remember {
        mutableStateOf(initial?.let { InstalledApp(it.label, it.packageName) })
    }
    var type by remember { mutableStateOf(initial?.type ?: RuleType.BLOCK) }
    var daysMask by remember { mutableStateOf(initial?.daysMask ?: ALL_DAYS_MASK) }
    var start by remember { mutableStateOf(initial?.let { formatMin(it.startMin) } ?: "9:00") }
    var end by remember { mutableStateOf(initial?.let { formatMin(it.endMin) } ?: "17:00") }
    var quota by remember { mutableStateOf(initial?.quotaMin?.takeIf { it > 0 }?.toString() ?: "30") }
    var showPicker by remember { mutableStateOf(false) }
    var wildcard by remember { mutableStateOf(initial?.packageName == "*") }

    val valid = (wildcard || selected != null) && when (type) {
        RuleType.WINDOW -> parseHm(start) != null && parseHm(end) != null
        RuleType.QUOTA -> quota.toIntOrNull()?.let { it in 1..1440 } == true
        else -> true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add app rule" else "Edit app rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row2 {
                    OutlinedButton(
                        onClick = { showPicker = true },
                        enabled = !wildcard,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(selected?.label ?: "Choose app…")
                    }
                    TextButton(onClick = { wildcard = !wildcard }) {
                        Text(if (wildcard) "All apps ✓" else "All apps")
                    }
                }
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
                    val pkg = if (wildcard) "*" else selected!!.packageName
                    val label = if (wildcard) "*" else selected!!.label
                    onSave(
                        AppRule(
                            id = initial?.id ?: 0,
                            packageName = pkg,
                            label = label,
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

    if (showPicker) {
        AppPickerDialog(installed, onDismiss = { showPicker = false }) {
            selected = it
            showPicker = false
        }
    }
}

@Composable
private fun Row2(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) { content() }
}

@Composable
fun AppPickerDialog(
    apps: List<InstalledApp>,
    onDismiss: () -> Unit,
    onPick: (InstalledApp) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose app") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(app) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                        ) {
                            Text(app.label)
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
