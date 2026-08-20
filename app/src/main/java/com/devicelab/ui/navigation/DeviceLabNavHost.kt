package com.devicelab.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.devicelab.ui.capabilities.CapabilitiesScreen
import com.devicelab.ui.components.Motion
import com.devicelab.ui.dashboard.DashboardScreen
import com.devicelab.ui.export.ExportSheet
import com.devicelab.ui.hardware.HardwareScreen
import com.devicelab.ui.hardware.LabScreen
import com.devicelab.ui.history.CompareScreen
import com.devicelab.ui.history.HistoryScreen
import com.devicelab.ui.history.SnapshotScreen
import com.devicelab.ui.settings.SettingsScreen
import com.devicelab.ui.theme.LocalReduceMotion

/**
 * The whole app below the theme: five tabs, four detail screens, one sheet.
 *
 * Navigation state lives in the [NavHostController] rather than in a ViewModel, so process
 * death and a configuration change both restore the back stack for free. The tab a detail
 * screen belongs to is derived from the route by [Tab.forRoute] instead of being tracked
 * separately -- one source of truth, and no way for the highlighted tab to disagree with
 * what is on screen.
 *
 * The pill is drawn over the [NavHost] rather than beside it in a `Scaffold`. Section 22 asks
 * for floating navigation, and a floating bar that displaces content is not floating; the
 * bottom clearance every screen reserves is what keeps the last row reachable.
 */
@Composable
fun DeviceLabApp(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val currentTab = Tab.forRoute(route)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        DeviceLabNavHost(navController = navController)

        // Absent on a detail screen, which has a back affordance of its own and no
        // unambiguous answer to what tapping "Hardware" should mean while a lab is open.
        if (currentTab != null) {
            FloatingNavPill(
                selected = currentTab,
                onSelect = { tab -> navController.selectTab(tab, currentTab) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun DeviceLabNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    // Read once outside the transition lambdas, which are not composable. Changing the
    // setting recomposes the host and rebuilds them.
    val reduceMotion = LocalReduceMotion.current

    NavHost(
        navController = navController,
        startDestination = Routes.DASHBOARD,
        modifier = modifier.fillMaxSize(),
        enterTransition = { enter(reduceMotion, forward = true) },
        exitTransition = { exit(reduceMotion, forward = true) },
        popEnterTransition = { enter(reduceMotion, forward = false) },
        popExitTransition = { exit(reduceMotion, forward = false) },
    ) {
        composable(Routes.DASHBOARD) {
            // The export sheet belongs to the dashboard because that is where the action
            // is, and its state is saveable so a rotation with the sheet open keeps it open.
            var showExport by rememberSaveable { mutableStateOf(false) }
            DashboardScreen(
                onOpenLab = { lab -> navController.push(Routes.lab(lab)) },
                onOpenExport = { showExport = true },
            )
            if (showExport) {
                ExportSheet(onDismiss = { showExport = false })
            }
        }

        composable(Routes.CAPABILITIES) {
            CapabilitiesScreen()
        }

        composable(Routes.HARDWARE) {
            HardwareScreen(onOpenLab = { lab -> navController.push(Routes.lab(lab)) })
        }

        composable(Routes.HISTORY) {
            HistoryScreen(
                onCompare = { left, right -> navController.push(Routes.compare(left, right)) },
                onCompareWithLive = { id -> navController.push(Routes.compareWithLive(id)) },
                onOpenSnapshot = { id -> navController.push(Routes.snapshot(id)) },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen()
        }

        // Detail screens. Every argument is declared as a string even where it names a
        // number: the ViewModels parse them and treat an unparseable value as "not found",
        // which is a screen that explains itself rather than a crash inside the navigation
        // library on a restored back stack from an older build.
        composable(
            route = Routes.LAB_PATTERN,
            arguments = listOf(navArgument(Routes.ARG_LAB) { type = NavType.StringType }),
        ) {
            LabScreen(onBack = navController::popIfPossible)
        }

        composable(
            route = Routes.SNAPSHOT_PATTERN,
            arguments = listOf(navArgument(Routes.ARG_SNAPSHOT) { type = NavType.StringType }),
        ) {
            SnapshotScreen(onBack = navController::popIfPossible)
        }

        composable(
            route = Routes.COMPARE_PATTERN,
            arguments = listOf(
                navArgument(Routes.ARG_LEFT) { type = NavType.StringType },
                navArgument(Routes.ARG_RIGHT) { type = NavType.StringType },
            ),
        ) {
            CompareScreen(onBack = navController::popIfPossible)
        }
    }
}

/**
 * Moves to a tab.
 *
 * Tapping the tab already selected pops back to its root instead of pushing a second copy,
 * which is how a reader gets out of a lab report they navigated into. Moving between tabs
 * saves the state of the one being left and restores the state of the one being entered, so
 * a search typed into the capability matrix survives a trip to the dashboard.
 */
private fun NavHostController.selectTab(tab: Tab, currentTab: Tab?) {
    if (tab == currentTab) {
        popBackStack(tab.route, inclusive = false)
        return
    }
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Pushes a detail screen, ignoring a second tap on the same row. */
private fun NavHostController.push(route: String) {
    navigate(route) { launchSingleTop = true }
}

/**
 * Back, without leaving the app.
 *
 * A detail screen is only ever reached from a tab, so there is always something to pop --
 * except on the frame after a pop has already been handled, which a fast double tap on the
 * back affordance produces. `popBackStack` returning false there is the correct no-op.
 */
private fun NavHostController.popIfPossible() {
    popBackStack()
}

/**
 * Detail screens slide in from the trailing edge; tabs cross-fade.
 *
 * A tab change is a change of subject and has no spatial relationship to model, so a slide
 * would be inventing one. A detail screen is a step deeper into the same subject, which is
 * exactly what a slide reads as.
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(
    reduceMotion: Boolean,
    forward: Boolean,
): EnterTransition {
    if (reduceMotion) return EnterTransition.None
    val target = targetState.destination.route
    val initial = initialState.destination.route
    return if (Routes.isDetail(target) || Routes.isDetail(initial)) {
        slideIntoContainer(
            towards = if (forward) {
                AnimatedContentTransitionScope.SlideDirection.Start
            } else {
                AnimatedContentTransitionScope.SlideDirection.End
            },
            animationSpec = tween(Motion.EXPAND, easing = Motion.Standard),
        ) + fadeIn(tween(Motion.NORMAL))
    } else {
        fadeIn(tween(Motion.NORMAL, easing = Motion.Standard))
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(
    reduceMotion: Boolean,
    forward: Boolean,
): ExitTransition {
    if (reduceMotion) return ExitTransition.None
    val target = targetState.destination.route
    val initial = initialState.destination.route
    return if (Routes.isDetail(target) || Routes.isDetail(initial)) {
        slideOutOfContainer(
            towards = if (forward) {
                AnimatedContentTransitionScope.SlideDirection.Start
            } else {
                AnimatedContentTransitionScope.SlideDirection.End
            },
            animationSpec = tween(Motion.EXPAND, easing = Motion.Standard),
        ) + fadeOut(tween(Motion.NORMAL))
    } else {
        fadeOut(tween(Motion.NORMAL, easing = Motion.Standard))
    }
}
