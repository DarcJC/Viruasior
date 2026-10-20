package pro.darc.viruaisor

import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import pro.darc.viruaisor.ui.*
import pro.darc.viruaisor.ui.theme.ViruaisorTheme
import pro.darc.viruaisor.vm.*
import pro.darc.viruaisor.display.DisplayBackend
import pro.darc.viruaisor.display.DisplayTarget
import android.view.Surface
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Public Android accessibility APIs avoid Espresso's API-37 looper synchronization. */
@RunWith(AndroidJUnit4::class)
class WorkbenchUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val bundle = VmBundle(File("vm"), File("kernel"), null, File("rootfs"), null, null,
        emptyList(), "Manjaro Plasma", 4096, true, 4050, true, false, false, null, emptyList())
    private val ready = VmUiState(support = AvfSupport.Available(3), bundle = bundle,
        status = VmStatus.GuestAgentReady, storage = VmStorageState(rootBytes = 8 * RootfsCapacity.GIB))

    @Test fun diagnosticsAreSeparateFromDailyControls() {
        show().use {
            assertNull(findTag("probe_host"))
            click("nav_settings")
            scrollTo("open_diagnostics")
            click("open_diagnostics")
            await { nodes().any { it.text?.toString() == "诊断中心" } }
            assertNull(findTag("panel_control"))
            click("back_settings")
            click("nav_workbench")
            assertTrue(waitTag("panel_control").isVisibleToUser)
            assertNull(findTag("probe_host"))
        }
    }

    @Test fun stopRequiresExplicitConfirmationAndCancelDoesNothing() {
        val stops = AtomicInteger()
        show(actions = VmUiActions(onStop = { stops.incrementAndGet() })).use {
            scrollTo("action_stop")
            click("action_stop")
            assertEquals(0, stops.get())
            click("cancel_action")
            assertEquals(0, stops.get())
            click("action_stop")
            click("confirm_action")
            assertEquals(1, stops.get())
        }
    }

    @Test fun narrowLayoutExposesStorageValidationAndDisplayPanel() {
        show(narrow = true).use {
            assertNotNull(waitTag("bottom_navigation"))
            click("panel_storage")
            scrollTo("resize_disk")
            click("resize_disk")
            val input = waitTag("capacity_input")
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "0")
            }
            assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
            instrumentation.waitForIdleSync()
            click("confirm_action")
            await { nodes().any { it.text?.toString() == "请输入 1–1024 之间的整数" } }
            click("cancel_action")
            scrollTo("panel_display", backwards = true)
            click("panel_display")
            await { nodes().any { it.text?.toString() == "显示模式" } }
        }
    }

    @Test fun probeDisablesLaunchAndReplacementInWorkbench() {
        show(state = ready.copy(status = VmStatus.Stopped),
            environment = VmEnvironment(host = pro.darc.viruaisor.gpu.VenusHostState(running = true))).use {
            assertFalse(waitTag("action_start").isEnabled)
            scrollTo("choose_bundle")
            assertFalse(waitTag("choose_bundle").isEnabled)
        }
    }

    @Test fun memoryDialogValidatesAndSubmitsPresetInCompactLayout() {
        val selected = AtomicInteger()
        show(state = ready.copy(status = VmStatus.Stopped), narrow = true,
            actions = VmUiActions(onMemory = { selected.set(it) })).use {
            scrollTo("adjust_memory")
            click("adjust_memory")
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "0")
            }
            assertTrue(waitTag("memory_input").performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
            instrumentation.waitForIdleSync()
            click("confirm_memory")
            await { nodes().any { it.text?.toString() == "请输入 256–32768 MiB 之间的整数" } }
            assertEquals(0, selected.get())
            click("memory_preset_2048")
            click("confirm_memory")
            await { selected.get() == 2048 }
            assertNull(findTag("memory_input"))
        }
    }

    @Test fun activeVmCannotAdjustMemory() {
        show().use {
            scrollTo("adjust_memory")
            assertFalse(waitTag("adjust_memory").isEnabled)
        }
    }

    @Test fun fullscreenPreservesNativeSurfaceAttachment() {
        val fullscreen = mutableStateOf(false)
        val attachments = AtomicInteger()
        val backend = object : DisplayBackend {
            override val name = "UI attachment fixture"
            override fun attach(target: DisplayTarget, onAttached: () -> Unit) { attachments.incrementAndGet(); onAttached() }
            override fun detach(surface: Surface) = Unit
        }
        show(actions = VmUiActions(onFullscreen = { fullscreen.value = it }), fullscreen = fullscreen,
            desktop = { modifier ->
                VmSurface(modifier, backend, fullscreen.value, { fullscreen.value = true }, {},
                    DisplaySize(1280,720), DisplaySize(1280,720), true)
            }).use {
            await { attachments.get() > 0 }
            val original = attachments.get()
            click("toggle_fullscreen")
            await { fullscreen.value }
            SystemClock.sleep(400)
            assertEquals("Entering fullscreen must keep the Surface consumer", original, attachments.get())
            click("toggle_fullscreen")
            await { !fullscreen.value }
            SystemClock.sleep(400)
            assertEquals("Leaving fullscreen must keep the Surface consumer", original, attachments.get())
        }
    }

    @Test fun fullscreenControlsAutoHideAndCanBeReopened() {
        val fullscreen = mutableStateOf(false)
        show(actions = VmUiActions(onFullscreen = { fullscreen.value = it }), fullscreen = fullscreen).use {
            click("toggle_fullscreen")
            await { fullscreen.value }
            waitTag("toggle_fullscreen")
            SystemClock.sleep(3200)
            assertNull("Fullscreen exit control must auto-hide", findTag("toggle_fullscreen"))
            click("fullscreen_handle")
            waitTag("toggle_fullscreen")
            click("toggle_fullscreen")
            assertFalse(fullscreen.value)
        }
    }

    @Test fun settingsNavigationPreservesNativeSurfaceAttachment() {
        val attachments = AtomicInteger()
        val backend = object : DisplayBackend {
            override val name = "UI navigation fixture"
            override fun attach(target: DisplayTarget, onAttached: () -> Unit) { attachments.incrementAndGet(); onAttached() }
            override fun detach(surface: Surface) = Unit
        }
        show(desktop = { modifier ->
            VmSurface(modifier, backend, false, {}, {}, DisplaySize(1280,720), DisplaySize(1280,720), true)
        }).use {
            await { attachments.get() > 0 }
            val original = attachments.get()
            click("nav_settings")
            assertNull(findTag("panel_control"))
            click("nav_workbench")
            await { findTag("panel_control")?.isVisibleToUser == true }
            SystemClock.sleep(400)
            assertEquals("Settings navigation must keep the Surface consumer", original, attachments.get())
        }
    }

    private fun show(state: VmUiState = ready, environment: VmEnvironment = VmEnvironment(),
        actions: VmUiActions = VmUiActions(), narrow: Boolean = false, fullscreen: State<Boolean>? = null,
        desktop: @Composable (Modifier) -> Unit = {}): AutoCloseable {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        // This tablet rejects app-UID background starts even during instrumentation.
        // Launch only the existing debug host through shell; no device policy is changed.
        val monitor = instrumentation.addMonitor(ComponentActivity::class.java.name, null, false)
        val descriptor = instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${BuildConfig.APPLICATION_ID}/androidx.activity.ComponentActivity")
        val output = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        val activity = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as? ComponentActivity
        instrumentation.removeMonitor(monitor)
        checkNotNull(activity) { "Test host did not start: $output" }
        instrumentation.runOnMainSync {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.setContent {
                ViruaisorTheme {
                    Box(if(narrow) Modifier.width(390.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                        WorkbenchContent(state, environment, actions, fullscreen = fullscreen?.value ?: false, desktop = desktop)
                    }
                }
            }
        }
        waitTag("panel_control")
        return AutoCloseable {
            // Accessibility actions do not reset the tablet's user-activity timer.
            // Touch the inert title before closing, so a completed test does not
            // immediately lock the device when KEEP_SCREEN_ON is released.
            val title = nodes().firstOrNull { it.packageName == BuildConfig.APPLICATION_ID && it.text?.startsWith("VIRUAISOR /") == true }
            title?.let {
                val bounds = Rect().also(it::getBoundsInScreen)
                shellTap(bounds)
            }
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        instrumentation.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            result += node
            repeat(node.childCount) { index -> node.getChild(index)?.let(::visit) }
        }
        val roots = instrumentation.uiAutomation.windows.mapNotNull { it.root }
        if(roots.isEmpty()) instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        else roots.forEach(::visit)
        return result
    }

    private fun findTag(tag: String) = nodes().firstOrNull { it.viewIdResourceName == tag }
    private fun waitTag(tag: String): AccessibilityNodeInfo {
        await { findTag(tag) != null }
        return checkNotNull(findTag(tag)) { "Missing UI node: $tag" }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while(SystemClock.uptimeMillis() < deadline) {
            if(condition()) return
            SystemClock.sleep(60)
        }
        throw AssertionError("UI condition timed out; visible nodes: " + nodes().mapNotNull { it.viewIdResourceName ?: it.text?.toString() })
    }
    private fun click(tag: String) {
        val node = waitTag(tag)
        assertTrue("Enabled node $tag", node.isEnabled)
        assertTrue("Accessibility action $tag", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(100)
    }

    private fun shellTap(bounds: Rect) {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(
            "input tap ${bounds.centerX()} ${bounds.centerY()}")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
    private fun scrollTo(tag: String, backwards: Boolean = false) {
        repeat(15) {
            val node = findTag(tag)
            val bounds = Rect()
            node?.getBoundsInScreen(bounds)
            if(node?.isVisibleToUser == true && bounds.height() > 10) return
            val scroll = nodes().lastOrNull { it.isScrollable }
            assertNotNull("Scrollable container for $tag", scroll)
            scroll!!.performAction(if(backwards) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100)
        }
        throw AssertionError("Could not scroll to $tag")
    }
}
