package ai.kira.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared semantic brand tokens. Prefer MaterialTheme.colorScheme in feature UI. */
object KiraPalette {
    val background = Color(0xFF0B0E15)
    val surface = Color(0xFF151C2B)
    val surfaceVariant = Color(0xFF20283A)
    val elevatedSurface = Color(0xFF1B2435)
    val primary = Color(0xFFB9A5FF)
    val primaryContainer = Color(0xFF493D79)
    val secondary = Color(0xFF8FD8D1)
    val onBackground = Color(0xFFF4F2FA)
    val onSurface = Color(0xFFF1EFF8)
    val muted = Color(0xFF9BA5B8)
    val outline = Color(0xFF49536A)
    val error = Color(0xFFFFB4AB)
    val success = Color(0xFF83D6A5)
}
private val KiraColorScheme = darkColorScheme(
    primary = KiraPalette.primary, onPrimary = Color(0xFF24164D),
    primaryContainer = KiraPalette.primaryContainer, onPrimaryContainer = Color(0xFFE5DCFF),
    secondary = KiraPalette.secondary, onSecondary = Color(0xFF003735),
    background = KiraPalette.background, onBackground = KiraPalette.onBackground,
    surface = KiraPalette.surface, onSurface = KiraPalette.onSurface,
    surfaceVariant = KiraPalette.surfaceVariant, onSurfaceVariant = Color(0xFFC2C9D6),
    outline = KiraPalette.outline, error = KiraPalette.error
)
private val KiraTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 25.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
)
val KiraShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
@Composable
fun KiraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = KiraColorScheme, typography = KiraTypography, shapes = KiraShapes, content = content)
}
