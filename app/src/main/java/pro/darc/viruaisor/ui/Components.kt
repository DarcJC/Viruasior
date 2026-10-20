package pro.darc.viruaisor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp

internal enum class Symbol { WORKSPACE, SETTINGS, SCREEN, STORAGE, PLAY, POWER, ARROW, BACK, EXPAND, CODE }

@Composable
internal fun Glyph(symbol: Symbol, modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    Canvas(modifier.size(22.dp)) {
        val u = size.width / 24f
        fun line(x: Float, y: Float, xx: Float, yy: Float) = drawLine(color, Offset(x*u,y*u), Offset(xx*u,yy*u), 1.7f*u)
        when (symbol) {
            Symbol.SCREEN, Symbol.WORKSPACE -> {
                drawRoundRect(color, Offset(3*u,4*u), Size(18*u,13*u), CornerRadius(2*u), style = Stroke(1.7f*u))
                line(12f,17f,12f,21f); line(8f,21f,16f,21f)
                if (symbol == Symbol.WORKSPACE) { line(8f,8f,10f,10f); line(10f,10f,8f,12f); line(13f,12f,16f,12f) }
            }
            Symbol.SETTINGS -> {
                listOf(6f,12f,18f).forEachIndexed { i,y ->
                    line(3f,y,21f,y)
                    drawCircle(color, 2.5f*u, Offset((if(i == 1) 16 else 8)*u,y*u))
                }
            }
            Symbol.STORAGE -> {
                drawRoundRect(color, Offset(3*u,4*u), Size(18*u,16*u), CornerRadius(3*u), style = Stroke(1.7f*u))
                line(3f,14f,21f,14f); drawCircle(color, u, Offset(17*u,17*u))
            }
            Symbol.PLAY -> drawPath(Path().apply { moveTo(8*u,4*u); lineTo(20*u,12*u); lineTo(8*u,20*u); close() }, color)
            Symbol.POWER -> { drawArc(color, -45f, 270f, false, Offset(4*u,4*u), Size(16*u,16*u), style = Stroke(1.7f*u)); line(12f,2f,12f,11f) }
            Symbol.ARROW -> { line(4f,12f,20f,12f); line(14f,6f,20f,12f); line(14f,18f,20f,12f) }
            Symbol.BACK -> { line(20f,12f,4f,12f); line(10f,6f,4f,12f); line(10f,18f,4f,12f) }
            Symbol.EXPAND -> { line(3f,9f,3f,3f); line(3f,3f,9f,3f); line(15f,3f,21f,3f); line(21f,3f,21f,9f); line(3f,15f,3f,21f); line(3f,21f,9f,21f); line(15f,21f,21f,21f); line(21f,21f,21f,15f) }
            Symbol.CODE -> { line(8f,6f,2f,12f); line(2f,12f,8f,18f); line(16f,6f,22f,12f); line(22f,12f,16f,18f); line(14f,3f,10f,21f) }
        }
    }
}

@Composable
internal fun SectionCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                subtitle?.let { Muted(it) }
            }
            content()
        }
    }
}

@Composable internal fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable internal fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Muted(label)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable internal fun Notice(text: String, error: Boolean = false) {
    Surface(color = if(error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall,
            color = if(error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable internal fun StatusBadge(label: String, active: Boolean = false, error: Boolean = false) {
    val color = when { error -> MaterialTheme.colorScheme.error; active -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurfaceVariant }
    Surface(color = color.copy(alpha = .09f), shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(6.dp).background(color, RoundedCornerShape(50)))
            Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

@Composable internal fun TechnicalDetails(label: String = "查看技术详情", text: String) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text(if(expanded) "收起详情" else label)
        }
        if(expanded) SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable internal fun ConfirmDialog(title: String, message: String, confirm: String, enabled: Boolean = true,
    dismiss: () -> Unit, action: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, modifier = Modifier.semantics { testTagsAsResourceId = true }, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { dismiss(); action() }, enabled = enabled, modifier = Modifier.testTag("confirm_action")) { Text(confirm) } },
        dismissButton = { TextButton(onClick = dismiss, modifier = Modifier.testTag("cancel_action")) { Text("取消") } })
}
