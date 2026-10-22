package ai.local.voiceagent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Scheme = darkColorScheme(
    primary = Color(0xFF7DD3FC),
    onPrimary = Color(0xFF06202B),
    secondary = Color(0xFF9AE6B4),
    onSecondary = Color(0xFF06281A),
    background = Color(0xFF101014),
    onBackground = Color(0xFFE7E7EA),
    surface = Color(0xFF17171C),
    onSurface = Color(0xFFE7E7EA),
    surfaceVariant = Color(0xFF23232B),
    onSurfaceVariant = Color(0xFFB6B6C0),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF2B0704),
)

@Composable
fun VoiceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
