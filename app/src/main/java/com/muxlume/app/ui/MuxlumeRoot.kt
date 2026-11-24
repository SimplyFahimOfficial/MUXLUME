package com.muxlume.app.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.muxlume.app.container
import com.muxlume.app.ui.components.LocalPosterTransition
import com.muxlume.app.ui.components.PosterTransition
import com.muxlume.app.ui.guide.GuideMappingScreen
import com.muxlume.app.ui.guide.GuideScreen
import com.muxlume.app.ui.guide.GuideSettingsScreen
import com.muxlume.app.ui.library.BackupScreen
import com.muxlume.app.ui.library.HiddenChannelsScreen
import com.muxlume.app.ui.library.ParentalScreen
import com.muxlume.app.ui.library.RecordingsScreen
import com.muxlume.app.ui.library.AboutScreen
import com.muxlume.app.ui.library.AppearanceScreen
import com.muxlume.app.ui.library.SettingsScreen
import com.muxlume.app.ui.library.SupportScreen
import com.muxlume.app.ui.main.MainScreen
import com.muxlume.app.ui.onboarding.AddSourceScreen
import com.muxlume.app.ui.onboarding.WelcomeScreen
import com.muxlume.app.ui.player.PlayerScreen
import com.muxlume.app.ui.search.SearchScreen
import com.muxlume.app.ui.theme.Motion
import com.muxlume.app.ui.theme.Mux
import com.muxlume.app.ui.theme.MuxTheme
import com.muxlume.app.ui.theme.Speed
import com.muxlume.app.ui.vod.DetailScreen
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

@Serializable object WelcomeRoute
@Serializable data class AddSourceRoute(val sourceId: Long = -1, val fromOnboarding: Boolean = false)
@Serializable object MainRoute
@Serializable object PlayerRoute
@Serializable object SearchRoute
/** [poster]: opened from a Movies or Series poster, which then grows into the page (and back). */
@Serializable data class DetailRoute(val sourceId: Long, val key: String, val poster: Boolean = false)
@Serializable object SettingsRoute
@Serializable object AppearanceRoute
@Serializable object SupportRoute
@Serializable object GuideRoute
@Serializable object GuideSettingsRoute
@Serializable object BackupRoute
@Serializable object HiddenChannelsRoute
@Serializable object ParentalRoute
@Serializable object RecordingsRoute
@Serializable data class GuideMappingRoute(val sourceId: Long)
@Serializable object AboutRoute

/** Navigation actions shared by screens. */
class Nav(private val controller: NavHostController) {
    fun player() = controller.navigate(PlayerRoute) { launchSingleTop = true }
    fun search() = controller.navigate(SearchRoute) { launchSingleTop = true }
    fun detail(sourceId: Long, key: String, poster: Boolean = false) = controller.navigate(DetailRoute(sourceId, key, poster)) { launchSingleTop = true } // a double tap opens one page
    fun addSource(sourceId: Long = -1) = controller.navigate(AddSourceRoute(sourceId))
    fun settings() = controller.navigate(SettingsRoute)
    fun appearance() = controller.navigate(AppearanceRoute)
    fun support() = controller.navigate(SupportRoute)
    fun guide() = controller.navigate(GuideRoute) { launchSingleTop = true }
    fun guideSettings() = controller.navigate(GuideSettingsRoute)
    fun backup() = controller.navigate(BackupRoute)
    fun hiddenChannels() = controller.navigate(HiddenChannelsRoute)
    fun parental() = controller.navigate(ParentalRoute)
    fun recordings() = controller.navigate(RecordingsRoute)
    fun guideMapping(sourceId: Long) = controller.navigate(GuideMappingRoute(sourceId))
    fun about() = controller.navigate(AboutRoute)
    fun back() = controller.popBackStack()
    fun home() = controller.navigate(MainRoute) { popUpTo(0) { inclusive = true } }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MuxlumeRoot() {
    val context = LocalContext.current
    val start by produceState<Any?>(null) {
        val c = context.container
        c.lock.load() // lists need to know whether locked groups apply before the first screen
        val onboarded = c.settings.current().onboardingDone || c.db.sources().all().first().isNotEmpty()
        value = if (onboarded) MainRoute else WelcomeRoute
    }
    Box(Modifier.fillMaxSize().background(MuxTheme.Background)) {
        val startRoute = start ?: return@Box // black splash continuation while settings load (a few ms)
        val controller = rememberNavController()
        val nav = Nav(controller)
        val motion = MuxTheme.motion
        val reduced = motion.reduced
        // The screen underneath stays opaque and still: only the screen on top fades and slides. Two translucent
        // screens at once let the window show through and ghost each other.
        SharedTransitionLayout(Modifier.fillMaxSize()) {
            NavHost(
                controller, startDestination = startRoute,
                enterTransition = { if (reduced) EnterTransition.None else forward(motion) },
                exitTransition = { ExitTransition.KeepUntilTransitionsFinished },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { if (reduced) ExitTransition.None else backward(motion) },
                // Without these the back gesture runs the library default (scale to 70%) and snaps to popExit on release.
                predictivePopEnterTransition = { EnterTransition.None },
                predictivePopExitTransition = { if (reduced) ExitTransition.None else backward(motion) },
            ) {
                composable<WelcomeRoute> { WelcomeScreen(onAdd = { controller.navigate(AddSourceRoute(fromOnboarding = true)) }, onExplore = nav::home) }
                composable<AddSourceRoute> { entry ->
                    val route = entry.toRoute<AddSourceRoute>()
                    AddSourceScreen(route.sourceId.takeIf { it > 0 }, onDone = { if (route.fromOnboarding) nav.home() else nav.back() }, onBack = { nav.back() })
                }
                composable<MainRoute> { PosterRoute(this, motion) { MainScreen(nav) } }
                composable<PlayerRoute>(
                    enterTransition = { if (reduced) EnterTransition.None else fadeIn(motion.effects()) },
                ) { PlayerScreen(onBack = { nav.back() }) }
                composable<SearchRoute> { SearchScreen(nav) }
                composable<DetailRoute> { entry -> PosterRoute(this, motion) { entry.toRoute<DetailRoute>().let { DetailScreen(it.sourceId, it.key, nav, it.poster) } } }
                composable<SettingsRoute> { SettingsScreen(nav) }
                composable<AppearanceRoute> { AppearanceScreen(onBack = { nav.back() }) }
                composable<SupportRoute> { SupportScreen(onBack = { nav.back() }) }
                composable<GuideRoute> { GuideScreen(nav) }
                composable<GuideSettingsRoute> { GuideSettingsScreen(nav) }
                composable<BackupRoute> { BackupScreen(nav) }
                composable<HiddenChannelsRoute> { HiddenChannelsScreen(nav) }
                composable<ParentalRoute> { ParentalScreen(nav) }
                composable<RecordingsRoute> { RecordingsScreen(nav) }
                composable<GuideMappingRoute> { entry -> GuideMappingScreen(entry.toRoute<GuideMappingRoute>().sourceId, onBack = { nav.back() }) }
                composable<AboutRoute> { AboutScreen(onBack = { nav.back() }) }
            }
        }
    }
}

/** Lets the poster grid and the detail page of this route fly one poster between them. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.PosterRoute(visibility: AnimatedVisibilityScope, motion: Motion, content: @Composable () -> Unit) {
    val transition = remember(this, visibility, motion) { PosterTransition(this, visibility, motion) }
    CompositionLocalProvider(LocalPosterTransition provides transition, content = content)
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.forward(motion: Motion) =
    fadeIn(motion.effects()) + slideInHorizontally(motion.spatial()) { it / 12 }

/** Leaving the player only fades, because it has no sibling to slide away from. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.backward(motion: Motion) =
    if (initialState.destination.hasRoute<PlayerRoute>()) fadeOut(motion.effects(Speed.FAST))
    else fadeOut(motion.effects(Speed.FAST)) + slideOutHorizontally(motion.spatial()) { it / 12 }
