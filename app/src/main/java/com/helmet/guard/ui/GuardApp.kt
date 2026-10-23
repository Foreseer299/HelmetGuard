package com.helmet.guard.ui

import android.content.Intent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dataset
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsMotorsports
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import com.helmet.guard.ui.components.EmergencyDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.helmet.guard.AppGraph
import com.helmet.guard.ui.screens.ContactsScreen
import com.helmet.guard.ui.screens.DeviceScreen
import com.helmet.guard.ui.screens.HistoryScreen
import com.helmet.guard.ui.screens.HomeScreen
import com.helmet.guard.ui.screens.SettingsScreen
import com.helmet.guard.ui.screens.TelemetryScreen

private data class Destination(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
private val destinations = listOf(
    Destination("home", "守护", Icons.Outlined.Home),
    Destination("device", "设备", Icons.Outlined.SportsMotorsports),
    Destination("telemetry", "数据", Icons.Outlined.Dataset),
    Destination("history", "记录", Icons.Outlined.History),
    Destination("settings", "设置", Icons.Outlined.Settings)
)

@Composable
fun GuardApp(
    graph: AppGraph,
    requestCorePermissions: (afterResult: () -> Unit) -> Unit,
    startMonitoring: () -> Unit,
    stopMonitoring: () -> Unit,
    associateCompanion: () -> Unit,
    runServiceAction: (String, Intent.() -> Unit) -> Unit
) {
    EmergencyDialog(graph)
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(graph) {
        graph.runtime.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (route in destinations.map { it.route }) {
                // Material's compact label slot assumes normal text metrics. Let large
                // labels measure their own height, preserving font scale and tab semantics.
                val selectDestination: (Destination) -> Unit = { item ->
                    navController.navigate(item.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
                if (LocalDensity.current.fontScale > 1.2f) {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Row(
                            Modifier.fillMaxWidth().navigationBarsPadding().selectableGroup(),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            destinations.forEach { item ->
                                val selected = route == item.route
                                Column(
                                    Modifier.weight(1f).testTag("nav_${item.route}")
                                        .selectable(selected, role = Role.Tab, onClick = { selectDestination(item) })
                                        .heightIn(min = 80.dp).padding(horizontal = 2.dp, vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        Modifier.size(52.dp, 32.dp).background(
                                            if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                                            RoundedCornerShape(16.dp)
                                        ), contentAlignment = Alignment.Center
                                    ) {
                                        Icon(item.icon, null, tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(
                                        item.label, style = MaterialTheme.typography.labelMedium,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                } else {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        tonalElevation = 0.dp
                    ) {
                        destinations.forEach { item ->
                            NavigationBarItem(
                                modifier = Modifier.testTag("nav_${item.route}"),
                                selected = route == item.route,
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                onClick = { selectDestination(item) },
                                icon = { Icon(item.icon, null) },
                                label = { Text(item.label) }
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController, "home", Modifier.padding(padding).consumeWindowInsets(padding).testTag("guard_navigation"),
            enterTransition = { fadeIn(tween(180)) },
            exitTransition = { fadeOut(tween(160)) },
            popEnterTransition = { fadeIn(tween(180)) },
            popExitTransition = { fadeOut(tween(160)) }
        ) {
            composable("home") {
                HomeScreen(graph, requestCorePermissions, startMonitoring, stopMonitoring, { navController.navigate("telemetry") }, { navController.navigate("contacts") }, runServiceAction)
            }
            composable("device") { DeviceScreen(graph, requestCorePermissions, startMonitoring, associateCompanion) }
            composable("telemetry") { TelemetryScreen(graph, startMonitoring, runServiceAction) }
            composable("history") { HistoryScreen(graph) }
            composable("settings") { SettingsScreen(graph, requestCorePermissions, { navController.navigate("contacts") }) }
            composable("contacts") { ContactsScreen(graph) { navController.popBackStack() } }
        }
    }
}
