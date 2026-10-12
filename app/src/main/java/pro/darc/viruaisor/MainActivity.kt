package pro.darc.viruaisor

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Base64
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import pro.darc.viruaisor.display.DisplayBackendRegistry
import pro.darc.viruaisor.display.InputBackendRegistry
import pro.darc.viruaisor.ui.theme.ViruaisorTheme
import pro.darc.viruaisor.vm.AvfSupport
import pro.darc.viruaisor.vm.VmRuntime
import pro.darc.viruaisor.vm.VmService
import pro.darc.viruaisor.vm.VmStatus
import pro.darc.viruaisor.vm.VmUiState

class MainActivity : ComponentActivity() {
    private val bundlePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            ContextCompat.startForegroundService(this, VmService.importIntent(this, uri))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch { VmRuntime.initialize(this@MainActivity) }
        handleDebugIntent(intent)
        setContent {
            ViruaisorTheme {
                val state by VmRuntime.state.collectAsState()
                VmScreen(
                    state = state,
                    onChooseBundle = { bundlePicker.launch(BUNDLE_MIME_TYPES) },
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugIntent(intent)
    }

    private fun handleDebugIntent(intent: Intent) {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) return
        when (intent.action) {
            ACTION_DEBUG_START -> ContextCompat.startForegroundService(this, VmService.startIntent(this))
            ACTION_DEBUG_STOP -> ContextCompat.startForegroundService(this, VmService.stopIntent(this))
            ACTION_DEBUG_CONSOLE -> {
                val encoded = intent.getStringExtra(EXTRA_TEXT_BASE64).orEmpty()
                val text = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                ContextCompat.startForegroundService(this, VmService.debugConsoleIntent(this, text))
            }
        }
    }

    private companion object {
        const val ACTION_DEBUG_START = "pro.darc.viruaisor.action.DEBUG_START"
        const val ACTION_DEBUG_STOP = "pro.darc.viruaisor.action.DEBUG_STOP"
        const val ACTION_DEBUG_CONSOLE = "pro.darc.viruaisor.action.DEBUG_CONSOLE"
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
    onChooseBundle: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSurfaceReady: () -> Unit,
    onSurfaceGone: () -> Unit,
) {
    var surfaceReady by remember { mutableStateOf(false) }
    LaunchedEffect(surfaceReady, state.status) {
        val running = state.status is VmStatus.Running || state.status is VmStatus.GuestAgentReady
        if (surfaceReady && running) onSurfaceReady()
        if (!surfaceReady && running) onSurfaceGone()
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Viruaisor", style = MaterialTheme.typography.headlineMedium)
            AvfCard(state.support)
            VmSurface(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .aspectRatio(DISPLAY_ASPECT_RATIO)
                    .fillMaxWidth(),
                onReadyChanged = { surfaceReady = it },
            )
            StatusCard(state)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onChooseBundle, enabled = state.canImport) {
                    Text(if (state.bundle == null) "Choose VM bundle" else "Replace VM bundle")
                }
                Button(onClick = onStart, enabled = state.canStart) { Text("Start") }
                OutlinedButton(onClick = onStop, enabled = state.canStop) { Text("Stop") }
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
    onReadyChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val backend = DisplayBackendRegistry.active
    AndroidView(
        modifier = modifier.background(Color.Black),
        factory = {
            SurfaceView(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                holder.setFixedSize(DISPLAY_WIDTH, DISPLAY_HEIGHT)
                isFocusable = true
                isFocusableInTouchMode = true
                setOnTouchListener { view, event ->
                    if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) requestFocus()
                    InputBackendRegistry.setTouchScale(DISPLAY_WIDTH.toFloat() / view.width)
                    InputBackendRegistry.sendTouch(event)
                }
                setOnKeyListener { _, keyCode, event ->
                    val scanCode = (event.scanCode.takeIf { it != 0 } ?: keyCode).toShort()
                    when (event.action) {
                        KeyEvent.ACTION_DOWN -> InputBackendRegistry.sendKey(scanCode, true)
                        KeyEvent.ACTION_UP -> InputBackendRegistry.sendKey(scanCode, false)
                        else -> false
                    }
                }
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        backend.attach(holder.surface) { post { onReadyChanged(true) } }
                    }
                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) = Unit
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        onReadyChanged(false)
                        backend.detach(holder.surface)
                    }
                })
            }
        },
    )
}

private const val DISPLAY_WIDTH = 1280
private const val DISPLAY_HEIGHT = 720
private const val DISPLAY_ASPECT_RATIO = 16f / 9f

private fun statusLabel(status: VmStatus): String = when (status) {
    VmStatus.Stopped -> "VM stopped"
    VmStatus.Importing -> "Importing VM bundle"
    VmStatus.Starting -> "VM starting"
    VmStatus.Running -> "VM running"
    VmStatus.GuestAgentReady -> "Guest agent ready"
    VmStatus.Stopping -> "VM stopping"
    is VmStatus.Crashed -> "VM stopped / crashed (${status.reason})"
}
