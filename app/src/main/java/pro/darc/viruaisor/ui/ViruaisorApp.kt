package pro.darc.viruaisor.ui

import android.content.Context
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import pro.darc.viruaisor.display.*
import pro.darc.viruaisor.gpu.VenusHostRuntime
import pro.darc.viruaisor.network.VmNetworkRuntime
import pro.darc.viruaisor.root.*
import pro.darc.viruaisor.ui.theme.ViruaisorTheme
import pro.darc.viruaisor.vm.*

@Composable
internal fun VmScreen(
    state: VmUiState, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit, onLeaveDesktop: () -> Unit,
    rootAccess: RootAccess, permissionSetup: AvfPermissionSetup, useRootDisplay: Boolean,
    onToggleRootDisplay: () -> Unit, onRequestRoot: () -> Unit, onGrantAvf: () -> Unit, onRevokeAvf: () -> Unit,
    onChooseBundle: () -> Unit, onPreparedBundle: (() -> Unit)?, onStart: () -> Unit, onStop: () -> Unit,
    onResizeRootfs: (Long) -> Unit, onRestoreRootfs: () -> Unit, onResize: (DisplayMode) -> Unit,
    onMemory: (Int) -> Unit,
    onSurfaceReady: () -> Unit, onSurfaceGone: () -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("appearance", Context.MODE_PRIVATE) }
    var appearance by remember { mutableStateOf(Appearance.entries.firstOrNull { it.name == preferences.getString("theme", null) } ?: Appearance.SYSTEM) }
    val dark = when(appearance) { Appearance.DARK -> true; Appearance.LIGHT -> false; Appearance.SYSTEM -> isSystemInDarkTheme() }
    SideEffect {
        (context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val host by VenusHostRuntime.state.collectAsState()
    val avf by VenusHostRuntime.avfState.collectAsState()
    val network by VmNetworkRuntime.state.collectAsState()
    val passwordStatus by GuestPasswordRuntime.status.collectAsState()
    var surfaceReady by remember { mutableStateOf(false) }
    val backend = remember { RootAvfSurfaceBackend(context) }
    val displayStatus by backend.status.collectAsState()
    DisposableEffect(backend) { onDispose { backend.close() } }
    BackHandler(enabled = fullscreen, onBack = onLeaveDesktop)
    LaunchedEffect(state.isActive) { if(!state.isActive) onFullscreen(false) }
    LaunchedEffect(useRootDisplay, state.status, rootAccess) {
        val running = state.status is VmStatus.Running || state.status is VmStatus.GuestAgentReady
        backend.setRunning(useRootDisplay && rootAccess is RootAccess.Granted && running)
    }
    LaunchedEffect(useRootDisplay, displayStatus) {
        if(useRootDisplay && !displayStatus.startsWith("Root Direct Surface attached")) surfaceReady = false
    }
    LaunchedEffect(surfaceReady, state.status) {
        if(state.status is VmStatus.Running || state.status is VmStatus.GuestAgentReady) {
            if(surfaceReady) onSurfaceReady() else onSurfaceGone()
        }
    }
    val currentState by rememberUpdatedState(state)
    val currentFullscreen by rememberUpdatedState(fullscreen)
    val currentRootDisplay by rememberUpdatedState(useRootDisplay)
    val enterFullscreen by rememberUpdatedState(onFullscreen)
    // The same AndroidView moves between the workbench and fullscreen slots.
    // No second Surface consumer is created when the layout changes.
    val desktop = remember {
        movableContentOf<Modifier> { modifier ->
            val buffer = currentState.bundle?.display?.let { DisplaySize(it.width, it.height) } ?: DisplaySize(1280,720)
            key(currentRootDisplay) {
                VmSurface(modifier, if(currentRootDisplay) backend else DisplayBackendRegistry.active,
                    currentFullscreen, { if(currentState.isActive) enterFullscreen(true) }, { surfaceReady = it },
                    currentState.display.presented ?: buffer, buffer, !currentState.display.switching)
            }
        }
    }
    ViruaisorTheme(darkTheme = dark) {
        WorkbenchContent(state,
            VmEnvironment(rootAccess, permissionSetup, host, avf, useRootDisplay, displayStatus, surfaceReady, network, passwordStatus),
            VmUiActions(onStart = onStart, onStop = onStop, onChooseBundle = onChooseBundle,
                onPreparedBundle = onPreparedBundle, onFullscreen = onFullscreen, onRequestRoot = onRequestRoot,
                onGrantAvf = onGrantAvf, onRevokeAvf = onRevokeAvf, onToggleRootDisplay = onToggleRootDisplay,
                onHostProbe = { VenusHostRuntime.start(context) }, onAvfProbe = { VenusHostRuntime.startAvfProbe(context) },
                onResizeRootfs = onResizeRootfs, onRestoreRootfs = onRestoreRootfs, onResize = onResize,
                onMemory = onMemory,
                onPassword = GuestPasswordRuntime::request, onNetwork = { VmNetworkRuntime.setEnabled(context, it) }),
            fullscreen, appearance, {
                appearance = it
                preferences.edit().putString("theme", it.name).apply()
            }, desktop)
    }
}
