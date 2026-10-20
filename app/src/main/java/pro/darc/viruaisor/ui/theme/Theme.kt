package pro.darc.viruaisor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Light = lightColorScheme(
    primary = Color(0xFF3159E4), onPrimary = Color.White,
    primaryContainer = Color(0xFFE7ECFF), onPrimaryContainer = Color(0xFF213DA6),
    secondary = Color(0xFF56617B), secondaryContainer = Color(0xFFE8EDF7),
    background = Color(0xFFF3F5F9), onBackground = Color(0xFF192235),
    surface = Color.White, onSurface = Color(0xFF192235),
    surfaceVariant = Color(0xFFEDF0F6), onSurfaceVariant = Color(0xFF626D82),
    outline = Color(0xFF8390A6), outlineVariant = Color(0xFFDFE4EE),
    error = Color(0xFFB73544), errorContainer = Color(0xFFFFEDEE),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFB8C5FF), onPrimary = Color(0xFF142768),
    primaryContainer = Color(0xFF253568), onPrimaryContainer = Color(0xFFDCE3FF),
    secondary = Color(0xFFB7C2DA), secondaryContainer = Color(0xFF303A51),
    background = Color(0xFF10141E), onBackground = Color(0xFFE6EAF4),
    surface = Color(0xFF1A202E), onSurface = Color(0xFFE6EAF4),
    surfaceVariant = Color(0xFF252D3E), onSurfaceVariant = Color(0xFFABB5CB),
    outline = Color(0xFF78859E), outlineVariant = Color(0xFF343E52),
    error = Color(0xFFFFB1B8), errorContainer = Color(0xFF4B2631),
)

@Composable
fun ViruaisorTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, typography = Typography,
        shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(28.dp)), content = content)
}
