package pro.darc.viruaisor

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Base64
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import pro.darc.viruaisor.vm.DisplayMode
import pro.darc.viruaisor.display.DesktopPointerPolicy
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import pro.darc.viruaisor.display.DisplayBackend
import pro.darc.viruaisor.display.DisplayBackendRegistry
import pro.darc.viruaisor.display.DisplayTarget
import pro.darc.viruaisor.display.RootAvfSurfaceBackend
import pro.darc.viruaisor.display.InputBackendRegistry
import pro.darc.viruaisor.display.DesktopKeyboard
import pro.darc.viruaisor.display.DesktopSurfaceView
import pro.darc.viruaisor.root.AvfPermissionRuntime
import pro.darc.viruaisor.root.AvfPermissionSetup
import pro.darc.viruaisor.root.RootAccess
import pro.darc.viruaisor.root.RootRuntime
import pro.darc.viruaisor.ui.theme.ViruaisorTheme
import pro.darc.viruaisor.vm.AvfSupport
import pro.darc.viruaisor.vm.VmRuntime
import pro.darc.viruaisor.vm.VmService
import pro.darc.viruaisor.vm.VmStatus
import pro.darc.viruaisor.vm.VmUiState
import pro.darc.viruaisor.vm.VmGpuProfile
import pro.darc.viruaisor.vm.DisplaySize
import pro.darc.viruaisor.gpu.VenusHostRuntime

class MainActivity : ComponentActivity() {
    private var useRootDisplay by mutableStateOf(false)
    private var fullscreen by mutableStateOf(false)
    private var exitQPending = false
    private val keyboard = DesktopKeyboard(InputBackendRegistry::sendKey) {
        exitQPending = true
        changeFullscreen(false)
    }
    private val bundlePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            ContextCompat.startForegroundService(this, VmService.importIntent(this, uri))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        useRootDisplay = savedInstanceState?.getBoolean("rootDisplay", false) ?: false
        enableEdgeToEdge()
        lifecycleScope.launch { VmRuntime.initialize(this@MainActivity) }
        RootRuntime.request(onlyIfIdle = true)
        handleDebugIntent(intent)
        setContent {
            ViruaisorTheme {
                val state by VmRuntime.state.collectAsState()
                LaunchedEffect(fullscreen,state.display.actual) { applyFullscreenRefresh(fullscreen) }
                DisposableEffect(state.isActive) {
                    // Window-local only: hidden/closed windows cannot keep the screen on.
                    window.decorView.keepScreenOn = state.isActive
                    onDispose { window.decorView.keepScreenOn = false }
                }
                LaunchedEffect(state.bundle?.gpuProfile) {
                    if (state.bundle?.gpuProfile == VmGpuProfile.VENUS) useRootDisplay=true
                }
                val rootAccess by RootRuntime.state.collectAsState()
                val permissionSetup by AvfPermissionRuntime.state.collectAsState()
                VmScreen(
                    state = state,
                    fullscreen = fullscreen,
                    onFullscreen = ::changeFullscreen,
                    onLeaveDesktop = { changeFullscreen(false); moveTaskToBack(true) },
                    rootAccess = rootAccess,
                    permissionSetup = permissionSetup,
                    useRootDisplay = useRootDisplay,
                    onToggleRootDisplay = { useRootDisplay = !useRootDisplay },
                    onRequestRoot = { RootRuntime.request() },
                    onGrantAvf = { AvfPermissionRuntime.grantMissing(this) },
                    onRevokeAvf = { AvfPermissionRuntime.revokeAdded(this) },
                    onChooseBundle = { bundlePicker.launch(BUNDLE_MIME_TYPES) },
                    onPreparedBundle = if (getExternalFilesDir(null)?.resolve(VmService.PREPARED_BUNDLE_NAME)?.isFile == true) {
                        { ContextCompat.startForegroundService(this,VmService.importPreparedIntent(this)); Unit }
                    } else null,
                    onStart = {
                        ContextCompat.startForegroundService(
                            this,
                            VmService.startIntent(this),
                        )
                    },
                    onStop = {
                        ContextCompat.startForegroundService(
                            this,
                            VmService.stopIntent(this),
                        )
                    },
                    onResize = { size -> ContextCompat.startForegroundService(this,VmService.resizeIntent(this,size)); Unit },
                    onSurfaceReady = {
                        ContextCompat.startForegroundService(
                            this,
                            VmService.surfaceReadyIntent(this),
                        )
                    },
                    onSurfaceGone = {
                        ContextCompat.startForegroundService(
                            this,
                            VmService.surfaceGoneIntent(this),
                        )
                    },
                )
            }
        }
    }

    private fun changeFullscreen(value: Boolean) {
        fullscreen = value
        applyFullscreenRefresh(value)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (value) controller.hide(WindowInsetsCompat.Type.systemBars()) else {
            keyboard.release()
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private var savedDisplayPreference: Pair<Int,Float>? = null
    private fun applyFullscreenRefresh(active: Boolean) {
        val attributes=window.attributes
        if (!active) {
            savedDisplayPreference?.let { (id,rate) ->
                attributes.preferredDisplayModeId=id
                attributes.preferredRefreshRate=rate
                window.attributes=attributes
            }
            savedDisplayPreference=null
            return
        }
        val display=window.decorView.display ?: return
        if (savedDisplayPreference == null) savedDisplayPreference=attributes.preferredDisplayModeId to attributes.preferredRefreshRate
        val current=display.mode
        val target=VmRuntime.state.value.display.actual?.refreshHz ?: 120f
        val selected=DesktopPointerPolicy.preferredMode(display.supportedModes.map {
            DesktopPointerPolicy.ScreenMode(it.modeId,it.physicalWidth,it.physicalHeight,it.refreshRate)
        },current.physicalWidth,current.physicalHeight,target) ?: return
        attributes.preferredDisplayModeId=selected.id
        attributes.preferredRefreshRate=selected.hz
        window.attributes=attributes
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (fullscreen && VmRuntime.state.value.display.switching) {
            keyboard.release()
            return true
        }
        if (exitQPending && event.keyCode == KeyEvent.KEYCODE_Q) {
            if (event.action == KeyEvent.ACTION_UP) {
                exitQPending = false
                keyboard.key(event.keyCode, event.scanCode, false)
            }
            return true
        }
        if (fullscreen && event.action in setOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP) &&
            keyboard.key(event.keyCode, event.scanCode, event.action == KeyEvent.ACTION_DOWN,
                event.isCtrlPressed, event.isShiftPressed, event.isAltPressed)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) keyboard.release()
    }

    override fun onPause() {
        changeFullscreen(false)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("rootDisplay", useRootDisplay)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugIntent(intent)
    }

    private fun handleDebugIntent(intent: Intent) {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) return
        when (intent.action) {
            ACTION_DEBUG_IMPORT_PREPARED -> ContextCompat.startForegroundService(this,VmService.importPreparedIntent(this))
            ACTION_DEBUG_START -> ContextCompat.startForegroundService(this, VmService.startIntent(this))
            ACTION_DEBUG_STOP -> ContextCompat.startForegroundService(this, VmService.stopIntent(this))
            ACTION_DEBUG_GRANT_AVF -> AvfPermissionRuntime.grantMissing(this)
            ACTION_DEBUG_ROOT_DISPLAY -> useRootDisplay = true
            "pro.darc.viruaisor.action.DEBUG_RESIZE" -> {
                val size = runCatching { DisplaySize(intent.getIntExtra("width",0),intent.getIntExtra("height",0)) }.getOrNull()
                if (size != null) {
                    val rate=intent.getIntExtra("millihz",0)
                    val request=if(rate == 0) VmService.resizeIntent(this,size) else
                        runCatching { VmService.resizeIntent(this,DisplayMode(size,rate)) }.getOrNull()
                    if(request != null) ContextCompat.startForegroundService(this,request)
                }
            }
            ACTION_DEBUG_CONSOLE -> {
                val encoded = intent.getStringExtra(EXTRA_TEXT_BASE64).orEmpty()
                val text = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                ContextCompat.startForegroundService(this, VmService.debugConsoleIntent(this, text))
            }
        }
    }

    private companion object {
        const val ACTION_DEBUG_START = "pro.darc.viruaisor.action.DEBUG_START"
        const val ACTION_DEBUG_IMPORT_PREPARED = "pro.darc.viruaisor.action.DEBUG_IMPORT_PREPARED_MANJARO"
        const val ACTION_DEBUG_STOP = "pro.darc.viruaisor.action.DEBUG_STOP"
        const val ACTION_DEBUG_CONSOLE = "pro.darc.viruaisor.action.DEBUG_CONSOLE"
        const val ACTION_DEBUG_GRANT_AVF = "pro.darc.viruaisor.action.DEBUG_GRANT_AVF"
        const val ACTION_DEBUG_ROOT_DISPLAY = "pro.darc.viruaisor.action.DEBUG_ROOT_DISPLAY"
        const val EXTRA_TEXT_BASE64 = "textBase64"
        val BUNDLE_MIME_TYPES = arrayOf(
            "application/zip",
            "application/octet-stream",
            "application/x-zip-compressed",
        )
    }
}

@Composable
private fun VmScreen(
    state: VmUiState,
    fullscreen: Boolean,
    onFullscreen: (Boolean) -> Unit,
    onLeaveDesktop: () -> Unit,
    rootAccess: RootAccess,
    permissionSetup: AvfPermissionSetup,
    useRootDisplay: Boolean,
    onToggleRootDisplay: () -> Unit,
    onRequestRoot: () -> Unit,
    onGrantAvf: () -> Unit,
    onRevokeAvf: () -> Unit,
    onChooseBundle: () -> Unit,
    onPreparedBundle: (() -> Unit)?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onResize: (DisplayMode) -> Unit,
    onSurfaceReady: () -> Unit,
    onSurfaceGone: () -> Unit,
) {
    BackHandler(enabled = fullscreen, onBack = onLeaveDesktop)
    LaunchedEffect(state.isActive) { if (!state.isActive) onFullscreen(false) }
    var surfaceReady by remember { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(view, state.isActive) {
        val previous = view.keepScreenOn
        view.keepScreenOn = state.isActive
        onDispose { view.keepScreenOn = previous }
    }
    val context = LocalContext.current
    val bufferSize = state.bundle?.display?.let { DisplaySize(it.width,it.height) } ?: DisplaySize(1280,720)
    val guestSize = state.display.presented ?: bufferSize
    val aspect = guestSize.width.toFloat()/guestSize.height
    val venusHost by VenusHostRuntime.state.collectAsState()
    val venusAvf by VenusHostRuntime.avfState.collectAsState()
    val rootBackend = remember { RootAvfSurfaceBackend(context) }
    val rootDisplayStatus by rootBackend.status.collectAsState()
    DisposableEffect(rootBackend) { onDispose { rootBackend.close() } }
    LaunchedEffect(useRootDisplay, state.status, rootAccess) {
        val running = state.status is VmStatus.Running || state.status is VmStatus.GuestAgentReady
        rootBackend.setRunning(useRootDisplay && rootAccess is RootAccess.Granted && running)
    }
    LaunchedEffect(useRootDisplay, rootDisplayStatus) {
        if (useRootDisplay && !rootDisplayStatus.startsWith("Root Direct Surface attached")) surfaceReady = false
    }
    LaunchedEffect(surfaceReady, state.status) {
        val running = state.status is VmStatus.Running || state.status is VmStatus.GuestAgentReady
        if (surfaceReady && running) onSurfaceReady()
        if (!surfaceReady && running) onSurfaceGone()
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxSize()
                .padding(innerPadding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!fullscreen) {
                Text("Viruaisor", style = MaterialTheme.typography.headlineMedium)
                Text("点击桌面进入全屏；Ctrl+Shift+Alt+Q 退出全屏，Android 返回/Home 手势切出。", style = MaterialTheme.typography.bodySmall)
            }
            key(useRootDisplay) {
                VmSurface(
                    modifier = if (fullscreen) Modifier.fillMaxWidth().weight(1f) else Modifier
                        .heightIn(max = 360.dp)
                        .aspectRatio(aspect)
                        .fillMaxWidth(),
                    backend = if (useRootDisplay) rootBackend else DisplayBackendRegistry.active,
                    fullscreen = fullscreen,
                    onEnterFullscreen = { if (state.isActive) onFullscreen(true) },
                    onReadyChanged = { surfaceReady = it },
                    guestSize = guestSize,
                    bufferSize = bufferSize,
                    inputEnabled = !state.display.switching,
                )
            }
            if (!fullscreen) {
                StatusCard(state)
                if (state.display.modes.isNotEmpty()) ResolutionControl(state,onResize)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onChooseBundle, enabled = state.canImport && !venusHost.running && !venusAvf.running) {
                        Text(if (state.bundle == null) "Choose VM bundle" else "Replace VM bundle")
                    }
                    Button(onClick = onStart, enabled = state.canStart && !venusHost.running && !venusAvf.running) { Text("Start") }
                    OutlinedButton(onClick = onStop, enabled = state.canStop) { Text("Stop") }
                }
                if (onPreparedBundle != null) OutlinedButton(onClick=onPreparedBundle,enabled=state.canImport) {
                    Text("导入已准备的 Manjaro Plasma 镜像")
                }
                RootCard(rootAccess, onRequestRoot)
                AvfPermissionCard(permissionSetup, rootAccess is RootAccess.Granted, state.isActive, onGrantAvf, onRevokeAvf)
                AvfCard(state.support)
                OutlinedButton(onClick = onToggleRootDisplay, enabled = rootAccess is RootAccess.Granted) {
                    Text(if (useRootDisplay) "关闭 Root 显示桥接" else "实验 Root Direct Surface（仅显示桥接）")
                }
                if (useRootDisplay) {
                    Text("实验仅限单 VM；内部显示接口没有 VM 所有权参数。", style = MaterialTheme.typography.bodySmall)
                    Text(rootDisplayStatus, style = MaterialTheme.typography.bodySmall)
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Venus 宿主验证", style = MaterialTheme.typography.titleMedium)
                        Text(venusHost.message, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { VenusHostRuntime.start(context) }, enabled = !state.isActive && !venusHost.running && !venusAvf.running) {
                            Text("验证 Venus GPU 计算")
                        }
                        Text(venusAvf.message, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { VenusHostRuntime.startAvfProbe(context) }, enabled = !state.isActive && venusHost.verified && !venusHost.running && !venusAvf.running) {
                            Text("检查 AVF Venus 后端")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResolutionControl(state: VmUiState, onResize: (DisplayMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            OutlinedButton(onClick = { expanded=true }, enabled = !state.display.switching) {
                Text(if (state.display.requested != null) "正在切换到 ${state.display.requested}…"
                    else "分辨率：${state.display.actual ?: state.display.presented}")
            }
            DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                state.display.modes.forEach { size ->
                    DropdownMenuItem(text={Text(size.toString())},onClick={expanded=false; onResize(size)})
                }
            }
        }
        state.display.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun RootCard(access: RootAccess, onRequestRoot: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Root / KernelSU", style = MaterialTheme.typography.titleMedium)
            Text(
                when (access) {
                    RootAccess.Idle -> "尚未申请 Root"
                    RootAccess.Requesting -> "正在请求 Root，请在 KernelSU 中允许 Viruaisor…"
                    is RootAccess.Granted -> "已获得 Root（UID 0） · ${access.implementation}"
                    is RootAccess.Denied -> access.reason
                    is RootAccess.Unavailable -> access.reason
                },
                color = if (access is RootAccess.Denied || access is RootAccess.Unavailable) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (access is RootAccess.Granted) {
                Text("SELinux: ${access.selinuxMode} · su: ${access.suPath}", style = MaterialTheme.typography.bodySmall)
                Text(access.identity, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "此步骤仅验证 Root，不会修改 SELinux 或授予 AVF 权限。",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onRequestRoot, enabled = access !is RootAccess.Requesting) {
                Text(if (access is RootAccess.Idle) "申请 Root 权限" else "重新检测 Root")
            }
        }
    }
}

@Composable
private fun AvfPermissionCard(
    setup: AvfPermissionSetup,
    rootGranted: Boolean,
    vmActive: Boolean,
    onGrant: () -> Unit,
    onRevoke: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("AVF 最小权限配置", style = MaterialTheme.typography.titleMedium)
            Text("仅给本应用补齐 MANAGE_VIRTUAL_MACHINE 和 USE_CUSTOM_VIRTUAL_MACHINE；保留已有授权，不修改系统策略。", style = MaterialTheme.typography.bodySmall)
            when (setup) {
                AvfPermissionSetup.Idle -> Unit
                AvfPermissionSetup.Working -> Text("正在配置并从普通应用进程验证…")
                is AvfPermissionSetup.Failed -> Text(setup.reason, color = MaterialTheme.colorScheme.error)
                is AvfPermissionSetup.Complete -> setup.changes.forEach { change ->
                    Text("${change.permission.name}: ${if (change.isGranted) "已授权" else "未授权"}" +
                        (if (change.wasGranted) "（保留已有授权）" else "") +
                        (change.error?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(onClick = onGrant, enabled = rootGranted && setup !is AvfPermissionSetup.Working && !vmActive) {
                Text("补齐本应用 AVF 权限")
            }
            OutlinedButton(onClick = onRevoke, enabled = rootGranted && setup !is AvfPermissionSetup.Working && !vmActive) {
                Text("撤销配置新增权限（可能重启应用）")
            }
        }
    }
}

@Composable
private fun AvfCard(support: AvfSupport) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("AVF support", style = MaterialTheme.typography.titleMedium)
            Text(
                when (support) {
                    AvfSupport.Checking -> "Checking…"
                    is AvfSupport.Available -> "Available (capabilities: 0x${support.capabilities.toString(16)})"
                    is AvfSupport.Unavailable -> support.reason
                },
                color = if (support is AvfSupport.Unavailable) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun StatusCard(state: VmUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("VM status", style = MaterialTheme.typography.titleMedium)
            Text(statusLabel(state.status), style = MaterialTheme.typography.bodyLarge)
            Text(
                state.bundle?.let {
                    "Bundle: filesDir/vms/default · ${it.memoryMiB} MiB · " +
                    "GPU ${it.gpuProfile.avfBackend} · vsock ${it.vsockPort}"
                } ?: "No VM bundle imported",
                style = MaterialTheme.typography.bodySmall,
            )
            state.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun VmSurface(
    modifier: Modifier = Modifier,
    backend: DisplayBackend,
    fullscreen: Boolean,
    onEnterFullscreen: () -> Unit,
    onReadyChanged: (Boolean) -> Unit,
    guestSize: DisplaySize,
    bufferSize: DisplaySize,
    inputEnabled: Boolean,
) {
    val context = LocalContext.current
    BoxWithConstraints(modifier=modifier.background(Color.Black)) {
    val aspect = guestSize.width.toFloat()/guestSize.height
    val fitted=if (maxWidth/maxHeight > aspect) Modifier.fillMaxHeight().aspectRatio(aspect)
        else Modifier.fillMaxWidth().aspectRatio(aspect)
    AndroidView(
        modifier = fitted.align(Alignment.Center),
        factory = {
            DesktopSurfaceView(context).apply {
                // The Android parent and virtio touch ABS ranges keep their boot dimensions.
                // New guest scanouts scale into this space; input uses the confirmed guest mode.
                holder.setFixedSize(bufferSize.width, bufferSize.height)
                inputWidth=bufferSize.width
                inputHeight=bufferSize.height
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit
                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) {
                        // The callback supplies fixed-buffer dimensions, not on-screen view size.
                        onReadyChanged(false)
                        backend.attach(DisplayTarget(holder.surface, surfaceControl, width, height)) {
                            post { onReadyChanged(true) }
                        }
                    }
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        onReadyChanged(false)
                        backend.detach(holder.surface)
                    }
                })
            }
        },
        update = { view ->
            if (view.guestWidth != guestSize.width || view.guestHeight != guestSize.height || !inputEnabled) view.releaseInput()
            view.guestWidth=guestSize.width
            view.guestHeight=guestSize.height
            view.inputEnabled=inputEnabled
            view.enterFullscreen = onEnterFullscreen
            if (view.fullscreen != fullscreen) view.fullscreen = fullscreen
        },
        onRelease = { view ->
            view.releaseInput()
            onReadyChanged(false)
            backend.detach(view.holder.surface)
        },
    )
    }
}


private fun statusLabel(status: VmStatus): String = when (status) {
    VmStatus.Stopped -> "VM stopped"
    VmStatus.Importing -> "Importing VM bundle"
    VmStatus.Starting -> "VM starting"
    VmStatus.Running -> "VM running"
    VmStatus.GuestAgentReady -> "Guest agent ready"
    VmStatus.Stopping -> "VM stopping"
    is VmStatus.Crashed -> "VM stopped / crashed (${status.reason})"
}
