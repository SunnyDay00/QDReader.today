package today.qdreader.auto.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AppColorScheme = lightColorScheme(
    primary = Color(0xFFDC2626),
    onPrimary = Color.White,
    secondary = Color(0xFF64748B),
    tertiary = Color(0xFFB91C1C),
    background = Color(0xFFFFF7F7),
    surface = Color.White,
    surfaceVariant = Color(0xFFFEE2E2),
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A)
)

@Composable
fun QDReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        content = content
    )
}
