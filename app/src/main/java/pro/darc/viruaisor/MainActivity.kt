package pro.darc.viruaisor

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Base64
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import pro.darc.viruaisor.display.DesktopKeyboard
import pro.darc.viruaisor.display.DesktopPointerPolicy
import pro.darc.viruaisor.display.InputBackendRegistry
import pro.darc.viruaisor.root.AvfPermissionRuntime
import pro.darc.viruaisor.root.RootRuntime
import pro.darc.viruaisor.ui.VmScreen
import pro.darc.viruaisor.vm.*

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
        pro.darc.viruaisor.network.VmNetworkRuntime.initialize(this)
        RootRuntime.request(onlyIfIdle = true)
        handleDebugIntent(intent)
        setContent {
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
                onResizeRootfs = { bytes -> ContextCompat.startForegroundService(this, VmService.resizeRootfsIntent(this,bytes)); Unit },
                onMemory = { memoryMiB -> ContextCompat.startForegroundService(this, VmService.memoryIntent(this, memoryMiB)); Unit },
                onRestoreRootfs = { ContextCompat.startForegroundService(this, VmService.restoreRootfsIntent(this)); Unit },
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

    private fun changeFullscreen(value: Boolean) {
        fullscreen = value
        window.decorView.pointerIcon=if(value) android.view.PointerIcon.getSystemIcon(this,
            android.view.PointerIcon.TYPE_NULL) else null
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
        // Guest scanout timing and Android consumer cadence are independent.
        // Keep the visible consumer responsive even for a native 60 Hz guest.
        val target=120f
        val selected=DesktopPointerPolicy.preferredMode(display.supportedModes.map {
            DesktopPointerPolicy.ScreenMode(it.modeId,it.physicalWidth,it.physicalHeight,it.refreshRate)
        },current.physicalWidth,current.physicalHeight,target) ?: return
        attributes.preferredDisplayModeId=selected.id
        attributes.preferredRefreshRate=selected.hz
        window.attributes=attributes
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // This activity opts out of predictive Back in the manifest. Some tablet
        // keyboards emit Android BACK with physical ESC scan code 1; predictive
        // Back consumes it before this method and loses that distinction.
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
            (pro.darc.viruaisor.AppIdentity.ACTION_PREFIX + "DEBUG_VPN_MODE") -> {
                if (!VmRuntime.state.value.isActive) pro.darc.viruaisor.network.VmNetworkRuntime.setEnabled(this, intent.getBooleanExtra("enabled", true))
            }
            (pro.darc.viruaisor.AppIdentity.ACTION_PREFIX + "DEBUG_ROOTFS_RESIZE") -> ContextCompat.startForegroundService(
                this,VmService.resizeRootfsIntent(this,intent.getLongExtra("bytes",0)))
            (pro.darc.viruaisor.AppIdentity.ACTION_PREFIX + "DEBUG_ROOTFS_RESTORE") -> ContextCompat.startForegroundService(
                this,VmService.restoreRootfsIntent(this))
            (pro.darc.viruaisor.AppIdentity.ACTION_PREFIX + "DEBUG_RESIZE") -> {
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
        const val ACTION_DEBUG_START = (pro.darc.viruaisor.AppIdentity.ACTION_PREFIX + "DEBUG_START")
        const val ACTION_DEBUG_IMPORT_PREPARED = (pro.darc.viruaisor.AppIdentity.ACTION_PREFIX + "DEBUG_IMPORT_PREPARED_MANJARO")
        const val ACTION_DEBUG_STOP = (pro.darc.viruaisor.AppIdentity.ACTION_PREFIX + "DEBUG_STOP")
        const val ACTION_DEBUG_CONSOLE = (pro.darc.viruaisor.AppIdentity.ACTION_PREFIX + "DEBUG_CONSOLE")
        const val ACTION_DEBUG_GRANT_AVF = (pro.darc.viruaisor.AppIdentity.ACTION_PREFIX + "DEBUG_GRANT_AVF")
        const val ACTION_DEBUG_ROOT_DISPLAY = (pro.darc.viruaisor.AppIdentity.ACTION_PREFIX + "DEBUG_ROOT_DISPLAY")
        const val EXTRA_TEXT_BASE64 = "textBase64"
        val BUNDLE_MIME_TYPES = arrayOf(
            "application/zip",
            "application/octet-stream",
            "application/x-zip-compressed",
        )
    }
}
