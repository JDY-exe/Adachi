package com.adachi.lockdown.ui

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.adachi.lockdown.vpn.AdachiVpnService

@Composable
fun DashboardScreen(
    onOpenUnlock: () -> Unit,
    onOpenSetup: () -> Unit,
    vm: DashboardViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val recentBlocks by vm.recentBlocks.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val vpnConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) AdachiVpnService.start(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Adachi", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        // ---- Status card ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.enforcementPaused) {
                    Text("PAUSED", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
                    Text("Enforcement resumes in ${formatCountdown(state.pauseRemainingMs)}")
                } else {
                    Text("LOCKDOWN ACTIVE", style = MaterialTheme.typography.titleLarge)
                }
                Text("Blocked today: ${state.blocksToday}")
                if (state.inGracePeriod) {
                    Text(
                        "Grace period: full removal available (first 48h after provisioning)",
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // ---- Arming checklist ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Protection status", style = MaterialTheme.typography.titleMedium)
                StatusRow("Domain filter (VPN)", state.vpnRunning)
                StatusRow("App blocker (accessibility)", state.accessibilityOn)
                StatusRow("Anti-tamper (device owner)", state.deviceOwner)

                if (!state.vpnRunning) {
                    Button(
                        onClick = {
                            val prepare = VpnService.prepare(context)
                            if (prepare != null) vpnConsent.launch(prepare)
                            else AdachiVpnService.start(context)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Start domain protection") }
                }

                if (!state.vpnRunning || !state.accessibilityOn || !state.deviceOwner) {
                    OutlinedButton(onClick = onOpenSetup, modifier = Modifier.fillMaxWidth()) {
                        Text("Open setup")
                    }
                }
            }
        }

        // ---- Emergency ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Emergency", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.weeklyUnlockAvailable) "Weekly unlock: available"
                    else "Weekly unlock: used this week",
                )
                OutlinedButton(onClick = onOpenUnlock, modifier = Modifier.fillMaxWidth()) {
                    Text("Open emergency unlock")
                }
            }
        }

        // ---- Recent blocks ----
        if (recentBlocks.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Recent blocks", style = MaterialTheme.typography.titleMedium)
                    recentBlocks.forEach { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                entry.target,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                formatBlockTime(entry.epochMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

private fun formatBlockTime(epochMs: Long): String {
    val t = java.time.Instant.ofEpochMilli(epochMs)
        .atZone(java.time.ZoneId.systemDefault())
    return "%d:%02d".format(t.hour, t.minute)
}

@Composable
private fun StatusRow(label: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Text(
            if (ok) "ON" else "OFF",
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold,
        )
    }
}
