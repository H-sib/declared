package io.github.h_sib.declared.app.ui

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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.h_sib.declared.app.R

/**
 * The look of the app. Two themes made on purpose: "night" (deep navy, electric cyan, a violet second accent) and "day" (cool white, deep blue).
 * Statuses have their own colors, kept for what they mean: red is a risk, amber is a thing to look at, green is "nothing found", blue is a note,
 * grey is "not read". Color is never the only signal: every status also has a shape and a word (see Kit.kt). The contrast of each text pair was
 * computed: at least 4.9 to 1, most above 6 to 1. The typeface family is Atkinson Hyperlegible (SIL Open Font License, bundled).
 */
private fun face(res: Int, weight: Int) = Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val DeclaredText = FontFamily(face(R.font.declared_text, 400), face(R.font.declared_text, 500), face(R.font.declared_text, 700))

/** For evidence: hashes, package names, identifiers. */
val DeclaredMono = FontFamily(face(R.font.declared_mono, 400), face(R.font.declared_mono, 500))

@Immutable
class StatusColors(val risk: Color, val look: Color, val ok: Color, val note: Color, val unknown: Color)

val LocalStatusColors = staticCompositionLocalOf { NightStatus }

private val NightStatus = StatusColors(risk = Color(0xFFFF7088), look = Color(0xFFFFBA4D), ok = Color(0xFF43DFA0), note = Color(0xFF6FB3FF), unknown = Color(0xFF8F9BC0))
private val DayStatus = StatusColors(risk = Color(0xFFC0203A), look = Color(0xFF8F5500), ok = Color(0xFF0B7447), note = Color(0xFF1458B8), unknown = Color(0xFF566290))

private val Night = darkColorScheme(
    primary = Color(0xFF3CC8F0), onPrimary = Color(0xFF04141F),
    primaryContainer = Color(0xFF18244A), onPrimaryContainer = Color(0xFFE8EDFF),
    secondary = Color(0xFFA99BFF), onSecondary = Color(0xFF0A0F1E),
    secondaryContainer = Color(0xFF241F55), onSecondaryContainer = Color(0xFFE8EDFF),
    tertiary = Color(0xFF6FB3FF), onTertiary = Color(0xFF0A0F1E),
    background = Color(0xFF0A0F1E), onBackground = Color(0xFFE8EDFF),
    surface = Color(0xFF0A0F1E), onSurface = Color(0xFFE8EDFF),
    surfaceVariant = Color(0xFF111A33), onSurfaceVariant = Color(0xFF9DAACF),
    surfaceContainerLowest = Color(0xFF0A0F1E), surfaceContainerLow = Color(0xFF111A33), surfaceContainer = Color(0xFF111A33),
    surfaceContainerHigh = Color(0xFF18244A), surfaceContainerHighest = Color(0xFF18244A),
    outline = Color(0xFF9DAACF), outlineVariant = Color(0xFF2A3A6B),
    error = Color(0xFFFF7088), onError = Color(0xFF0A0F1E),
    errorContainer = Color(0xFF3A1622), onErrorContainer = Color(0xFFFF7088),
)

private val Day = lightColorScheme(
    primary = Color(0xFF0A5FC2), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE6FA), onPrimaryContainer = Color(0xFF0A1230),
    secondary = Color(0xFF5342CF), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE5E1FB), onSecondaryContainer = Color(0xFF0A1230),
    tertiary = Color(0xFF1458B8), onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF2F5FC), onBackground = Color(0xFF0A1230),
    surface = Color(0xFFF2F5FC), onSurface = Color(0xFF0A1230),
    surfaceVariant = Color(0xFFFFFFFF), onSurfaceVariant = Color(0xFF475585),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFFFFFF), surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE6ECFA), surfaceContainerHighest = Color(0xFFE6ECFA),
    outline = Color(0xFF475585), outlineVariant = Color(0xFFC5D0EC),
    error = Color(0xFFC0203A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFAE0E5), onErrorContainer = Color(0xFFC0203A),
)

/** Sizes in sp: 11 and 12 labels, 14 secondary, 16 body, 20 titles, 26 section statements. Weights 400, 500 and 700 only. */
private fun style(size: Int, line: Int, weight: Int = 400, family: FontFamily = DeclaredText, spacing: Float = 0f) =
    TextStyle(fontFamily = family, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = line.sp, letterSpacing = spacing.sp)

private val DeclaredTypography = Typography(
    displaySmall = style(32, 40, 500),
    headlineSmall = style(26, 32, 500),
    titleLarge = style(20, 28, 500),
    titleMedium = style(18, 26, 500),
    titleSmall = style(16, 22, 500),
    bodyLarge = style(16, 24),
    bodyMedium = style(14, 20),
    bodySmall = style(12, 16),
    labelLarge = style(14, 20, 500),
    labelMedium = style(12, 16, 500, spacing = 0.2f),
    labelSmall = style(11, 16, 400, spacing = 0.4f),
)

private val DeclaredShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private fun TextStyle.scaled(f: Float) = copy(fontSize = fontSize * f, lineHeight = lineHeight * f)

private fun Typography.scaled(f: Float) = copy(
    displaySmall = displaySmall.scaled(f), headlineSmall = headlineSmall.scaled(f), titleLarge = titleLarge.scaled(f), titleMedium = titleMedium.scaled(f),
    titleSmall = titleSmall.scaled(f), bodyLarge = bodyLarge.scaled(f), bodyMedium = bodyMedium.scaled(f), bodySmall = bodySmall.scaled(f),
    labelLarge = labelLarge.scaled(f), labelMedium = labelMedium.scaled(f), labelSmall = labelSmall.scaled(f),
)

/** [large] is the simple view: the same type, a fifth bigger. */
@Composable
fun DeclaredTheme(large: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalStatusColors provides if (dark) NightStatus else DayStatus) {
        MaterialTheme(colorScheme = if (dark) Night else Day, typography = if (large) DeclaredTypography.scaled(1.2f) else DeclaredTypography, shapes = DeclaredShapes, content = content)
    }
}
