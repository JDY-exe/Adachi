package com.adachi.lockdown.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.adachi.lockdown.ui.theme.AdachiTheme

object Routes {
    const val DASHBOARD = "dashboard"
    const val DOMAINS = "domains"
    const val APPS = "apps"
    const val UNLOCK = "unlock"
    const val SETUP = "setup"
}

class MainActivity : ComponentActivity() {

    private val pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingRoute.value = intent.getStringExtra("route")
        requestNotificationPermissionIfNeeded()
        setContent {
            AdachiTheme {
                AdachiNavHost(pendingRoute)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        pendingRoute.value = intent.getStringExtra("route")
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}

@Composable
fun AdachiNavHost(pendingRoute: androidx.compose.runtime.MutableState<String?> = remember { mutableStateOf(null) }) {
    val nav = rememberNavController()
    val rulesVm: RulesViewModel = viewModel()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(pendingRoute.value) {
        when (pendingRoute.value) {
            "unlock" -> nav.navigate(Routes.UNLOCK)
        }
        pendingRoute.value = null
    }

    val items: List<Triple<String, String, @Composable () -> Unit>> = listOf(
        Triple(Routes.DASHBOARD, "Home") { Icon(Icons.Default.Home, contentDescription = "Home") },
        Triple(Routes.DOMAINS, "Domains") {
            Icon(androidx.compose.ui.res.painterResource(com.adachi.lockdown.R.drawable.ic_globe), contentDescription = "Domains")
        },
        Triple(Routes.APPS, "Apps") {
            Icon(androidx.compose.ui.res.painterResource(com.adachi.lockdown.R.drawable.ic_grid), contentDescription = "Apps")
        },
        Triple(Routes.UNLOCK, "Unlock") { Icon(Icons.Default.Lock, contentDescription = "Unlock") },
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                val backStack by nav.currentBackStackEntryAsState()
                val current = backStack?.destination?.route
                items.forEach { (route, label, icon) ->
                    NavigationBarItem(
                        selected = current == route,
                        onClick = {
                            nav.navigate(route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = icon,
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    onOpenUnlock = { nav.navigate(Routes.UNLOCK) },
                    onOpenSetup = { nav.navigate(Routes.SETUP) },
                )
            }
            composable(Routes.DOMAINS) { DomainRulesScreen(rulesVm, snackbar) }
            composable(Routes.APPS) { AppRulesScreen(rulesVm, snackbar) }
            composable(Routes.UNLOCK) { UnlockScreen() }
            composable(Routes.SETUP) { SetupScreen() }
        }
    }
}
