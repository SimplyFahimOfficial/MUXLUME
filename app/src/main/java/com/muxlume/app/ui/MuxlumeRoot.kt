package com.muxlume.app.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.muxlume.app.container
import com.muxlume.app.ui.guide.GuideMappingScreen
import com.muxlume.app.ui.guide.GuideScreen
import com.muxlume.app.ui.guide.GuideSettingsScreen
import com.muxlume.app.ui.library.BackupScreen
import com.muxlume.app.ui.library.HiddenChannelsScreen
import com.muxlume.app.ui.library.ParentalScreen
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
import com.muxlume.app.ui.vod.DetailScreen
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

@Serializable object WelcomeRoute
@Serializable data class AddSourceRoute(val sourceId: Long = -1, val fromOnboarding: Boolean = false)
@Serializable object MainRoute
@Serializable object PlayerRoute
@Serializable object SearchRoute
@Serializable data class DetailRoute(val sourceId: Long, val key: String)
@Serializable object SettingsRoute
@Serializable object AppearanceRoute
@Serializable object SupportRoute
@Serializable object GuideRoute
@Serializable object GuideSettingsRoute
@Serializable object BackupRoute
@Serializable object HiddenChannelsRoute
@Serializable object ParentalRoute
@Serializable data class GuideMappingRoute(val sourceId: Long)
@Serializable object AboutRoute

/** Navigation actions shared by screens. */
class Nav(private val controller: NavHostController) {
    fun player() = controller.navigate(PlayerRoute) { launchSingleTop = true }
    fun search() = controller.navigate(SearchRoute) { launchSingleTop = true }
    fun detail(sourceId: Long, key: String) = controller.navigate(DetailRoute(sourceId, key))
    fun addSource(sourceId: Long = -1) = controller.navigate(AddSourceRoute(sourceId))
    fun settings() = controller.navigate(SettingsRoute)
    fun appearance() = controller.navigate(AppearanceRoute)
    fun support() = controller.navigate(SupportRoute)
    fun guide() = controller.navigate(GuideRoute) { launchSingleTop = true }
    fun guideSettings() = controller.navigate(GuideSettingsRoute)
    fun backup() = controller.navigate(BackupRoute)
    fun hiddenChannels() = controller.navigate(HiddenChannelsRoute)
    fun parental() = controller.navigate(ParentalRoute)
    fun guideMapping(sourceId: Long) = controller.navigate(GuideMappingRoute(sourceId))
    fun about() = controller.navigate(AboutRoute)
    fun back() = controller.popBackStack()
    fun home() = controller.navigate(MainRoute) { popUpTo(0) { inclusive = true } }
}

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
        val reduced = MuxTheme.reducedMotion
        NavHost(
            controller, startDestination = startRoute,
            enterTransition = { if (reduced) EnterTransition.None else forward() },
            exitTransition = { if (reduced) ExitTransition.None else fadeOut(Motion.spec(Motion.SHORT, false)) },
            popEnterTransition = { if (reduced) EnterTransition.None else fadeIn(Motion.spec(Motion.MEDIUM, false)) },
            popExitTransition = { if (reduced) ExitTransition.None else backward() },
        ) {
            composable<WelcomeRoute> { WelcomeScreen(onAdd = { controller.navigate(AddSourceRoute(fromOnboarding = true)) }, onExplore = nav::home) }
            composable<AddSourceRoute> { entry ->
                val route = entry.toRoute<AddSourceRoute>()
                AddSourceScreen(route.sourceId.takeIf { it > 0 }, onDone = { if (route.fromOnboarding) nav.home() else nav.back() }, onBack = { nav.back() })
            }
            composable<MainRoute> { MainScreen(nav) }
            composable<PlayerRoute>(
                enterTransition = { if (reduced) EnterTransition.None else fadeIn(Motion.spec(Motion.MEDIUM, false)) },
                popExitTransition = { if (reduced) ExitTransition.None else fadeOut(Motion.spec(Motion.SHORT, false)) },
            ) { PlayerScreen(onBack = { nav.back() }) }
            composable<SearchRoute> { SearchScreen(nav) }
            composable<DetailRoute> { entry -> entry.toRoute<DetailRoute>().let { DetailScreen(it.sourceId, it.key, nav) } }
            composable<SettingsRoute> { SettingsScreen(nav) }
            composable<AppearanceRoute> { AppearanceScreen(onBack = { nav.back() }) }
            composable<SupportRoute> { SupportScreen(onBack = { nav.back() }) }
            composable<GuideRoute> { GuideScreen(nav) }
            composable<GuideSettingsRoute> { GuideSettingsScreen(nav) }
            composable<BackupRoute> { BackupScreen(nav) }
            composable<HiddenChannelsRoute> { HiddenChannelsScreen(nav) }
            composable<ParentalRoute> { ParentalScreen(nav) }
            composable<GuideMappingRoute> { entry -> GuideMappingScreen(entry.toRoute<GuideMappingRoute>().sourceId, onBack = { nav.back() }) }
            composable<AboutRoute> { AboutScreen(onBack = { nav.back() }) }
        }
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.forward() =
    fadeIn(Motion.spec(Motion.MEDIUM, false)) + slideInHorizontally(Motion.spec(Motion.MEDIUM, false)) { it / 12 }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.backward() =
    fadeOut(Motion.spec(Motion.SHORT, false)) + slideOutHorizontally(Motion.spec(Motion.MEDIUM, false)) { it / 12 }
