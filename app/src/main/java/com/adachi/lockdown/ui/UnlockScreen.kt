package com.adachi.lockdown.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.adachi.lockdown.unlock.UnlockManager
import kotlinx.coroutines.delay
import java.time.LocalDate

private const val HOLD_MS = 3000L

@Composable
fun UnlockScreen(vm: UnlockViewModel = viewModel()) {
    val state by vm.unlockState.collectAsStateWithLifecycle()
    val now by vm.nowMs.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    val deviceOwner by vm.deviceOwner.collectAsStateWithLifecycle()

    val today = remember { LocalDate.now() }
    val active = UnlockManager.isActive(state, now)
    val remaining = UnlockManager.remainingMs(state, now)
    val weeklyAvailable = UnlockManager.canSpendWeeklyUnlock(state, today)
    val malfunctionAvailable = UnlockManager.canMalfunctionPause(state, today)
    val inGrace = UnlockManager.inGracePeriod(state, now)

    var confirmMalfunction by remember { mutableStateOf(false) }
    var confirmDeactivate by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Emergency unlock", style = MaterialTheme.typography.headlineSmall)

        if (active) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "UNLOCKED",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("Enforcement paused. Time remaining: ${formatCountdown(remaining)}")
                    Text("You can edit or relax rules until the timer ends.")
                }
            }
        }

        // ---- Weekly unlock ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Weekly unlock", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Once per week you may pause lockdown for 30 minutes to adjust settings. " +
                        "This is the only way to relax rules. Use it deliberately.",
                )
                Text(
                    if (weeklyAvailable) "Status: available" else "Status: used this week",
                    fontWeight = FontWeight.Bold,
                )
                HoldToConfirmButton(
                    label = "Hold 3s to spend this week's unlock",
                    enabled = weeklyAvailable && !active,
                    onConfirmed = { vm.spendWeeklyUnlock() },
                )
            }
        }

        // ---- Malfunction pause ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Something's wrong?", style = MaterialTheme.typography.titleMedium)
                Text(
                    "If Adachi is misbehaving — blocking things it shouldn't — you can pause " +
                        "enforcement for 10 minutes, once per day. This does NOT use your weekly unlock.",
                )
                OutlinedButton(
                    onClick = { confirmMalfunction = true },
                    enabled = malfunctionAvailable && !active,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (malfunctionAvailable) "Pause 10 min (malfunction)" else "Malfunction pause used today")
                }
            }
        }

        // ---- Travel & tools ----
        if (deviceOwner) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Travel & tools", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Automatic timezone handles most travel. To override the timezone " +
                            "manually, unlock it here for 5 minutes — free, no unlock spent. " +
                            "Changing the DATE costs this week's unlock.",
                    )
                    OutlinedButton(onClick = { vm.travelMode() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Travel mode: unlock timezone (5 min)")
                    }
                    if (active) {
                        OutlinedButton(onClick = { vm.enableAdb() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Re-enable ADB (30 min)")
                        }
                    }
                }
            }
        }

        // ---- Deactivate ----
        if (inGrace || active) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Deactivate Adachi",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        if (inGrace) "Grace period: you can fully remove Adachi now, no unlock needed."
                        else "While unlocked, you can fully remove Adachi. This lifts every " +
                            "restriction and relinquishes device ownership. Factory reset " +
                            "remains possible afterwards regardless.",
                    )
                    Button(
                        onClick = { confirmDeactivate = true },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Deactivate completely") }
                }
            }
        }
    }

    if (confirmMalfunction) {
        AlertDialog(
            onDismissRequest = { confirmMalfunction = false },
            title = { Text("Pause for 10 minutes?") },
            text = {
                Text(
                    "Use this only if Adachi is blocking something it shouldn't. " +
                        "It works once per day and is logged.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.spendMalfunctionPause()
                    confirmMalfunction = false
                }) { Text("Pause 10 min") }
            },
            dismissButton = { TextButton(onClick = { confirmMalfunction = false }) { Text("Cancel") } },
        )
    }

    if (confirmDeactivate) {
        AlertDialog(
            onDismissRequest = { confirmDeactivate = false },
            title = { Text("Deactivate Adachi completely?") },
            text = {
                Text(
                    "This stops the VPN, lifts all device-owner restrictions, unblocks " +
                        "uninstalling, and relinquishes device ownership. Re-enabling later " +
                        "requires the ADB provisioning command again.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deactivate()
                    confirmDeactivate = false
                }) { Text("Deactivate") }
            },
            dismissButton = { TextButton(onClick = { confirmDeactivate = false }) { Text("Cancel") } },
        )
    }

    error?.let {
        AlertDialog(
            onDismissRequest = { vm.clearError() },
            title = { Text("Not available") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { vm.clearError() }) { Text("OK") } },
        )
    }

    info?.let {
        AlertDialog(
            onDismissRequest = { vm.clearInfo() },
            title = { Text("Adachi") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { vm.clearInfo() }) { Text("OK") } },
        )
    }
}

@Composable
fun HoldToConfirmButton(
    label: String,
    enabled: Boolean,
    onConfirmed: () -> Unit,
) {
    var holding by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(holding) {
        if (!holding) {
            progress = 0f
            return@LaunchedEffect
        }
        val stepMs = 50L
        var elapsed = 0L
        while (elapsed < HOLD_MS && holding) {
            delay(stepMs)
            elapsed += stepMs
            progress = (elapsed.toFloat() / HOLD_MS).coerceAtMost(1f)
        }
        if (holding && progress >= 1f) {
            holding = false
            progress = 0f
            onConfirmed()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                .background(
                    if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                )
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            holding = true
                            tryAwaitRelease()
                            holding = false
                        },
                    )
                }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = if (enabled) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
        }
        if (holding) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}
