package com.adachi.lockdown.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.adachi.lockdown.data.EventLog

/**
 * Live diagnostic feed: every DNS verdict, upstream health event, app block,
 * and VPN lifecycle event. Updates in near-real-time (Room-observed, batched
 * writes every ~1.5s). Newest first.
 */
@Composable
fun LogScreen(vm: LogViewModel = viewModel()) {
    val events by vm.events.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = filter == LogFilter.ALL,
                onClick = { vm.filter.value = LogFilter.ALL },
                label = { Text("All") },
            )
            FilterChip(
                selected = filter == LogFilter.BLOCKS,
                onClick = { vm.filter.value = LogFilter.BLOCKS },
                label = { Text("Blocks") },
            )
            FilterChip(
                selected = filter == LogFilter.ERRORS,
                onClick = { vm.filter.value = LogFilter.ERRORS },
                label = { Text("Errors") },
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.clear() }) { Text("Clear") }
        }

        if (events.isEmpty()) {
            Text(
                "No events yet. Start the domain protection and browse — " +
                    "every DNS verdict shows up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(events, key = { it.id }) { event ->
                    LogRow(event)
                }
            }
        }
    }
}

@Composable
private fun LogRow(event: EventLog) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            formatLogTime(event.epochMs),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            event.level,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = levelColor(event.level),
        )
        Text(
            event.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun levelColor(level: String): Color = when (level) {
    "BLOCK" -> MaterialTheme.colorScheme.error
    "ERROR" -> MaterialTheme.colorScheme.error
    "ALLOW" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun formatLogTime(epochMs: Long): String {
    val t = java.time.Instant.ofEpochMilli(epochMs)
        .atZone(java.time.ZoneId.systemDefault())
    return "%d:%02d:%02d".format(t.hour, t.minute, t.second)
}
