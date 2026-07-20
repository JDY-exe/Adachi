package com.adachi.lockdown.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adachi.lockdown.data.ALL_DAYS_MASK
import com.adachi.lockdown.data.RuleType

fun ruleTypeLabel(type: RuleType): String = when (type) {
    RuleType.BLOCK -> "Blocked"
    RuleType.ALLOW -> "Always allowed"
    RuleType.WINDOW -> "Time window"
    RuleType.QUOTA -> "Daily quota"
}

fun windowSummary(daysMask: Int, startMin: Int, endMin: Int): String {
    val days = DAY_LETTERS.mapIndexedNotNull { i, letter ->
        if (daysMask and (1 shl i) != 0) letter else null
    }
    val dayPart = if (daysMask == ALL_DAYS_MASK) "every day" else days.joinToString(" ")
    return "Allowed ${formatMin(startMin)}–${formatMin(endMin)}, $dayPart"
}

fun quotaSummary(quotaMin: Int): String = "Max $quotaMin min/day"

@Composable
fun TypeChips(selected: RuleType, onSelect: (RuleType) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RuleType.entries.forEach { type ->
            FilterChip(
                selected = selected == type,
                onClick = { onSelect(type) },
                label = {
                    Text(
                        when (type) {
                            RuleType.BLOCK -> "Block"
                            RuleType.ALLOW -> "Allow"
                            RuleType.WINDOW -> "Window"
                            RuleType.QUOTA -> "Quota"
                        },
                    )
                },
            )
        }
    }
}

@Composable
fun DayChips(mask: Int, onChange: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DAY_LETTERS.forEachIndexed { i, letter ->
            val bit = 1 shl i
            FilterChip(
                selected = mask and bit != 0,
                onClick = { onChange(mask xor bit) },
                label = { Text(letter) },
            )
        }
    }
}

@Composable
fun TimeField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text("9:00") },
        singleLine = true,
        isError = value.isNotBlank() && parseHm(value) == null,
        modifier = modifier,
    )
}

@Composable
fun RuleCard(
    title: String,
    type: RuleType,
    detail: String?,
    enabled: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(
                    ruleTypeLabel(type) + if (!enabled) " (disabled)" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodySmall)
                }
            }
            Switch(checked = enabled, onCheckedChange = { onToggle() })
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
        }
    }
}
