package com.devicelab.di

import javax.inject.Qualifier

/**
 * The dispatcher for platform inspection and database work.
 *
 * Qualified rather than injected as a bare [kotlinx.coroutines.CoroutineDispatcher] so
 * a test can substitute a deterministic one without also replacing every other
 * dispatcher in the graph.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** The main thread dispatcher, for the rare case a use case needs it explicitly. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

/**
 * A scope that lives as long as the process.
 *
 * The device scan belongs to the app, not to a screen: five tabs read one result, and
 * cancelling it because the user rotated the phone mid-scan would restart the whole
 * thing. A ViewModel scope is the wrong lifetime for that.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
