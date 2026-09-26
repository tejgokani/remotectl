package dev.remotectl.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Aqua = Color(0xFF2DD4BF)
val Online = Color(0xFF34D399)

private val scheme = darkColorScheme(
    primary = Aqua,
    onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF0F3D38),
    onPrimaryContainer = Color(0xFFB5F5EC),
    secondaryContainer = Color(0xFF173330),
    onSecondaryContainer = Color(0xFFCFEFEA),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE6F1EF),
    surface = Color(0xFF080C0D),
    onSurface = Color(0xFFE6F1EF),
    surfaceVariant = Color(0xFF131A1C),
    onSurfaceVariant = Color(0xFF9DB0AD),
    surfaceContainer = Color(0xFF0E1415),
    surfaceContainerHigh = Color(0xFF151D1F),
    outline = Color(0xFF2A3638),
    outlineVariant = Color(0xFF1C2527),
    error = Color(0xFFFF6B6B),
    errorContainer = Color(0xFF3A1414),
    onErrorContainer = Color(0xFFFFD6D6),
)

@Composable
fun RemotectlTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
