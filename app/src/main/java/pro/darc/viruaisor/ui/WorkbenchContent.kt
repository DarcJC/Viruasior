package pro.darc.viruaisor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import pro.darc.viruaisor.vm.*
import pro.darc.viruaisor.R

@Composable
internal fun WorkbenchContent(
    state: VmUiState,
    environment: VmEnvironment = VmEnvironment(),
    actions: VmUiActions,
    fullscreen: Boolean = false,
    appearance: Appearance = Appearance.SYSTEM,
    setAppearance: (Appearance) -> Unit = {},
    desktop: @Composable (Modifier) -> Unit = {},
) {
    var route by rememberSaveable { mutableStateOf("workbench") }
    var panel by rememberSaveable { mutableStateOf(0) }
    BackHandler(enabled = !fullscreen && route != "workbench") { route = if(route == "diagnostics") "settings" else "workbench" }
    LaunchedEffect(fullscreen) { if(fullscreen) route = "workbench" }
    BoxWithConstraints(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        val rail = maxWidth >= 840.dp
        Scaffold(containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = if(fullscreen) WindowInsets(0,0,0,0) else WindowInsets.safeDrawing,
            bottomBar = {
                if(!fullscreen && !rail) NavigationBar(containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.testTag("bottom_navigation")) {
                    NavigationBarItem(selected = route == "workbench", onClick = { route = "workbench" },
                        icon = { Glyph(Symbol.WORKSPACE) }, label = { Text("工作台") }, modifier = Modifier.testTag("nav_workbench"))
                    NavigationBarItem(selected = route != "workbench", onClick = { route = "settings" },
                        icon = { Glyph(Symbol.SETTINGS) }, label = { Text("设置") }, modifier = Modifier.testTag("nav_settings"))
                }
            }) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if(!fullscreen && rail) NavigationRail(containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxHeight().width(88.dp), header = {
                        Surface(color = Color(0xFF101C36), shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.padding(top = 20.dp, bottom = 36.dp).size(44.dp)) {
                            Image(painterResource(R.drawable.viruaisor_mark), contentDescription = "Viruaisor",
                                modifier = Modifier.padding(2.dp))
                        }
                    }) {
                    NavigationRailItem(selected = route == "workbench", onClick = { route = "workbench" },
                        icon = { Glyph(Symbol.WORKSPACE) }, label = { Text("工作台") }, modifier = Modifier.testTag("nav_workbench"))
                    Spacer(Modifier.height(18.dp))
                    NavigationRailItem(selected = route != "workbench", onClick = { route = "settings" },
                        icon = { Glyph(Symbol.SETTINGS) }, label = { Text("设置") }, modifier = Modifier.testTag("nav_settings"))
                    Spacer(Modifier.weight(1f))
                    Muted("AVF", Modifier.padding(bottom = 20.dp))
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if(!fullscreen) PageHeader(state, route)
                    Layout(modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(), content = {
                        // Keep the live Surface under the same parent while viewing
                        // settings. An offscreen, accessibility-hidden workbench keeps
                        // its last guest buffer without covering the active page.
                        Box(Modifier.fillMaxSize().then(if(route != "workbench") Modifier.clearAndSetSemantics {} else Modifier)) {
                            Workspace(state, environment, actions, fullscreen, panel, { panel = it }, { route = "settings" }, desktop)
                        }
                        when(route) {
                            "settings" -> SettingsPage(state, environment, appearance, setAppearance, actions) { route = "diagnostics" }
                            "diagnostics" -> DiagnosticsPage(state, environment, actions) { route = "settings" }
                        }
                    }) { measurables, constraints ->
                        val width = constraints.maxWidth
                        val height = constraints.maxHeight
                        val page = Constraints.fixed(width, height)
                        val workspace = measurables[0].measure(page)
                        val activePage = measurables.getOrNull(1)?.measure(page)
                        layout(width, height) {
                            workspace.placeRelative(if(route == "workbench") 0 else -width * 2, 0)
                            activePage?.placeRelative(0,0)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageHeader(state: VmUiState, route: String) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 22.dp)) {
        val title = when(route) { "settings" -> "设置"; "diagnostics" -> "开发与排障"; else -> state.bundle?.osName ?: "你的 Linux 工作空间" }
        val compact = maxWidth < 600.dp
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Muted(when(route) { "settings" -> "VIRUAISOR / 偏好与环境"; "diagnostics" -> "VIRUAISOR / 诊断"; else -> "VIRUAISOR / 工作台" })
                    Spacer(Modifier.height(5.dp))
                    Text(title, style = MaterialTheme.typography.headlineMedium)
                }
                if(!compact) AvfBadge(state)
            }
            if(compact) AvfBadge(state)
        }
    }
}

@Composable
private fun AvfBadge(state: VmUiState) {
    Box(Modifier.semantics { contentDescription = if(state.support is AvfSupport.Available) "AVF_AVAILABLE" else "AVF_UNAVAILABLE" }) {
        StatusBadge(when(state.support) { is AvfSupport.Available -> "AVF 可用"; AvfSupport.Checking -> "环境检查中"; else -> "环境需配置" }, state.support is AvfSupport.Available)
    }
}

@Composable
private fun Workspace(state: VmUiState, environment: VmEnvironment, actions: VmUiActions, fullscreen: Boolean,
    panel: Int, selectPanel: (Int) -> Unit, openSettings: () -> Unit, desktop: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val dual = maxWidth >= 860.dp
        val compact = !dual && !fullscreen
        val sidePadding = if(fullscreen) 0.dp else if(dual) 24.dp else 16.dp
        Column(Modifier.fillMaxSize().then(if(compact) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(start = sidePadding, end = sidePadding, bottom = if(fullscreen) 0.dp else 24.dp)) {
            // Keep the native display at one composition site and under one Layout.
            // Moving AndroidView between Row/Column/fullscreen parents recreates its
            // Surface queue and loses a static guest's last submitted frame.
            Layout(modifier = Modifier.fillMaxWidth(), content = {
                DesktopStage(state, environment, actions, fullscreen, Modifier.fillMaxSize(), openSettings, desktop)
                if(!fullscreen) Inspector(state, environment, actions, panel, selectPanel, openSettings, dual, Modifier.fillMaxSize())
            }) { measurables, constraints ->
                val width = constraints.maxWidth
                val gap = if(dual) 20.dp.roundToPx() else 18.dp.roundToPx()
                if(fullscreen) {
                    val height = constraints.maxHeight
                    val stage = measurables[0].measure(Constraints.fixed(width, height))
                    layout(width, height) { stage.placeRelative(0,0) }
                } else if(dual) {
                    val height = constraints.maxHeight
                    val inspectorWidth = 340.dp.roundToPx()
                    val stageWidth = width - gap - inspectorWidth
                    val stage = measurables[0].measure(Constraints.fixed(stageWidth, height))
                    val inspector = measurables[1].measure(Constraints.fixed(inspectorWidth, height))
                    layout(width, height) { stage.placeRelative(0,0); inspector.placeRelative(stageWidth + gap,0) }
                } else {
                    val stage = measurables[0].measure(Constraints.fixed(width, 300.dp.roundToPx()))
                    val inspector = measurables[1].measure(Constraints(minWidth = width, maxWidth = width))
                    layout(width, stage.height + gap + inspector.height) {
                        stage.placeRelative(0,0); inspector.placeRelative(0,stage.height + gap)
                    }
                }
            }
        }
    }
}

@Composable
private fun Inspector(state: VmUiState, environment: VmEnvironment, actions: VmUiActions, selected: Int,
    select: (Int) -> Unit, openSettings: () -> Unit, scroll: Boolean, modifier: Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("控制", "存储", "显示").forEachIndexed { index, label ->
                    val tag = listOf("control", "storage", "display")[index]
                    TextButton(onClick = { select(index) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("panel_$tag"),
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.textButtonColors(containerColor = if(index == selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            contentColor = if(index == selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)) { Text(label) }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column((if(scroll) Modifier.weight(1f).verticalScroll(rememberScrollState()) else Modifier).padding(20.dp)) {
                when(selected) {
                    1 -> StoragePanel(state, environment, actions)
                    2 -> DisplayPanel(state, environment, actions)
                    else -> ControlPanel(state, environment, actions, openSettings)
                }
            }
        }
    }
}

@Composable
private fun DesktopStage(state: VmUiState, environment: VmEnvironment, actions: VmUiActions, fullscreen: Boolean,
    modifier: Modifier, openSettings: () -> Unit, desktop: @Composable (Modifier) -> Unit) {
    Surface(modifier, shape = if(fullscreen) RoundedCornerShape(0.dp) else MaterialTheme.shapes.large,
        color = Color(0xFF111827), border = if(fullscreen) null else BorderStroke(1.dp, Color(0xFF2A354A))) {
        Column {
            if(!fullscreen) Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Glyph(Symbol.SCREEN, color = Color(0xFFA7B7D6))
                Text("桌面预览", color = Color(0xFFE4EAF5), style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { actions.onFullscreen(!fullscreen) }, enabled = fullscreen || state.isActive,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFBFCCFF)), modifier = Modifier.testTag("toggle_fullscreen")) {
                    Glyph(if(fullscreen) Symbol.BACK else Symbol.EXPAND, Modifier.size(17.dp)); Spacer(Modifier.width(8.dp))
                    Text(if(fullscreen) "退出全屏" else "全屏")
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
                if(state.isActive) desktop(Modifier.fillMaxSize()) else {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Glyph(Symbol.WORKSPACE, Modifier.size(42.dp), Color(0xFF8092B8))
                        Text(if(state.bundle == null) "从一个 Linux 系统包开始" else "工作空间，随时就绪", color = Color(0xFFE4EAF5), style = MaterialTheme.typography.titleLarge)
                        Text(if(state.bundle == null) "导入系统后，在这里打开你的桌面。" else "启动虚拟机，然后点击画面进入全屏。", color = Color(0xFF95A5C2), style = MaterialTheme.typography.bodySmall)
                        if(state.support !is AvfSupport.Available) TextButton(onClick = openSettings) { Text("配置运行环境", color = Color(0xFFBFCCFF)) }
                    }
                }
            }
            if(!fullscreen) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                val text = when {
                    !state.isActive -> statusLabel(state.status)
                    environment.surfaceReady && state.display.presented != null -> "已收到客体画面 · 点击进入桌面"
                    environment.surfaceReady -> "显示已连接 · 等待客体画面"
                    else -> "${statusLabel(state.status)} · 等待显示连接"
                }
                Text(text, style = MaterialTheme.typography.bodySmall, color = Color(0xFFA7B7D6),
                    modifier = Modifier.weight(1f).semantics { contentDescription = "VM_STATUS:${state.status.javaClass.simpleName}" })
            }
        }
        if(fullscreen) FullscreenControls { actions.onFullscreen(false) }
    }
}
