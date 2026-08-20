package com.devicelab.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * Type.
 *
 * No bundled font. The system's own family is used at every size, which keeps the APK
 * small, matches whatever the user's device and accessibility settings have chosen, and
 * respects a user who has set a different system font. A bundled display face would look
 * more designed and would ignore all of that.
 *
 * Section 22 asks for large typography and clean spacing, so display and headline sizes
 * are generous and tracking is tightened at the top of the scale, where default tracking
 * makes large text look loose.
 *
 * Every size is in sp, so all of it scales with the user's font-size setting as Section
 * 27 requires. Nothing here is dp.
 */
private val Display = FontFamily.Default
private val Body = FontFamily.Default

/**
 * The face for values.
 *
 * Figures are compared column-to-column constantly in this app -- resolutions, byte
 * counts, frequencies, version numbers -- and proportional digits make that harder than
 * it needs to be. Monospace is a setting rather than a rule because it is wider, and on a
 * narrow screen a long GL renderer string benefits more from the space than from the
 * alignment.
 */
val MonoValue = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 13.5.sp,
    lineHeight = 19.sp,
)

val ProportionalValue = TextStyle(
    fontFamily = Body,
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
    lineHeight = 19.sp,
)

/** A number set apart, as in the scorecard cards and the compare counters. */
val MetricStyle = TextStyle(
    fontFamily = Display,
    fontWeight = FontWeight.SemiBold,
    fontSize = 26.sp,
    lineHeight = 30.sp,
    letterSpacing = (-0.5).sp,
    textAlign = TextAlign.Start,
)

val DeviceLabTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 44.sp,
        lineHeight = 50.sp, letterSpacing = (-1.2).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 34.sp,
        lineHeight = 40.sp, letterSpacing = (-0.8).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 28.sp,
        lineHeight = 34.sp, letterSpacing = (-0.5).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 26.sp,
        lineHeight = 32.sp, letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 22.sp,
        lineHeight = 28.sp, letterSpacing = (-0.3).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 19.sp,
        lineHeight = 25.sp, letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
        lineHeight = 23.sp, letterSpacing = (-0.1).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 13.5.sp,
        lineHeight = 19.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 12.5.sp,
        lineHeight = 17.5.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 13.5.sp,
        lineHeight = 18.sp, letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 11.5.sp,
        lineHeight = 15.sp, letterSpacing = 0.3.sp,
    ),
    // The overline for section captions: uppercase, tracked out, small. Wide tracking is
    // what makes 10.5sp uppercase legible rather than cramped.
    labelSmall = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp,
        lineHeight = 14.sp, letterSpacing = 0.9.sp,
    ),
)
