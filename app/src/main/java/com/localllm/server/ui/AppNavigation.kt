package com.localllm.server.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.localllm.server.ui.screens.HomeScreen
import com.localllm.server.ui.screens.LogScreen
import com.localllm.server.ui.screens.ModelScreen
import com.localllm.server.ui.screens.SettingsScreen

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Server", Icons.Filled.Dns),
    MODELS("models", "Models", Icons.Filled.Inventory2),
    LOGS("logs", "Logs", Icons.AutoMirrored.Filled.List),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

@Composable
fun AppNavigation() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val go: (String) -> Unit = { route ->
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = { go(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.HOME.route, modifier = Modifier.padding(padding)) {
            composable(Tab.HOME.route) { HomeScreen(onOpenModels = { go(Tab.MODELS.route) }, onOpenLogs = { go(Tab.LOGS.route) }) }
            composable(Tab.MODELS.route) { ModelScreen() }
            composable(Tab.LOGS.route) { LogScreen() }
            composable(Tab.SETTINGS.route) { SettingsScreen() }
        }
    }
}
