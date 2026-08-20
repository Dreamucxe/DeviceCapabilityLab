package com.devicelab.ui.navigation

import androidx.annotation.StringRes
import com.devicelab.R
import com.devicelab.core.model.Lab

/**
 * Every place the app can be.
 *
 * Routes are built here rather than spelled out at each call site, so a typo in an argument
 * name is a compile error instead of a silent navigation failure at runtime.
 */
object Routes {
    const val DASHBOARD = "dashboard"
    const val CAPABILITIES = "capabilities"
    const val HARDWARE = "hardware"
    const val HISTORY = "history"
    const val SETTINGS = "settings"

    const val ARG_LAB = "labId"
    const val ARG_SNAPSHOT = "snapshotId"
    const val ARG_LEFT = "left"
    const val ARG_RIGHT = "right"

    /** The id used in place of a saved snapshot to mean "the scan on screen now". */
    const val LIVE = "live"

    const val LAB_PATTERN = "$HARDWARE/{$ARG_LAB}"
    const val SNAPSHOT_PATTERN = "$HISTORY/{$ARG_SNAPSHOT}"
    const val COMPARE_PATTERN = "compare/{$ARG_LEFT}/{$ARG_RIGHT}"

    fun lab(lab: Lab): String = "$HARDWARE/${lab.id}"

    fun snapshot(id: Long): String = "$HISTORY/$id"

    /** Compare two saved snapshots. */
    fun compare(leftId: Long, rightId: Long): String = "compare/$leftId/$rightId"

    /** Compare a saved snapshot against the live scan. */
    fun compareWithLive(savedId: Long): String = "compare/$savedId/$LIVE"

    /**
     * Whether a route is a detail screen pushed over a tab.
     *
     * Two things read this: the navigation pill, which hides on a detail screen because the
     * screen has its own back affordance, and the transition, which slides a detail in from
     * the side and cross-fades between tabs.
     */
    fun isDetail(route: String?): Boolean =
        route == LAB_PATTERN || route == SNAPSHOT_PATTERN || route == COMPARE_PATTERN
}

/**
 * The five tabs of the floating navigation.
 *
 * The glyph is a text character, not a drawable. Five vector icons for five tabs would mean
 * five more files and a dependency on the extended Material icon set (which is several
 * thousand vectors, of which the release build would keep five, but which has to be parsed
 * at build time regardless). These read clearly at 20sp and inherit the tab's colour and
 * the user's font scale.
 */
enum class Tab(
    val route: String,
    @StringRes val titleRes: Int,
    val glyph: String,
) {
    DASHBOARD(Routes.DASHBOARD, R.string.tab_dashboard, "◈"),
    CAPABILITIES(Routes.CAPABILITIES, R.string.tab_capabilities, "▤"),
    HARDWARE(Routes.HARDWARE, R.string.tab_hardware, "◧"),
    HISTORY(Routes.HISTORY, R.string.tab_history, "◔"),
    SETTINGS(Routes.SETTINGS, R.string.tab_settings, "⚙"),
    ;

    companion object {
        /**
         * The tab that owns a route, or null when the route is a detail screen.
         *
         * A lab detail screen is pushed on top of the Hardware tab, so it keeps Hardware
         * selected; comparison is reached from History and keeps History selected. Anything
         * unrecognised selects nothing rather than guessing, which is what makes an
         * unexpected route visibly wrong instead of quietly mis-highlighted.
         */
        fun forRoute(route: String?): Tab? = when {
            route == null -> null
            route == Routes.LAB_PATTERN -> HARDWARE
            route == Routes.SNAPSHOT_PATTERN -> HISTORY
            route == Routes.COMPARE_PATTERN -> HISTORY
            else -> entries.firstOrNull { it.route == route }
        }
    }
}
