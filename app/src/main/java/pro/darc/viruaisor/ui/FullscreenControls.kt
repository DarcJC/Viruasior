package pro.darc.viruaisor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

/** Separate window sits above the on-top Surface without resizing its viewport. */
@Composable
internal fun FullscreenControls(exit: () -> Unit) {
    var expanded by remember { mutableStateOf(true) }
    LaunchedEffect(expanded) {
        if(expanded) { delay(2500); expanded = false }
    }
    val top = with(LocalDensity.current) { 8.dp.roundToPx() }
    Popup(alignment = Alignment.TopCenter, offset = IntOffset(0,top),
        properties = PopupProperties(focusable = false, dismissOnBackPress = false)) {
        Box(Modifier.semantics { testTagsAsResourceId = true }) {
            if(expanded) Surface(shape = RoundedCornerShape(16.dp), color = Color(0xEE192235),
                contentColor = Color(0xFFE6EAF4), border = BorderStroke(1.dp, Color(0xFF52607B)), shadowElevation = 8.dp) {
                Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = exit, modifier = Modifier.heightIn(min = 48.dp).testTag("toggle_fullscreen"),
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFE6EAF4))) {
                        Glyph(Symbol.BACK, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("退出全屏")
                    }
                    TextButton(onClick = { expanded = false }, modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFB8C5FF))) { Text("收起") }
                }
            } else Box(Modifier.size(72.dp,48.dp).testTag("fullscreen_handle")
                .semantics { contentDescription = "显示全屏控制" }.clickable { expanded = true }, contentAlignment = Alignment.TopCenter) {
                Box(Modifier.padding(top = 6.dp).size(36.dp,4.dp).background(Color(0xBBAAB8D4), RoundedCornerShape(50)))
            }
        }
    }
}
