package app.universal3d.inspector.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF4FC3F7), onPrimary = Color(0xFF00334A), primaryContainer = Color(0xFF004D6E), onPrimaryContainer = Color(0xFFCDEBFF),
    secondary = Color(0xFF9FB8C9), tertiary = Color(0xFFFFB74D), background = Color(0xFF101418), surface = Color(0xFF141A20),
    surfaceVariant = Color(0xFF232C35), onSurface = Color(0xFFE3E8EC), onSurfaceVariant = Color(0xFFB4BEC7), error = Color(0xFFFF8A80),
)
private val Light = lightColorScheme(
    primary = Color(0xFF01579B), onPrimary = Color.White, primaryContainer = Color(0xFFCDEBFF), onPrimaryContainer = Color(0xFF002A44),
    secondary = Color(0xFF4A6272), tertiary = Color(0xFF8A5A00), background = Color(0xFFF7FAFC), surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE3EBF1), onSurface = Color(0xFF14181C), onSurfaceVariant = Color(0xFF45515A), error = Color(0xFFB3261E),
)

object U3DColors {
    val ok = Color(0xFF43A047); val warn = Color(0xFFF9A825); val loss = Color(0xFFE53935); val info = Color(0xFF29B6F6)
}

@Composable
fun U3DTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
