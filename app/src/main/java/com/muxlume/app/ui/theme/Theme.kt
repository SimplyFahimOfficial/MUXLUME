package com.muxlume.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.muxlume.app.data.Accent
import com.muxlume.app.data.ThemeMode

/** Brand constants that don't change with the theme: shapes and spacing. */
object Mux {
    val CardShape = RoundedCornerShape(20.dp)
    val HeroShape = RoundedCornerShape(28.dp)
    val PillShape = RoundedCornerShape(50)

    val ScreenPadding = 20.dp
}

/**
 * Semantic colour roles of the current theme, read through [MuxTheme]. Screens never use fixed
 * colours, except white/black on media and scrims, which look the same in every theme.
 */
data class MuxPalette(
    /** Screen background. */
    val background: Color,
    /** Cards and sheets. */
    val surface: Color,
    /** Pills, inputs, the bottom bar. */
    val raised: Color,
    val outline: Color,
    /** Main text and icons. */
    val textPrimary: Color,
    val textSecondary: Color,
    /** Primary button and selected-pill fill; also emphasised text. */
    val primary: Color,
    /** Text and icons on [primary]. */
    val onPrimary: Color,
    /** Highlight colour chosen in Appearance: live dot, progress, toggles, icons. */
    val accent: Color,
    /** Text and icons on an [accent] fill. */
    val onAccent: Color,
    val error: Color,
    /** Translucent black behind controls that sit on media. */
    val scrim: Color,
    val isLight: Boolean,
    /** The dark-theme accent, kept so [onMedia] can switch to it. */
    val mediaAccent: Color = accent,
) {
    /** Dark palette with the same accent, for content that always sits on dark media (player, home hero). */
    fun onMedia(): MuxPalette = if (isLight) darkPalette(mediaAccent, amoled = false) else this
}

/**
 * The accent as used in the given theme. Light-theme values are darker so accent text and icons,
 * and white labels on accent fills, reach WCAG AA (4.5:1) on the light background. Dark-theme values
 * reach 4.5:1 on black and take black labels. Checked by SupportTest.
 */
@Suppress("MagicNumber") // hex values are the colour tokens
fun accentColor(accent: Accent, light: Boolean = false): Color = if (light) {
    when (accent) {
        Accent.ORANGE -> Color(0xFFC34C00)
        Accent.AMBER -> Color(0xFF956800)
        Accent.CRIMSON -> Color(0xFFCA3D45)
        Accent.TEAL -> Color(0xFF177F75)
        Accent.VIOLET -> Color(0xFF7456EF)
    }
} else {
    when (accent) {
        Accent.ORANGE -> Color(0xFFE65A00)
        Accent.AMBER -> Color(0xFFE6A100)
        Accent.CRIMSON -> Color(0xFFE4454E)
        Accent.TEAL -> Color(0xFF1FA89B)
        Accent.VIOLET -> Color(0xFF7C5CFF)
    }
}

@Suppress("MagicNumber") // hex values are the colour tokens
private fun darkPalette(accent: Color, amoled: Boolean) = MuxPalette(
    background = Color(0xFF000000),
    surface = if (amoled) Color(0xFF000000) else Color(0xFF111114),
    raised = if (amoled) Color(0xFF0D0D10) else Color(0xFF1B1B20),
    outline = if (amoled) Color(0xFF1A1A1F) else Color(0xFF2A2A31),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFA4A4AC),
    primary = Color(0xFFFFF7E8),
    onPrimary = Color(0xFF000000),
    accent = accent,
    onAccent = Color.Black,
    error = Color(0xFFFF6B5E),
    scrim = Color(0xB3000000),
    isLight = false,
)

@Suppress("MagicNumber") // hex values are the colour tokens
private fun lightPalette(accent: Color, mediaAccent: Color) = MuxPalette(
    background = Color(0xFFFAF7F2),
    surface = Color(0xFFFFFFFF),
    raised = Color(0xFFF0EBE3),
    outline = Color(0xFFE2DBD0),
    textPrimary = Color(0xFF17130E),
    textSecondary = Color(0xFF6B655C),
    primary = Color(0xFF1C1813),
    onPrimary = Color(0xFFFAF7F2),
    accent = accent,
    onAccent = Color.White,
    error = Color(0xFFC0392B),
    scrim = Color(0xB3000000), // scrims sit on media, which is dark in either theme
    isLight = true,
    mediaAccent = mediaAccent,
)

val LocalMux = staticCompositionLocalOf { darkPalette(accentColor(Accent.ORANGE), amoled = false) }
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Themed colour roles and the reduced-motion flag, read from composition. */
object MuxTheme {
    val reducedMotion: Boolean @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
    val isLight: Boolean @Composable @ReadOnlyComposable get() = LocalMux.current.isLight
    val Background: Color @Composable @ReadOnlyComposable get() = LocalMux.current.background
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalMux.current.surface
    val Raised: Color @Composable @ReadOnlyComposable get() = LocalMux.current.raised
    val Outline: Color @Composable @ReadOnlyComposable get() = LocalMux.current.outline
    val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalMux.current.textPrimary
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalMux.current.textSecondary
    val Primary: Color @Composable @ReadOnlyComposable get() = LocalMux.current.primary
    val OnPrimary: Color @Composable @ReadOnlyComposable get() = LocalMux.current.onPrimary
    val Accent: Color @Composable @ReadOnlyComposable get() = LocalMux.current.accent
    val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalMux.current.onAccent
    val Error: Color @Composable @ReadOnlyComposable get() = LocalMux.current.error
    val Scrim: Color @Composable @ReadOnlyComposable get() = LocalMux.current.scrim
}

/** Renders [content] with the dark palette, for UI drawn over video or artwork. */
@Composable
fun OnMedia(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMux provides LocalMux.current.onMedia(), content = content)
}

/** Motion tokens: short, one easing curve, and no motion at all when the user asked for less. */
object Motion {
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val SHORT = 180
    const val MEDIUM = 260

    fun <T> spec(durationMs: Int, reduced: Boolean): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(durationMs, easing = Emphasized)
}

/** Uppercase section label above rows. */
val SectionLabel = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)

private fun scheme(p: MuxPalette) = (if (p.isLight) lightColorScheme() else darkColorScheme()).copy(
    primary = p.primary,
    onPrimary = p.onPrimary,
    secondary = p.accent,
    onSecondary = p.onAccent,
    tertiary = p.accent,
    onTertiary = p.onAccent,
    background = p.background,
    onBackground = p.textPrimary,
    surface = p.background,
    onSurface = p.textPrimary,
    surfaceVariant = p.raised,
    onSurfaceVariant = p.textSecondary,
    surfaceContainerLowest = p.background,
    surfaceContainerLow = p.surface,
    surfaceContainer = p.surface,
    surfaceContainerHigh = p.raised,
    surfaceContainerHighest = p.outline,
    outline = p.outline,
    outlineVariant = p.outline,
    error = p.error,
    onError = p.onPrimary,
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

private val shapes = Shapes(small = RoundedCornerShape(12.dp), medium = Mux.CardShape, large = Mux.HeroShape)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun MuxlumeTheme(
    theme: ThemeMode = ThemeMode.DARK,
    amoled: Boolean = false,
    accent: Accent = Accent.ORANGE,
    dynamicColor: Boolean = false,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dark = when (theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val palette = remember(context, dark, amoled, accent, dynamicColor) {
        val dynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        // Material You takes the matching system scheme: the dark one's primary is a pastel that would
        // be unreadable on the light background.
        val darkAccent = if (dynamic) dynamicDarkColorScheme(context).primary else accentColor(accent, light = false)
        if (dark) {
            darkPalette(darkAccent, amoled)
        } else {
            lightPalette(if (dynamic) dynamicLightColorScheme(context).primary else accentColor(accent, light = true), darkAccent)
        }
    }

    val systemOff = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // Status- and navigation-bar icons follow the theme. The context can be a wrapper.
            val window = view.context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = palette.isLight
                isAppearanceLightNavigationBars = palette.isLight
            }
        }
    }

    CompositionLocalProvider(LocalMux provides palette, LocalReducedMotion provides (reduceMotion || systemOff)) {
        MaterialTheme(colorScheme = scheme(palette), typography = typography, shapes = shapes, content = content)
    }
}
