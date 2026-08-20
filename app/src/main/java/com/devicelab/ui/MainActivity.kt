package com.devicelab.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devicelab.ui.navigation.DeviceLabApp
import com.devicelab.ui.theme.DeviceLabTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The only activity.
 *
 * Everything above the theme happens here and nothing else does: the splash screen is
 * installed before `setContent` (it has to be, the library replaces the window's theme), the
 * window is taken edge-to-edge, and the user's presentation settings are read once and handed
 * to [DeviceLabTheme]. Navigation, state and every screen live in Compose below.
 *
 * The activity handles configuration changes itself -- the manifest lists `orientation`,
 * `screenSize`, `uiMode`, `fontScale` and the rest -- so a rotation or a font-size change does
 * not restart it. That matters here more than in most apps: a full scan opens the camera
 * service, instantiates DRM plugins and enumerates every codec on the device, and while the
 * scan is application-scoped and would survive a recreation, the recreation itself would still
 * throw away every fold, filter and scroll position mid-read.
 *
 * The splash is held until the settings have actually been read, rather than for a fixed time.
 * Without that, a user who chose the light theme gets one dark frame -- the defaults the
 * settings flow starts from -- before the real choice arrives from disk. The condition is
 * bounded by [AppViewModel.settingsLoaded], which resolves even when the preferences file is
 * unreadable, so there is no path where the splash stays up.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Drawn behind the system bars. Every scroll container applies the status-bar inset
        // itself and the navigation pill insets from the navigation bar, so the content is
        // never underneath either -- see ScreenScaffold and FloatingNavPill.
        enableEdgeToEdge()

        splash.setKeepOnScreenCondition { !viewModel.settingsLoaded.value }

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            DeviceLabTheme(
                themeMode = settings.theme,
                dynamicColor = settings.dynamicColor,
                reduceMotion = settings.reduceMotion,
                monospaceValues = settings.monospaceValues,
                showProvenance = settings.showProvenance,
            ) {
                DeviceLabApp()
            }
        }
    }
}
