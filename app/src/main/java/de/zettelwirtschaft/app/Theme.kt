package de.zettelwirtschaft.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Farben: Papier, Tinte und das Grün aus dem App-Icon
private val LightColors = lightColorScheme(
    primary = Color(0xFF2E7D5B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE8DA),
    onPrimaryContainer = Color(0xFF0B3D28),
    secondaryContainer = Color(0xFFE6F2EB),
    onSecondaryContainer = Color(0xFF0B3D28),
    tertiary = Color(0xFFB8741F),
    background = Color(0xFFEFE9DD),
    onBackground = Color(0xFF1F1D1A),
    surface = Color(0xFFFFFDF8),
    onSurface = Color(0xFF1F1D1A),
    onSurfaceVariant = Color(0xFF6B655B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFDF8),
    surfaceContainer = Color(0xFFF7F2E8),
    surfaceContainerHigh = Color(0xFFF1EBDF),
    surfaceContainerHighest = Color(0xFFEAE3D5),
    outline = Color(0xFFB9AF9C),
    outlineVariant = Color(0xFFDDD4C3),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF86D4AB),
    onPrimary = Color(0xFF00381F),
    primaryContainer = Color(0xFF1E5A40),
    onPrimaryContainer = Color(0xFFCDE8DA),
    secondaryContainer = Color(0xFF26352D),
    onSecondaryContainer = Color(0xFFCDE8DA),
    tertiary = Color(0xFFF0B46A),
    background = Color(0xFF121412),
    onBackground = Color(0xFFECE6DA),
    surface = Color(0xFF1E211E),
    onSurface = Color(0xFFECE6DA),
    onSurfaceVariant = Color(0xFFB0A99C),
    surfaceContainerLowest = Color(0xFF0D0F0D),
    surfaceContainerLow = Color(0xFF1E211E),
    surfaceContainer = Color(0xFF232723),
    surfaceContainerHigh = Color(0xFF2A2E2A),
    surfaceContainerHighest = Color(0xFF333833),
    outline = Color(0xFF6E7369),
    outlineVariant = Color(0xFF3C413B),
    error = Color(0xFFF2B8B5),
)

/** Beträge in Monospace – wie auf einem Kassenbon, und Ziffern stehen sauber untereinander. */
val Mono = FontFamily.Monospace
val Serif = FontFamily.Serif

private val AppTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
    )
}

val LabelCaps = TextStyle(
    fontFamily = Mono,
    fontSize = 11.sp,
    letterSpacing = 2.sp,
    fontWeight = FontWeight.Medium,
)

@Composable
fun ZettelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}

/** Rechteck mit gezackter Abrisskante unten – der Kassenbon. */
class ReceiptShape(private val tooth: Dp = 7.dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val t = with(density) { tooth.toPx() }
        val teeth = (size.width / (t * 2f)).toInt().coerceAtLeast(1)
        val step = size.width / teeth
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height - t)
            var x = size.width
            repeat(teeth) {
                lineTo(x - step / 2f, size.height)
                lineTo(x - step, size.height - t)
                x -= step
            }
            close()
        }
        return Outline.Generic(path)
    }
}

/** Gestrichelte Linie wie auf einem Bon. */
@Composable
fun DashedDivider(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        drawLine(
            color = color,
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
        )
    }
}

// Gedeckte Farben für die runden Kürzel vor jedem Eintrag (je nach Zweck immer dieselbe)
private val StampLight = listOf(0xFF2E7D5B, 0xFFB8741F, 0xFF3F6E9E, 0xFF9A4D6B, 0xFF6B7A2E, 0xFF7A5A3C)
private val StampDark = listOf(0xFF86D4AB, 0xFFF0B46A, 0xFF9CC3EE, 0xFFE7A6C1, 0xFFC5D67F, 0xFFD9B894)

@Composable
fun stampColor(key: String): Color {
    val list = if (isSystemInDarkTheme()) StampDark else StampLight
    val i = (key.lowercase().hashCode() and Int.MAX_VALUE) % list.size
    return Color(list[i])
}
