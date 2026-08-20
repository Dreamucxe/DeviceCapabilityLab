package com.devicelab.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.devicelab.ui.theme.LocalReduceMotion

/**
 * Motion, in one place.
 *
 * Section 23 asks for subtle animation that stays smooth on low-end devices, and Section
 * 27 for a reduced-motion path. Both are satisfied by routing every animation through
 * these helpers: when reduced motion is on, each returns [snap], so the same composables
 * animate or do not without any of them containing an `if`.
 *
 * The durations are short on purpose -- 180ms for a state change, 240ms for an
 * expansion. Anything longer is felt as lag on a mid-range device rather than as polish,
 * and this app's animations are all in service of a value appearing, which the user is
 * waiting to read.
 *
 * Nothing here animates continuously. There is no shimmer, no pulsing and no infinite
 * loop anywhere in the app: a repeating animation costs a recomposition and a frame every
 * frame for as long as it is on screen, which on the low-end hardware this app is most
 * useful for is a real cost for decoration.
 */
object Motion {

    /** Material's standard easing: quick to leave, gentle to settle. */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Decelerate: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

    const val FAST = 140
    const val NORMAL = 180
    const val EXPAND = 240

    /** For colour and alpha changes. */
    @Composable
    @ReadOnlyComposable
    fun <T> fade(): FiniteAnimationSpec<T> =
        if (LocalReduceMotion.current) snap() else tween(NORMAL, easing = Standard)

    /** For size changes -- an expanding card, a growing list. */
    @Composable
    @ReadOnlyComposable
    fun <T> resize(): FiniteAnimationSpec<T> =
        if (LocalReduceMotion.current) snap() else tween(EXPAND, easing = Standard)

    /**
     * For a control that moves: the navigation pill's indicator, a chevron's rotation.
     *
     * A spring rather than a tween, because these follow a touch and a spring's
     * overshoot-free settle reads as physical. Low stiffness would be slow, so it is
     * medium-low with no bounce.
     */
    @Composable
    @ReadOnlyComposable
    fun <T> move(): FiniteAnimationSpec<T> = if (LocalReduceMotion.current) {
        snap()
    } else {
        spring(dampingRatio = 1f, stiffness = 700f)
    }

    /** For a progress indicator's value, which should never jump. */
    @Composable
    @ReadOnlyComposable
    fun progress(): FiniteAnimationSpec<Float> =
        if (LocalReduceMotion.current) snap() else tween(320, easing = Decelerate)
}
