package com.devicelab.core.model

/**
 * The fifteen inspection areas. Order is the order they appear in the report and
 * in the Hardware list.
 */
enum class Lab(val id: String, val title: String, val blurb: String) {
    PLATFORM("platform", "Android Platform", "Build, version, ABIs, kernel, patch level"),
    DISPLAY("display", "Display", "Resolution, density, refresh rates, HDR, colour"),
    GRAPHICS("graphics", "Graphics", "GPU renderer, OpenGL ES, EGL, Vulkan"),
    CPU("cpu", "CPU", "Architecture, ABIs, cores, instruction sets"),
    MEMORY("memory", "Memory", "RAM totals, memory class, runtime heap"),
    STORAGE("storage", "Storage", "Volumes, capacity, filesystem"),
    CAMERA("camera", "Cameras", "Per-camera Camera2 characteristics"),
    CODEC("codec", "Media Codecs", "Video and audio encoders and decoders"),
    AUDIO("audio", "Audio", "Sample rates, devices, latency classes"),
    SENSORS("sensors", "Sensors", "Every sensor the platform enumerates"),
    CONNECTIVITY("connectivity", "Connectivity", "Wi-Fi bands and standards, Bluetooth"),
    USB("usb", "USB", "Host and accessory modes, attached devices"),
    SECURITY("security", "Biometrics & Security", "Biometric class, keystore backing"),
    DRM("drm", "DRM", "Schemes, security levels, HDCP"),
    FEATURES("features", "Hardware Features", "Every PackageManager feature flag"),
    ;

    companion object {
        fun fromId(id: String): Lab? = entries.firstOrNull { it.id == id }
    }
}

/** One lab's detection result. */
data class LabReport(
    val lab: Lab,
    val sections: List<Section>,
    /** Non-fatal problems worth showing the user rather than swallowing. */
    val notes: List<String> = emptyList(),
) {
    fun allFacts(): List<Fact> = sections.flatMap { it.allFacts() }
}

/**
 * A domain's rolled-up scorecard entry.
 *
 * The counts are carried alongside the verdict so the dashboard can show why a domain
 * is partial rather than only that it is. [total] counts capability checks only, not the
 * measurements in [measurements] -- see [Scorecard] for why those are held apart.
 */
data class DomainStatus(
    val domain: Domain,
    val support: Support,
    val summary: String,
    val supported: Int,
    val total: Int,
    val notExposed: Int = 0,
    val unknown: Int = 0,
    val unsupported: Int = 0,
    val measurements: Int = 0,
)

/**
 * The complete capability profile: every lab, plus the derived scorecard.
 *
 * @param capturedAtMillis wall-clock time of the scan, carried so a saved snapshot
 *   can be labelled and compared without re-deriving it
 */
data class CapabilityProfile(
    val capturedAtMillis: Long,
    val reports: List<LabReport>,
) {
    fun report(lab: Lab): LabReport? = reports.firstOrNull { it.lab == lab }

    /**
     * Every fact from every lab, flattened once.
     *
     * Memoised rather than recomputed, because a profile is immutable and this walks a
     * three-level tree of a thousand-odd facts. The dashboard reads it on every
     * recomposition and the exporters read it per format; recomputing was measurable.
     */
    private val flattened: List<Fact> by lazy { reports.flatMap { it.allFacts() } }

    fun allFacts(): List<Fact> = flattened

    /**
     * The dashboard scorecard, derived from the facts themselves.
     *
     * Delegated to [Scorecard] so that a snapshot restored from the database rolls up by
     * the identical rule. There is deliberately no numeric score -- a made-up total
     * would be exactly the kind of invented statistic this app refuses to display.
     */
    val scorecard: List<DomainStatus> by lazy { Scorecard.of(allFacts()) }
}
