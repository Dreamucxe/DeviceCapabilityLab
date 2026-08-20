package com.devicelab

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * The instrumentation runner, declared in `defaultConfig.testInstrumentationRunner`.
 *
 * Instrumented tests cannot run under [DeviceLabApplication]: `@HiltAndroidApp` builds a
 * fixed component, and a test that wants to swap a detector for a fake -- or simply to use
 * `@HiltAndroidTest` at all -- needs the component Hilt generates for tests instead. This
 * runner substitutes [HiltTestApplication] at the one point where the application class is
 * chosen, which is the whole of what it exists to do.
 *
 * Nothing here changes the app: the substitution applies only to the instrumentation
 * process, and the release APK never contains this class.
 */
class HiltTestRunner : AndroidJUnitRunner() {

    override fun newApplication(
        classLoader: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(classLoader, HiltTestApplication::class.java.name, context)
}
