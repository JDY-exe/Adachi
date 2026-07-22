package com.adachi.lockdown.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adachi.lockdown.admin.DeviceOwnerManager
import com.adachi.lockdown.status.SystemStatus

@Composable fun DashboardScreen(onOpenUnlock:()->Unit,onOpenSetup:()->Unit) { val context=androidx.compose.ui.platform.LocalContext.current; Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){Text("Status",style=MaterialTheme.typography.headlineSmall);Text("VPN: ${if(SystemStatus.isVpnRunning(context)) "active" else "not running"}");Text("Accessibility: ${if(SystemStatus.isAccessibilityEnabled(context)) "enabled" else "needs setup"}");Text("Device owner: ${if(DeviceOwnerManager.isDeviceOwner(context)) "active" else "not provisioned"}");Button(onClick=onOpenSetup){Text("Setup and restrictions")};OutlinedButton(onClick=onOpenUnlock){Text("Emergency unlock and pause")} } }
