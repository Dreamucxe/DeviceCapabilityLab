package com.devicelab.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The app's own palette.
 *
 * Dynamic colour is on by default and takes precedence on Android 12+, so this is what
 * a device without it sees -- and it is the fallback that has to look deliberate rather
 * than like a missing theme.
 *
 * The hues are chosen for the one job the UI has that colour can help with: telling four
 * statuses apart at a glance. Supported is green, partial amber, unsupported red, and
 * both "not exposed" and "unknown" are a desaturated slate. Putting those last two in
 * grey rather than red is a deliberate distinction -- red would read as a failure of the
 * device, when the truth is that nothing was established either way.
 *
 * Contrast was the constraint on every value here, not vibrance. Section 27 requires
 * readable contrast, and status colours appear as small text and 20sp glyphs, which is
 * exactly where an attractive mid-tone fails.
 */

// Brand — a cool instrument blue. Reads as measurement rather than as a warning.
val Cyan200 = Color(0xFF7DD3FC)
val Cyan300 = Color(0xFF38BDF8)
val Cyan400 = Color(0xFF0EA5E9)
val Cyan700 = Color(0xFF0369A1)
val Cyan900 = Color(0xFF0C4A6E)

// Secondary — violet, for selection and the active navigation pill.
val Violet200 = Color(0xFFC4B5FD)
val Violet400 = Color(0xFF8B5CF6)
val Violet700 = Color(0xFF6D28D9)
val Violet900 = Color(0xFF3B1D8F)

// Tertiary — teal, for the accents that must not be confused with a status.
val Teal200 = Color(0xFF99F6E4)
val Teal400 = Color(0xFF2DD4BF)
val Teal700 = Color(0xFF0F766E)
val Teal900 = Color(0xFF134E4A)

// Neutrals — a slightly blue-shifted grey ramp, so surfaces read as glass rather
// than as flat charcoal when they are layered translucently.
val Ink0 = Color(0xFF07090C)
val Ink1 = Color(0xFF0B0D10)
val Ink2 = Color(0xFF12151A)
val Ink3 = Color(0xFF181C22)
val Ink4 = Color(0xFF1F242B)
val Ink5 = Color(0xFF2A3037)
val Slate400 = Color(0xFF94A3B8)
val Slate300 = Color(0xFFCBD5E1)
val Slate200 = Color(0xFFE2E8F0)
val Slate100 = Color(0xFFF1F5F9)

val Paper0 = Color(0xFFFFFFFF)
val Paper1 = Color(0xFFF7F8FA)
val Paper2 = Color(0xFFEEF1F5)
val Paper3 = Color(0xFFE3E8EF)
val Paper4 = Color(0xFFD3DAE3)
val Slate600 = Color(0xFF475569)
val Slate700 = Color(0xFF334155)
val Slate800 = Color(0xFF1E293B)
val Slate900 = Color(0xFF0F172A)

/**
 * Status colours, kept out of the Material scheme.
 *
 * Deliberately not mapped onto `primary`/`error`/`tertiary`: with dynamic colour on, the
 * scheme is derived from the user's wallpaper, and a wallpaper can easily produce a
 * `primary` that is orange and an `error` that is close to it. Supported and unsupported
 * must never be hard to tell apart, so these five are fixed and are the same on every
 * device. Two variants exist because the same green needs to be lighter on ink than on
 * paper to hold its contrast ratio.
 */
data class StatusColors(
    val supported: Color,
    val partial: Color,
    val unsupported: Color,
    val notExposed: Color,
    val unknown: Color,
    val informational: Color,
) {
    companion object {
        val Dark = StatusColors(
            supported = Color(0xFF4ADE80),
            partial = Color(0xFFFBBF24),
            unsupported = Color(0xFFF87171),
            notExposed = Color(0xFF8B98A8),
            unknown = Color(0xFF8B98A8),
            informational = Color(0xFF7DD3FC),
        )
        val Light = StatusColors(
            supported = Color(0xFF15803D),
            partial = Color(0xFF9A6700),
            unsupported = Color(0xFFB91C1C),
            notExposed = Color(0xFF5B6472),
            unknown = Color(0xFF5B6472),
            informational = Color(0xFF0369A1),
        )
    }
}
