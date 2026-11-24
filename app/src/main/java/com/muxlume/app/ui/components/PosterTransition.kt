package com.muxlume.app.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.muxlume.app.ui.theme.Motion

/** The poster a tap is about to open, shared by the grid and the detail page: only that one flies. */
class PosterOpening {
    internal var key: String? = null
}

/**
 * What a poster needs to fly into its detail page: the shared-transition scope of the navigation host and the visibility scope of the
 * route it is drawn in. Provided by the routes that take part; null elsewhere (Home, Search and Library open the page with the normal
 * transition), and with reduced motion nothing flies.
 */
class PosterTransition(
    internal val shared: SharedTransitionScope,
    internal val visibility: AnimatedVisibilityScope,
    internal val motion: Motion,
    private val opening: PosterOpening,
) {
    /** Call when the poster is tapped, before navigating. */
    fun opens(sourceId: Long, stableKey: String) {
        if (!motion.reduced) opening.key = keyOf(sourceId, stableKey)
    }

    internal fun wasTapped(sourceId: Long, stableKey: String): Boolean =
        (opening.key == keyOf(sourceId, stableKey)).also { if (it) opening.key = null }

    internal fun keyOf(sourceId: Long, stableKey: String) = "poster|$sourceId|$stableKey"
}

val LocalPosterTransition = staticCompositionLocalOf<PosterTransition?> { null }

/**
 * Marks the poster artwork of an item (in a grid) or the hero of its detail page, so the tapped poster grows into the hero and back.
 * The page marks its hero only when it was opened from that poster; otherwise (another screen, a poster scrolled out of the grid, reduced
 * motion) nothing is shared and the screens use the plain navigation transition.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedPoster(sourceId: Long, stableKey: String, detail: Boolean = false): Modifier {
    val t = LocalPosterTransition.current ?: return this
    if (t.motion.reduced) return this
    // Decided once, when the page enters: the pop back must fly to the poster as well.
    val flies = !detail || remember { t.wasTapped(sourceId, stableKey) }
    if (!flies) return this
    return with(t.shared) {
        sharedBounds(
            rememberSharedContentState(t.keyOf(sourceId, stableKey)),
            t.visibility,
            // The page's hero fades over the poster while it grows; the poster side keeps its own pixels until the hero covers it.
            enter = if (detail) fadeIn(t.motion.effects()) else EnterTransition.None,
            exit = if (detail) fadeOut(t.motion.effects()) else ExitTransition.None,
            boundsTransform = { _, _ -> t.motion.spatial() },
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.Crop, Alignment.Center),
        )
    }
}
