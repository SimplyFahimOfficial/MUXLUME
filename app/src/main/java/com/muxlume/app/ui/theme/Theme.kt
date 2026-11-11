package com.muxlume.app.ui.theme

import android.app.Activity
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

/** Brand constants that don't change with the theme: shapes, spacing, the logo's own orange. */
object Mux {
    /** The logo artwork orange; for brand graphics only, never UI chrome. */
    val LogoOrange = Color(0xFFFD5902)

    val CardShape = RoundedCornerShape(20.dp)
    val HeroShape = RoundedCornerShape(28.dp)
    val PillShape = RoundedCornerShape(50)

    val ScreenPadding = 20.dp
}

/**
 * Semantic colour roles, resolved from the current theme through [LocalMux]. Role names keep their
 * dark-theme meaning: `black` is the screen background, `white` the primary text, `cream` the
 * primary-button fill and `black` its text (so both flip together in light mode).
 */
data class MuxPalette(
    val black: Color,
    val surface: Color,
    val raised: Color,
    val outline: Color,
    val white: Color,
    val cream: Color,
    val orange: Color,
    /** Text and icons on an [orange] fill (black on dark-theme accents, white on light-theme ones). */
    val onOrange: Color,
    val textSecondary: Color,
    val error: Color,
    val scrim: Color,
    val isLight: Boolean,
    /** The dark-theme variant of the accent, used by [onMedia]. */
    val mediaOrange: Color = orange,
) {
    /**
     * A dark variant that keeps the accent, for content that always sits on dark media (the player,
     * the home hero) regardless of the app theme.
     */
    fun onMedia(): MuxPalette = if (!isLight) this else copy(
        black = Color(0xFF000000),
        surface = Color(0xFF111114),
        raised = Color(0xFF1B1B20),
        outline = Color(0xFF2A2A31),
        white = Color(0xFFFFFFFF),
        cream = Color(0xFFFFF7E8),
        textSecondary = Color(0xFFA4A4AC),
        error = Color(0xFFFF6B5E),
        orange = mediaOrange,
        onOrange = Color.Black,
        isLight = false,
    )
}

/**
 * The accent as used in the given theme. Light-theme values are darkened so text, icons and white
 * labels on accent fills all reach WCAG AA (4.5:1) on the light background; dark-theme values pass
 * on black, and their fills take black labels (see [MuxPalette.onOrange]).
 */
@Suppress("MagicNumber") // the hex values are the colour tokens
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

private fun darkPalette(orange: Color, amoled: Boolean) = MuxPalette(
    black = Color(0xFF000000),
    surface = if (amoled) Color(0xFF000000) else Color(0xFF111114),
    raised = if (amoled) Color(0xFF0D0D10) else Color(0xFF1B1B20),
    outline = if (amoled) Color(0xFF1A1A1F) else Color(0xFF2A2A31),
    white = Color(0xFFFFFFFF),
    cream = Color(0xFFFFF7E8),
    orange = orange,
    onOrange = Color.Black,
    textSecondary = Color(0xFFA4A4AC),
    error = Color(0xFFFF6B5E),
    scrim = Color(0xB3000000),
    isLight = false,
)

private fun lightPalette(orange: Color, mediaOrange: Color) = MuxPalette(
    black = Color(0xFFFAF7F2), // warm paper, screen background
    surface = Color(0xFFFFFFFF),
    raised = Color(0xFFF0EBE3),
    outline = Color(0xFFE2DBD0),
    white = Color(0xFF17130E), // primary text / icons
    cream = Color(0xFF1C1813), // primary-button fill (dark); its text uses `black` (light)
    orange = orange,
    onOrange = Color.White,
    textSecondary = Color(0xFF6B655C),
    error = Color(0xFFC0392B),
    scrim = Color(0xB3000000), // overlays sit on media, which is dark in either theme
    isLight = true,
    mediaOrange = mediaOrange,
)

val LocalMux = staticCompositionLocalOf { darkPalette(accentColor(Accent.ORANGE), amoled = false) }
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Themed colour tokens and the reduced-motion flag, read from composition. */
object MuxTheme {
    val reducedMotion: Boolean @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
    val Black: Color @Composable @ReadOnlyComposable get() = LocalMux.current.black
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalMux.current.surface
    val Raised: Color @Composable @ReadOnlyComposable get() = LocalMux.current.raised
    val Outline: Color @Composable @ReadOnlyComposable get() = LocalMux.current.outline
    val White: Color @Composable @ReadOnlyComposable get() = LocalMux.current.white
    val Cream: Color @Composable @ReadOnlyComposable get() = LocalMux.current.cream
    val Orange: Color @Composable @ReadOnlyComposable get() = LocalMux.current.orange
    val OnOrange: Color @Composable @ReadOnlyComposable get() = LocalMux.current.onOrange
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalMux.current.textSecondary
    val Error: Color @Composable @ReadOnlyComposable get() = LocalMux.current.error
    val Scrim: Color @Composable @ReadOnlyComposable get() = LocalMux.current.scrim
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
    primary = p.cream,
    onPrimary = p.black,
    secondary = p.orange,
    onSecondary = p.onOrange,
    tertiary = p.orange,
    background = p.black,
    onBackground = p.white,
    surface = p.black,
    onSurface = p.white,
    surfaceVariant = p.raised,
    onSurfaceVariant = p.textSecondary,
    surfaceContainerLowest = p.black,
    surfaceContainerLow = p.surface,
    surfaceContainer = p.surface,
    surfaceContainerHigh = p.raised,
    surfaceContainerHighest = p.outline,
    outline = p.outline,
    outlineVariant = p.outline,
    error = p.error,
    onError = if (p.isLight) Color.White else Color.Black,
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

private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

private val shapes = Shapes(small = RoundedCornerShape(12.dp), medium = Mux.CardShape, large = Mux.HeroShape)

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
    val dynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // Material You: each theme takes the matching system scheme's primary (the dark one is a light
    // pastel that would be unreadable on the light background).
    val darkOrange = if (dynamic) dynamicDarkColorScheme(context).primary else accentColor(accent, light = false)
    val palette = if (dark) {
        darkPalette(darkOrange, amoled)
    } else {
        lightPalette(if (dynamic) dynamicLightColorScheme(context).primary else accentColor(accent, light = true), darkOrange)
    }

    val systemOff = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // Status/navigation-bar icons follow the theme. The context can be a wrapper, so unwrap it.
            val window = view.context.findActivity()?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = palette.isLight
            controller.isAppearanceLightNavigationBars = palette.isLight
        }
    }

    CompositionLocalProvider(
        LocalMux provides palette,
        LocalReducedMotion provides (reduceMotion || systemOff),
    ) {
        MaterialTheme(colorScheme = scheme(palette), typography = typography, shapes = shapes, content = content)
    }
}
