package com.muxlume.app.ui.components

import androidx.compose.ui.res.stringResource
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import com.muxlume.app.R
import com.muxlume.app.ui.theme.Motion
import com.muxlume.app.ui.theme.Mux
import com.muxlume.app.ui.theme.MuxTheme
import com.muxlume.app.ui.theme.SectionLabel

/** The brand mark (screen, play triangle, underline) from the supplied logo artwork. */
@Composable
fun MuxLogo(modifier: Modifier = Modifier, size: Dp = 32.dp) {
    // The cream frame would vanish on the light background, so light mode uses a dark-framed copy.
    val res = if (MuxTheme.isLight) R.drawable.brand_logo_light else R.drawable.brand_logo
    Image(painterResource(res), contentDescription = null, modifier = modifier.size(size))
}

@Composable
fun Wordmark(modifier: Modifier = Modifier, logoSize: Dp = 30.dp) {
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = "MUXLUME" }, verticalAlignment = Alignment.CenterVertically) {
        MuxLogo(size = logoSize)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.components_muxlume), fontSize = (logoSize.value * 0.62f).sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, color = MuxTheme.TextPrimary)
    }
}

/** Gentle press feedback: scales to 97% while pressed (none with reduced motion). */
@Composable
fun Modifier.pressable(onClick: () -> Unit, label: String? = null, role: Role = Role.Button, enabled: Boolean = true): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val reduced = MuxTheme.reducedMotion
    val scale by animateFloatAsState(if (pressed && !reduced) 0.97f else 1f, Motion.spec(Motion.SHORT, reduced), label = "press")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), enabled = enabled, onClickLabel = label, role = role, onClick = onClick)
}

/**
 * Provider artwork, or an abstract placeholder built from the title (never stock posters).
 * Loading and failure show the same placeholder so the layout never jumps.
 */
@Composable
fun Artwork(url: String?, title: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, monogramSize: Int = 28) {
    Box(modifier.background(MuxTheme.Raised)) {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = null,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
            loading = { Placeholder(title, monogramSize) },
            error = { Placeholder(title, monogramSize) },
        )
    }
}

@Composable
private fun Placeholder(title: String, size: Int) {
    val initials = title.split(' ', '-', '_').filter { it.firstOrNull()?.isLetterOrDigit() == true }.take(2).joinToString("") { it.take(1) }.uppercase()
    Box(Modifier.fillMaxSize().background(MuxTheme.Raised), contentAlignment = Alignment.Center) {
        Text(initials.ifEmpty { "•" }, fontSize = size.sp, fontWeight = FontWeight.Black, color = MuxTheme.Primary.copy(alpha = 0.55f), letterSpacing = (-1).sp)
        Box(Modifier.align(Alignment.BottomStart).padding(12.dp).width(22.dp).height(4.dp).clip(Mux.PillShape).background(MuxTheme.Accent))
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val reduced = MuxTheme.reducedMotion
    val bg by animateColorAsState(if (selected) MuxTheme.Primary else MuxTheme.Raised, Motion.spec(Motion.SHORT, reduced), label = "pill")
    val fg by animateColorAsState(if (selected) MuxTheme.OnPrimary else MuxTheme.TextPrimary, Motion.spec(Motion.SHORT, reduced), label = "pillText")
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(Mux.PillShape)
            .background(bg)
            .pressable(onClick, role = Role.Tab)
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PillRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier, contentPadding = PaddingValues(horizontal = Mux.ScreenPadding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options.size) { i -> Pill(options[i], i == selected, { onSelect(i) }) }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    ButtonBase(text, onClick, modifier, icon, enabled, MuxTheme.Primary, MuxTheme.OnPrimary)
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    ButtonBase(text, onClick, modifier.border(1.dp, MuxTheme.Outline, Mux.PillShape), icon, enabled, MuxTheme.Raised, MuxTheme.TextPrimary)
}

@Composable
private fun ButtonBase(text: String, onClick: () -> Unit, modifier: Modifier, icon: ImageVector?, enabled: Boolean, bg: Color, fg: Color) {
    Row(
        modifier
            .heightIn(min = 52.dp)
            .clip(Mux.PillShape)
            .background(if (enabled) bg else MuxTheme.Surface)
            .pressable(onClick, enabled = enabled) // disabled buttons stay announced as buttons
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = if (enabled) fg else MuxTheme.TextSecondary, style = MaterialTheme.typography.labelLarge, fontSize = 16.sp)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = Mux.ScreenPadding), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), style = SectionLabel, color = MuxTheme.TextSecondary, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action, color = MuxTheme.Primary, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(Mux.PillShape).pressable(onAction).padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(Mux.ScreenPadding), horizontalAlignment = Alignment.Start) {
        Box(Modifier.width(36.dp).height(5.dp).clip(Mux.PillShape).background(MuxTheme.Accent))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MuxTheme.TextPrimary)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MuxTheme.TextSecondary)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            PrimaryButton(action, onAction)
        }
    }
}

/** 2:3 poster with title below. */
@Composable
fun PosterCard(title: String, artwork: String?, onClick: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, width: Dp? = 132.dp) {
    Column(modifier.then(if (width != null) Modifier.width(width) else Modifier).pressable(onClick, label = title).semantics(mergeDescendants = true) {}) {
        Artwork(artwork, title, Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(Mux.CardShape))
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = MuxTheme.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary, maxLines = 1)
    }
}

/** 16:9 card with optional progress, for Continue watching and channels. */
@Composable
fun WideCard(title: String, subtitle: String?, artwork: String?, onClick: () -> Unit, modifier: Modifier = Modifier, progress: Float? = null, fit: Boolean = false) {
    Column(modifier.width(240.dp).pressable(onClick, label = title).semantics(mergeDescendants = true) {}) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(Mux.CardShape)) {
            Artwork(artwork, title, Modifier.fillMaxSize(), contentScale = if (fit) ContentScale.Fit else ContentScale.Crop)
            if (progress != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(MuxTheme.Scrim)) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(MuxTheme.Accent))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = MuxTheme.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Live badge: orange dot + LIVE. */
@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    Row(modifier.clip(Mux.PillShape).background(MuxTheme.Scrim).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(Mux.PillShape).background(MuxTheme.Accent))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.components_live), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, letterSpacing = 1.sp) // always on the dark scrim
    }
}

/** A setting row with a switch; the whole row is the touch target. */
@Composable
fun Toggle(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(horizontal = Mux.ScreenPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MuxTheme.TextPrimary)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MuxTheme.TextSecondary)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked, null, colors = SwitchDefaults.colors(checkedTrackColor = MuxTheme.Accent, checkedThumbColor = MuxTheme.Primary))
    }
}
