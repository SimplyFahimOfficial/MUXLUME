package com.muxlume.app.ui.components

import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import com.muxlume.app.R
import com.muxlume.app.ui.theme.MuxTheme

/**
 * The watchlist button's icon: a plus that turns into a tick when [added] becomes true, and back. The first composition shows the state
 * as it is, without animating. With reduced motion the two states are plain drawings.
 */
@OptIn(ExperimentalAnimationGraphicsApi::class)
@Composable
fun watchlistPainter(added: Boolean): Painter =
    if (MuxTheme.reducedMotion) painterResource(if (added) R.drawable.ic_watchlist_added else R.drawable.ic_watchlist_add)
    else rememberAnimatedVectorPainter(AnimatedImageVector.animatedVectorResource(R.drawable.avd_watchlist), added)
