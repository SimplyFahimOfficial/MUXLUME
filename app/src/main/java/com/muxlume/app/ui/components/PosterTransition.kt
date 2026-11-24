package com.muxlume.app.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.muxlume.app.ui.theme.Motion

/**
 * What a poster needs to fly into its detail page: the shared-transition scope of the navigation host and the visibility scope of the
 * route it is drawn in. Provided by the routes that take part; null elsewhere, and with reduced motion nothing flies.
 */
class PosterTransition(
    internal val shared: SharedTransitionScope,
    internal val visibility: AnimatedVisibilityScope,
    internal val motion: Motion,
)

val LocalPosterTransition = staticCompositionLocalOf<PosterTransition?> { null }

/**
 * Marks the poster artwork of an item (in a grid) or the hero of its detail page, so the tapped poster grows into the hero and back.
 * The page marks its hero only when its route says it was opened from a poster ([detail] with [fromPoster]); a page opened from
 * Home, Search or Library, a poster scrolled out of the grid, or reduced motion leave the screens to the plain navigation transition.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedPoster(sourceId: Long, stableKey: String, detail: Boolean = false, fromPoster: Boolean = true): Modifier {
    val t = LocalPosterTransition.current ?: return this
    if (t.motion.reduced || (detail && !fromPoster)) return this
    return with(t.shared) {
        sharedBounds(
            rememberSharedContentState("poster|$sourceId|$stableKey"),
            t.visibility,
            // The page's hero fades over the poster while it grows; the poster side keeps its own pixels until the hero covers it.
            enter = if (detail) fadeIn(t.motion.effects()) else EnterTransition.None,
            exit = if (detail) fadeOut(t.motion.effects()) else ExitTransition.None,
            boundsTransform = { _, _ -> t.motion.spatial() },
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.Crop, Alignment.Center),
        )
    }
}
