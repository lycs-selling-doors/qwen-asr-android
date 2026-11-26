package dev.workspace.qwen.asr

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class AppDestination(val title: String, val icon: ImageVector) {
    LIVE("听写", AppIcons.Mic),
    SERVICE("服务", AppIcons.Server),
    LOGS("日志", AppIcons.Terminal),
    SETTINGS("设置", AppIcons.Settings)
}

@Composable
internal fun DictationApp(model: DictationModel, activity: MainActivity) {
    val state by model.state.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(AppDestination.LIVE) }
    var settingsSection by rememberSaveable { mutableStateOf("") }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val snackbarHostState = remember { SnackbarHostState() }

    fun openSettings(page: String) {
        destination = AppDestination.SETTINGS
        settingsSection = page
    }

    BackHandler(destination != AppDestination.LIVE || settingsSection.isNotEmpty()) {
        if (settingsSection.isNotEmpty()) settingsSection = ""
        else destination = AppDestination.LIVE
    }

    LaunchedEffect(state.notice) {
        if (state.notice.isNotBlank()) {
            snackbarHostState.showSnackbar(state.notice, duration = SnackbarDuration.Short)
            if (model.state.value.notice == state.notice) model.notify("")
        }
    }

    val gatewayRunning = state.gateway.addresses().isNotEmpty()
    val needsSetup = !state.modelReady && !state.checkingLegacy

    QwenDictationTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = {
                SnackbarHost(snackbarHostState) { data ->
                    Snackbar(
                        snackbarData = data,
                        shape = RoundedCornerShape(16.dp),
                        containerColor = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface
                    )
                }
            },
            bottomBar = {
                if (!landscape) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp
                    ) {
                        AppDestination.entries.forEach { item ->
                            val selected = destination == item
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    destination = item
                                    if (item != AppDestination.SETTINGS) settingsSection = ""
                                },
                                icon = {
                                    DestinationIcon(
                                        item = item,
                                        gatewayRunning = gatewayRunning,
                                        needsSetup = needsSetup
                                    )
                                },
                                label = { Text(item.title, style = MaterialTheme.typography.labelMedium) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            val contentPane: @Composable () -> Unit = {
                AnimatedContent(
                    targetState = destination,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
                    label = "rootDestination"
                ) { target ->
                    when (target) {
                        AppDestination.LIVE -> LiveScreen(
                            state = state,
                            model = model,
                            activity = activity,
                            compact = landscape,
                            onConfigure = { openSettings("首次配置") },
                            onOpenTranslation = { openSettings("翻译") },
                            onOpenModels = { openSettings("模型") }
                        )
                        AppDestination.SERVICE -> ServiceScreen(
                            state = state,
                            model = model,
                            activity = activity,
                            onOpenGatewayConfig = { openSettings("服务配置") }
                        )
                        AppDestination.LOGS -> LogsScreen(
                            state = state,
                            model = model,
                            activity = activity
                        )
                        AppDestination.SETTINGS -> SettingsScreen(
                            section = settingsSection,
                            state = state,
                            model = model,
                            activity = activity,
                            onOpenSection = { settingsSection = it },
                            onGoHome = {
                                settingsSection = ""
                                destination = AppDestination.LIVE
                            }
                        )
                    }
                }
            }

            if (landscape) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        Spacer(Modifier.height(10.dp))
                        AppDestination.entries.forEach { item ->
                            val selected = destination == item
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    destination = item
                                    if (item != AppDestination.SETTINGS) settingsSection = ""
                                },
                                icon = {
                                    DestinationIcon(
                                        item = item,
                                        gatewayRunning = gatewayRunning,
                                        needsSetup = needsSetup
                                    )
                                },
                                label = { Text(item.title, style = MaterialTheme.typography.labelSmall) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                                )
                            )
                        }
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        contentPane()
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    contentPane()
                }
            }
        }
    }
}

@Composable
private fun DestinationIcon(
    item: AppDestination,
    gatewayRunning: Boolean,
    needsSetup: Boolean
) {
    val showBadge = (item == AppDestination.SERVICE && gatewayRunning) ||
        (item == AppDestination.SETTINGS && needsSetup)
    if (showBadge) {
        BadgedBox(
            badge = {
                Badge(
                    containerColor = if (item == AppDestination.SERVICE) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }
        ) {
            Icon(imageVector = item.icon, contentDescription = item.title)
        }
    } else {
        Icon(imageVector = item.icon, contentDescription = item.title)
    }
}
