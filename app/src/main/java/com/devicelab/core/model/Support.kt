package com.devicelab.core.model

/**
 * The verdict for a single capability.
 *
 * These five states exist because "unavailable" is not one thing. A device that
 * answered "no" to a question is different from a device that could not be asked,
 * and both are different from a question Android has no API for. Collapsing them
 * into a single "unsupported" is the specific dishonesty this app exists to avoid.
 */
enum class Support(val glyph: String, val label: String, val rank: Int) {
    /** The platform reported the capability as present. */
    SUPPORTED("✓", "Fully supported", 0),

    /** Present, but with a real limitation the detail text names. */
    PARTIAL("◐", "Partially supported", 1),

    /** The query succeeded and the answer was no. */
    UNSUPPORTED("✕", "Unsupported", 3),

    /** Nothing could be asked: no API on this level, or the API refused. */
    NOT_EXPOSED("—", "Not exposed", 2),

    /** Asked, and the platform's answer was itself indeterminate. */
    UNKNOWN("?", "Unknown", 4),

    /** A measured value rather than a yes/no capability (e.g. a resolution). */
    INFORMATIONAL("·", "Reported", 5),
    ;

    val isAffirmative: Boolean get() = this == SUPPORTED || this == PARTIAL
}

/**
 * The eight capability domains on the dashboard scorecard.
 *
 * A domain's status is rolled up from the facts tagged with it, never assigned by
 * hand -- see [CapabilityProfile.scorecard].
 */
enum class Domain(val title: String) {
    DISPLAY("Display"),
    GRAPHICS("Graphics"),
    CAMERA("Camera"),
    AUDIO("Audio"),
    CONNECTIVITY("Connectivity"),
    SENSORS("Sensors"),
    SECURITY("Security"),
    MEDIA("Media"),
}
