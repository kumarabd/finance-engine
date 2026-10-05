package org.nighthawklabs.treasure.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Ledger at night": the AutoTelemetry neutrals with a brass accent. Expenses are plain text (spending is not an error);
 * [live] marks money coming back in; [critical] is only for errors and destructive actions.
 * Contrast measured: brass 5.2-5.9:1 on light surfaces, 8.4-10:1 on dark; light green >= 4.7:1; chart colors >= 5:1.
 */
@Immutable
class Tok(
    val dark: Boolean,
    val background: Color, val surface: Color, val raised: Color, val hairline: Color,
    val text: Color, val muted: Color, val accent: Color, val onAccent: Color,
    val live: Color, val warn: Color, val critical: Color,
    /** Categorical chart colors in rank order. Never the only carrier of meaning: every row is also labeled. */
    val series: List<Color>,
)

val InkTok = Tok(
    true, Color(0xFF0B0D10), Color(0xFF14181D), Color(0xFF1B2027), Color(0xFF262C34),
    Color(0xFFE8ECF1), Color(0xFF8A94A1), Color(0xFFE3B25C), Color(0xFF1A1203),
    Color(0xFF3DDC97), Color(0xFFFFB020), Color(0xFFFF5C5C),
    listOf(Color(0xFFE3B25C), Color(0xFF4FB6A8), Color(0xFF6C9BE8), Color(0xFF9B8AE0), Color(0xFFE0806B), Color(0xFF8DBF6F)),
)

val PaperTok = Tok(
    false, Color(0xFFF6F7F9), Color(0xFFFFFFFF), Color(0xFFEEF0F3), Color(0xFFE1E5EA),
    Color(0xFF0F1318), Color(0xFF5B6674), Color(0xFF8A5A00), Color(0xFFFFFFFF),
    Color(0xFF0A7A4C), Color(0xFF9A6200), Color(0xFFC42B2B),
    listOf(Color(0xFF8A5A00), Color(0xFF13796E), Color(0xFF2F5FC4), Color(0xFF6D4FC2), Color(0xFFB5472F), Color(0xFF4D7A25)),
)

val LocalTok = staticCompositionLocalOf { InkTok }

object Treasure {
    val tok: Tok @Composable @ReadOnlyComposable get() = LocalTok.current
}

/** Tabular figures so columns align and rolling digits don't jitter. */
val Tabular = TextStyle(fontFeatureSettings = "tnum")

private val AppTypography = Typography().let { d ->
    d.copy(
        titleLarge = d.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = d.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun TreasureTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val t = if (dark) InkTok else PaperTok
    val scheme = if (dark) {
        darkColorScheme(
            primary = t.accent, onPrimary = t.onAccent, background = t.background, onBackground = t.text,
            surface = t.surface, onSurface = t.text, surfaceVariant = t.raised, onSurfaceVariant = t.muted,
            secondaryContainer = Color(0xFF3A2E14), onSecondaryContainer = t.accent,
            surfaceContainer = t.surface, surfaceContainerHigh = t.raised, surfaceContainerHighest = t.raised,
            outline = t.hairline, outlineVariant = t.hairline, error = t.critical, onError = t.onAccent,
            inverseSurface = t.raised, inverseOnSurface = t.text, inversePrimary = t.accent,
        )
    } else {
        lightColorScheme(
            primary = t.accent, onPrimary = t.onAccent, background = t.background, onBackground = t.text,
            surface = t.surface, onSurface = t.text, surfaceVariant = t.raised, onSurfaceVariant = t.muted,
            secondaryContainer = Color(0xFFF3E4C4), onSecondaryContainer = t.accent,
            surfaceContainer = t.surface, surfaceContainerHigh = t.raised, surfaceContainerHighest = t.raised,
            outline = t.hairline, outlineVariant = t.hairline, error = t.critical, onError = t.onAccent,
            inverseSurface = t.text, inverseOnSurface = t.background, inversePrimary = t.accent,
        )
    }
    CompositionLocalProvider(LocalTok provides t) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

/** Large money figures: sp-based, so they follow the system font scale, and they shrink rather than truncate. */
val HeroStyle = TextStyle(fontSize = 44.sp, lineHeight = 50.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
