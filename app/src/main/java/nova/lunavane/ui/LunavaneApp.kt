package nova.lunavane.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nova.lunavane.DiagnosticEntry
import nova.lunavane.R
import nova.lunavane.TunnelUiState
import nova.lunavane.ui.components.BrandMark

internal enum class AppTab {
    HOME,
    NETWORKS,
    MOON,
    DIAGNOSTICS,
}

@Composable
internal fun LunavaneApp(
    state: TunnelUiState,
    diagnostics: List<DiagnosticEntry>,
    savedNetworkId: String,
    actionPending: Boolean,
    connectPending: Boolean,
    permissionDenied: Boolean,
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    dynamicColorSupported: Boolean,
    appVersion: String,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onOrbit: (String, String) -> Unit,
    onDeorbit: (String) -> Unit,
    onClearDiagnostics: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(AppTab.HOME) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var settingsPage by rememberSaveable { mutableStateOf(SettingsPage.ROOT) }
    val motionEnabled = rememberMotionEnabled()
    val saveableStateHolder = rememberSaveableStateHolder()

    fun leaveSettings() {
        if (settingsPage == SettingsPage.ROOT) {
            settingsOpen = false
        } else {
            settingsPage = SettingsPage.ROOT
        }
    }

    BackHandler(enabled = settingsOpen) {
        leaveSettings()
    }
    BackHandler(enabled = !settingsOpen && tab != AppTab.HOME) {
        tab = AppTab.HOME
    }

    val destination: ShellDestination = if (settingsOpen) {
        ShellDestination.Settings(settingsPage)
    } else {
        ShellDestination.Tab(tab)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ShellTopBar(
                destination = destination,
                onBack = ::leaveSettings,
                onOpenSettings = {
                    settingsPage = SettingsPage.ROOT
                    settingsOpen = true
                },
            )
        },
        bottomBar = {
            if (!settingsOpen) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    AppTab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = {
                                tab = entry
                                settingsOpen = false
                            },
                            icon = {
                                Icon(
                                    imageVector = entry.icon,
                                    contentDescription = null,
                                )
                            },
                            label = { Text(stringResource(entry.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Crossfade(
            targetState = destination,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            animationSpec = if (motionEnabled) tween(180) else snap(),
            label = "app-destination",
        ) { shown ->
            saveableStateHolder.SaveableStateProvider(shown.saveKey) {
                when (shown) {
                    is ShellDestination.Tab -> when (shown.tab) {
                        AppTab.HOME -> HomeScreen(
                            state = state,
                            savedNetworkId = savedNetworkId,
                            actionPending = actionPending,
                            permissionDenied = permissionDenied,
                            onConnect = {
                                onConnect(savedNetworkId.ifBlank { state.networkId })
                            },
                            onDisconnect = onDisconnect,
                            onOpenNetworks = { tab = AppTab.NETWORKS },
                            onOpenDiagnostics = { tab = AppTab.DIAGNOSTICS },
                        )
                        AppTab.NETWORKS -> NetworksScreen(
                            state = state,
                            savedNetworkId = savedNetworkId,
                            actionPending = actionPending,
                            connectPending = connectPending,
                            permissionDenied = permissionDenied,
                            onConnect = onConnect,
                            onDisconnect = onDisconnect,
                        )
                        AppTab.MOON -> MoonScreen(
                            state = state,
                            onOrbit = onOrbit,
                            onDeorbit = onDeorbit,
                        )
                        AppTab.DIAGNOSTICS -> DiagnosticsScreen(
                            state = state,
                            diagnostics = diagnostics,
                            onClearDiagnostics = onClearDiagnostics,
                        )
                    }
                    is ShellDestination.Settings -> SettingsScreen(
                        page = shown.page,
                        themeMode = themeMode,
                        dynamicColor = dynamicColor,
                        dynamicColorSupported = dynamicColorSupported,
                        appVersion = appVersion,
                        onThemeModeChange = onThemeModeChange,
                        onDynamicColorChange = onDynamicColorChange,
                        onOpenPage = { settingsPage = it },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShellTopBar(
    destination: ShellDestination,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val title: String
    val navigationIcon: (@Composable () -> Unit)?
    val showSettings: Boolean
    when (destination) {
        is ShellDestination.Tab -> {
            navigationIcon = null
            showSettings = destination.tab == AppTab.HOME
            title = when (destination.tab) {
                AppTab.HOME -> ""
                AppTab.NETWORKS -> stringResource(R.string.nav_networks)
                AppTab.MOON -> stringResource(R.string.nav_moon)
                AppTab.DIAGNOSTICS -> stringResource(R.string.nav_diagnostics)
            }
        }
        is ShellDestination.Settings -> {
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            }
            showSettings = false
            title = when (destination.page) {
                SettingsPage.ROOT -> stringResource(R.string.settings_title)
                SettingsPage.ABOUT -> stringResource(R.string.about_title)
                SettingsPage.LICENSES -> stringResource(R.string.licenses_title)
            }
        }
    }

    TopAppBar(
        title = {
            if (destination is ShellDestination.Tab && destination.tab == AppTab.HOME) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandMark()
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                Text(text = title)
            }
        },
        navigationIcon = { navigationIcon?.invoke() },
        actions = {
            if (showSettings) {
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.action_settings),
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    )
}

private sealed interface ShellDestination {
    data class Tab(val tab: AppTab) : ShellDestination
    data class Settings(val page: SettingsPage) : ShellDestination
}

// SaveableStateHolder keys must be Bundle-compatible, so use stable strings.
private val ShellDestination.saveKey: String
    get() = when (this) {
        is ShellDestination.Tab -> "tab:${tab.name}"
        is ShellDestination.Settings -> "settings:${page.name}"
    }

private val AppTab.labelRes: Int
    get() = when (this) {
        AppTab.HOME -> R.string.nav_home
        AppTab.NETWORKS -> R.string.nav_networks
        AppTab.MOON -> R.string.nav_moon
        AppTab.DIAGNOSTICS -> R.string.nav_diagnostics
    }

private val AppTab.icon: ImageVector
    get() = when (this) {
        AppTab.HOME -> Icons.Filled.Home
        AppTab.NETWORKS -> Icons.AutoMirrored.Filled.List
        AppTab.MOON -> Icons.Filled.Star
        AppTab.DIAGNOSTICS -> Icons.Filled.Build
    }
