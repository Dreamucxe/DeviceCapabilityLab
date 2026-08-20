package com.devicelab.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * How long a screen's state stays alive after its last collector goes away.
 *
 * Long enough to cover a configuration change -- a rotation tears the composition down and
 * rebuilds it, and without a grace period the flow would restart and the screen would
 * rebuild its state from scratch on every rotation. Short enough that a screen the user has
 * navigated away from stops recomputing.
 */
private const val STOP_TIMEOUT_MILLIS = 5_000L

/**
 * Shares a derived flow as screen state.
 *
 * Every screen's state is a `combine` of the scan, the user's settings and some local UI
 * state, and each needs the same sharing policy. Naming it once means a screen cannot
 * accidentally get `Eagerly` (and keep recomputing in the background) or a zero timeout
 * (and rebuild on every rotation).
 */
fun <T> Flow<T>.asUiState(scope: CoroutineScope, initial: T): StateFlow<T> =
    stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), initial)
