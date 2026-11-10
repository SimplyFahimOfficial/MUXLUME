package com.muxlume.app.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Brand tokens. See docs/DESIGN_SYSTEM.md. */
object Mux {
    val Black = Color(0xFF000000)
    val Surface = Color(0xFF111114)
    val Raised = Color(0xFF1B1B20)
    val Outline = Color(0xFF2A2A31)
    val White = Color(0xFFFFFFFF)
    val Cream = Color(0xFFFFF7E8)
    val Orange = Color(0xFFE65A00)
    /** The logo's own orange; for brand artwork only. */
    val LogoOrange = Color(0xFFFD5902)
    val TextSecondary = Color(0xFFA4A4AC)
    val Error = Color(0xFFFF6B5E)
    val Scrim = Color(0xB3000000)

    val CardShape = RoundedCornerShape(20.dp)
    val HeroShape = RoundedCornerShape(28.dp)
    val PillShape = RoundedCornerShape(50)

    val ScreenPadding = 20.dp
}

/** Motion tokens: short, one easing curve, and no motion at all when the user asked for less. */
object Motion {
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val SHORT = 180
    const val MEDIUM = 260

    fun <T> spec(durationMs: Int, reduced: Boolean): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(durationMs, easing = Emphasized)
}

val LocalReducedMotion = staticCompositionLocalOf { false }

object MuxTheme {
    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
}

private val colors = darkColorScheme(
    primary = Mux.Cream,
    onPrimary = Mux.Black,
    primaryContainer = Mux.Raised,
    onPrimaryContainer = Mux.Cream,
    secondary = Mux.Orange,
    onSecondary = Mux.Black,
    tertiary = Mux.Orange,
    background = Mux.Black,
    onBackground = Mux.White,
    surface = Mux.Black,
    onSurface = Mux.White,
    surfaceVariant = Mux.Raised,
    onSurfaceVariant = Mux.TextSecondary,
    surfaceContainerLowest = Mux.Black,
    surfaceContainerLow = Mux.Surface,
    surfaceContainer = Mux.Surface,
    surfaceContainerHigh = Mux.Raised,
    surfaceContainerHighest = Mux.Outline,
    outline = Mux.Outline,
    outlineVariant = Mux.Outline,
    error = Mux.Error,
    onError = Mux.Black,
)

private val base = Typography()
private val typography = Typography(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

/** Uppercase section label above rows. */
val SectionLabel = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)

@Composable
fun MuxlumeTheme(reduceMotion: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemOff = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    CompositionLocalProvider(LocalReducedMotion provides (reduceMotion || systemOff)) {
        MaterialTheme(
            colorScheme = colors,
            typography = typography,
            shapes = Shapes(small = RoundedCornerShape(12.dp), medium = Mux.CardShape, large = Mux.HeroShape),
            content = content,
        )
    }
}
