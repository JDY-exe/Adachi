package com.adachi.lockdown.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.adachi.lockdown.BuildConfig
import com.adachi.lockdown.admin.DeviceOwnerManager
import com.adachi.lockdown.status.SystemStatus
import com.adachi.lockdown.vpn.AdachiVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(vm: RulesViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshTick by remember { mutableStateOf(0) }
    var applying by remember { mutableStateOf(false) }
    var applyError by remember { mutableStateOf<String?>(null) }
    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) AdachiVpnService.start(context)
    }

    // Refresh the checks every couple of seconds (e.g. right after granting).
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            refreshTick++
        }
    }

    val accessibilityOn = remember(refreshTick) { SystemStatus.isAccessibilityEnabled(context) }
    val vpnOn = remember(refreshTick) { SystemStatus.isVpnRunning(context) }
    val deviceOwner = remember(refreshTick) { DeviceOwnerManager.isDeviceOwner(context) }
    val restrictionsOn = remember(refreshTick) { restrictionsApplied(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Setup", style = MaterialTheme.typography.headlineSmall)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Phase 1 — trial", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Use Adachi as a normal app first. Add your domain and app rules, " +
                        "start the VPN, enable the accessibility service, and live with it " +
                        "for a few days. If anything is wrong you can still uninstall normally.",
                )
                CheckRow("Domain filter (VPN) running", vpnOn)
                CheckRow("App blocker (accessibility)", accessibilityOn)
                if (!vpnOn) {
                    Button(onClick = {
                        VpnService.prepare(context)?.let { vpnPermission.launch(it) }
                            ?: AdachiVpnService.start(context)
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Set up and start domain-filter VPN")
                    }
                }
                if (!accessibilityOn) {
                    Button(onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Open accessibility settings")
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Phase 2 — lockdown (no mercy)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Provision Adachi as device owner. This blocks uninstalling, safe mode, " +
                        "ADB, force-stop, and VPN changes. Factory reset stays possible as your " +
                        "ultimate escape hatch. First remove ALL accounts on the phone " +
                        "(Settings → Passwords & accounts), then run from a computer:",
                )
                Text(
                    "adb shell dpm set-device-owner ${BuildConfig.APPLICATION_ID}/.admin.AdminReceiver",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Then come back here and apply the restrictions:")
                CheckRow("Device owner provisioned", deviceOwner)
                CheckRow("Lockdown restrictions applied", restrictionsOn)
                Button(
                    onClick = {
                        applying = true
                        applyError = null
                        scope.launch {
                            try {
                                DeviceOwnerManager.applyLockdown(context)
                            } catch (e: Exception) {
                                applyError = e.message
                            } finally {
                                applying = false
                            }
                        }
                    },
                    enabled = deviceOwner && !restrictionsOn && !applying,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (applying) "Applying…" else "Apply lockdown restrictions")
                }
                applyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

private fun restrictionsApplied(context: Context): Boolean {
    if (!DeviceOwnerManager.isDeviceOwner(context)) return false
    val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
    val admin = DeviceOwnerManager.adminComponent(context)
    val bundle: Bundle = dpm.getUserRestrictions(admin)
    return bundle.getBoolean(UserManager.DISALLOW_SAFE_BOOT, false) &&
        bundle.getBoolean(UserManager.DISALLOW_CONFIG_VPN, false)
}

@Composable
private fun CheckRow(label: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Text(
            if (ok) "✓" else "✗",
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold,
        )
    }
}
