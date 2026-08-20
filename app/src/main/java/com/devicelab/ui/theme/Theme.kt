package com.devicelab.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.devicelab.core.model.Support
import com.devicelab.data.settings.ThemeMode

private val DarkScheme = darkColorScheme(
    primary = Cyan300,
    onPrimary = Ink0,
    primaryContainer = Cyan900,
    onPrimaryContainer = Cyan200,
    secondary = Violet400,
    onSecondary = Color.White,
    secondaryContainer = Violet900,
    onSecondaryContainer = Violet200,
    tertiary = Teal400,
    onTertiary = Ink0,
    tertiaryContainer = Teal900,
    onTertiaryContainer = Teal200,
    background = Ink1,
    onBackground = Slate100,
    surface = Ink1,
    onSurface = Slate100,
    surfaceVariant = Ink4,
    onSurfaceVariant = Slate400,
    surfaceContainerLowest = Ink0,
    surfaceContainerLow = Ink2,
    surfaceContainer = Ink3,
    surfaceContainerHigh = Ink4,
    surfaceContainerHighest = Ink5,
    outline = Ink5,
    outlineVariant = Ink4,
    error = Color(0xFFF87171),
    onError = Ink0,
    errorContainer = Color(0xFF5B1616),
    onErrorContainer = Color(0xFFFECACA),
    inverseSurface = Slate100,
    inverseOnSurface = Ink1,
    scrim = Color(0xCC000000),
)

private val LightScheme = lightColorScheme(
    primary = Cyan700,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7EEFB),
    onPrimaryContainer = Cyan900,
    secondary = Violet700,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E1FE),
    onSecondaryContainer = Violet900,
    tertiary = Teal700,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD3F5EF),
    onTertiaryContainer = Teal900,
    background = Paper0,
    onBackground = Slate900,
    surface = Paper0,
    onSurface = Slate900,
    surfaceVariant = Paper2,
    onSurfaceVariant = Slate600,
    surfaceContainerLowest = Paper0,
    surfaceContainerLow = Paper1,
    surfaceContainer = Paper2,
    surfaceContainerHigh = Paper3,
    surfaceContainerHighest = Paper4,
    outline = Paper4,
    outlineVariant = Paper3,
    error = Color(0xFFB91C1C),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    inverseSurface = Slate800,
    inverseOnSurface = Paper1,
    scrim = Color(0x99000000),
)

val LocalStatusColors: ProvidableCompositionLocal<StatusColors> =
    staticCompositionLocalOf { StatusColors.Dark }

/**
 * Whether animations should be suppressed.
 *
 * Read from the user's setting and provided here rather than passed down, because
 * animation decisions are made deep in leaf composables -- an expanding card, a progress
 * bar, a list item's enter transition -- and threading a boolean through every one of
 * them would be a parameter on almost every function in the UI.
 */
val LocalReduceMotion: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/** Whether values are set in a monospaced face. */
val LocalMonospaceValues: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { true }

/** Whether each row shows the API that answered it. */
val LocalShowProvenance: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { true }

/** Spacing scale. One place, so the layout stays on a rhythm. */
object Spacing {
    val hairline: Dp = 1.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 28.dp
    val xxxl: Dp = 40.dp

    /**
     * Bottom padding that keeps the last list item clear of the floating navigation.
     *
     * The pill floats over the content rather than displacing it, so every scrollable
     * surface has to reserve this much room or the final row is permanently unreachable.
     */
    val navigationClearance: Dp = 104.dp

    /** The minimum touch target Section 27 requires. */
    val minTouchTarget: Dp = 48.dp
}

/** Corner radii. Generously rounded, as Section 22 asks. */
object Radii {
    val card: Dp = 20.dp
    val cardInner: Dp = 14.dp
    val chip: Dp = 999.dp
    val pill: Dp = 32.dp
    val sheet: Dp = 28.dp
}

/** The colour for a status, from the fixed status palette rather than the scheme. */
@Composable
@ReadOnlyComposable
fun colorFor(support: Support): Color {
    val status = LocalStatusColors.current
    return when (support) {
        Support.SUPPORTED -> status.supported
        Support.PARTIAL -> status.partial
        Support.UNSUPPORTED -> status.unsupported
        Support.NOT_EXPOSED -> status.notExposed
        Support.UNKNOWN -> status.unknown
        Support.INFORMATIONAL -> status.informational
    }
}

/**
 * The app theme.
 *
 * Dark is the default, per Section 22. Dynamic colour is applied when the user has it on
 * and the device is Android 12 or newer, which is the only version range where
 * [dynamicDarkColorScheme] exists -- below it the app's own palette is used, which is why
 * that palette had to be designed rather than derived.
 *
 * Status colours are provided separately and are never dynamic. See [StatusColors] for
 * why: a wallpaper-derived scheme cannot be trusted to keep "supported" and
 * "unsupported" distinguishable, and that distinction is the one thing this app cannot
 * get wrong.
 */
@Composable
fun DeviceLabTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    dynamicColor: Boolean = true,
    reduceMotion: Boolean = false,
    monospaceValues: Boolean = true,
    showProvenance: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val scheme = when {
        dynamicColor && dynamicAvailable && dark -> dynamicDarkColorScheme(context)
        dynamicColor && dynamicAvailable -> dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }

    CompositionLocalProvider(
        LocalStatusColors provides if (dark) StatusColors.Dark else StatusColors.Light,
        LocalReduceMotion provides reduceMotion,
        LocalMonospaceValues provides monospaceValues,
        LocalShowProvenance provides showProvenance,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = DeviceLabTypography,
            content = content,
        )
    }
}
