package com.devicelab.data.detect

import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import com.devicelab.core.common.Format
import com.devicelab.core.detect.Probe
import com.devicelab.core.model.Absent
import com.devicelab.core.model.Domain
import com.devicelab.core.model.Fact
import com.devicelab.core.model.Lab
import com.devicelab.core.model.LabReport
import com.devicelab.core.model.Section
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Display capabilities from [DisplayManager], [Display] and [Configuration].
 *
 * Three honesty notes, all of which the UI surfaces:
 *
 *  * **Physical size** is not reported by Android. It is computed here from
 *    `DisplayMetrics.xdpi/ydpi`, which are vendor-supplied and frequently rounded
 *    or plainly wrong, so the row says so rather than presenting a diagonal as
 *    measured fact.
 *  * **Panel bit depth** has no public API on any Android version.
 *  * **HDR types** come from `Display.getHdrCapabilities()`, which reports what the
 *    *display pipeline* accepts. On API 34+ the per-mode list is preferred because
 *    a panel can support HDR only at certain refresh rates.
 */
class DisplayDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val probe: Probe,
) : CapabilityDetector {

    override val lab = Lab.DISPLAY

    override suspend fun detect(): LabReport {
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        val notes = mutableListOf<String>()
        if (display == null) {
            notes += "DisplayManager returned no default display; this is expected only " +
                "on a device with no screen."
            return LabReport(lab, emptyList(), notes)
        }

        val metrics = DisplayMetrics().also {
            @Suppress("DEPRECATION")
            display.getRealMetrics(it)
        }
        val config = context.resources.configuration

        return LabReport(
            lab = lab,
            sections = listOfNotNull(
                geometry(display, metrics, config),
                refresh(display),
                hdr(display),
                colour(display, config),
                cutout(display),
                orientationAndLayout(display, config),
                multiDisplay(dm),
            ),
            notes = notes,
        )
    }

    private fun geometry(
        display: Display,
        metrics: DisplayMetrics,
        config: Configuration,
    ) = Section(
        id = "geometry",
        title = "Geometry & density",
        subtitle = "Display.getRealMetrics(), Resources.getConfiguration()",
        facts = listOf(
            probe.value(
                "Resolution",
                "Display.getRealMetrics()",
                domain = Domain.DISPLAY,
                searchTerms = listOf("resolution", "pixels", "1080p", "1440p"),
                detail = "The panel's full pixel count, including any area behind " +
                    "system bars or a cutout.",
            ) { Format.resolution(metrics.widthPixels, metrics.heightPixels) },
            probe.value(
                "Logical resolution",
                if (Build.VERSION.SDK_INT >= 30) {
                    "WindowManager.getMaximumWindowMetrics()"
                } else {
                    "Display.getSize()"
                },
                detail = "The area an app may lay out in. Smaller than the panel when " +
                    "the system reserves space for navigation.",
            ) { logicalResolution(display) },
            probe.value("Density bucket", "Configuration.densityDpi") {
                "${config.densityDpi} dpi (${densityBucket(config.densityDpi)})"
            },
            probe.value("Density scale", "DisplayMetrics.density") {
                "${Format.decimal(metrics.density)}×"
            },
            probe.value("Exact DPI", "DisplayMetrics.xdpi/ydpi") {
                "${metrics.xdpi.roundToInt()} × ${metrics.ydpi.roundToInt()} dpi"
            },
            probe.value("Screen size (dp)", "Configuration.screenWidthDp") {
                "${config.screenWidthDp} × ${config.screenHeightDp} dp"
            },
            probe.value("Smallest width", "Configuration.smallestScreenWidthDp") {
                "${config.smallestScreenWidthDp} dp"
            },
            probe.value(
                "Physical size (computed)",
                "DisplayMetrics.xdpi/ydpi",
                detail = "Android does not report a panel's physical dimensions. This " +
                    "diagonal is computed from the vendor-supplied xdpi/ydpi values, " +
                    "which are often rounded to a nominal figure, so treat it as " +
                    "approximate rather than measured.",
                searchTerms = listOf("inches", "diagonal", "screen size"),
            ) {
                val xdpi = metrics.xdpi
                val ydpi = metrics.ydpi
                if (xdpi <= 0f || ydpi <= 0f) return@value null
                val widthIn = metrics.widthPixels / xdpi
                val heightIn = metrics.heightPixels / ydpi
                val diagonal = hypot(widthIn.toDouble(), heightIn.toDouble())
                "≈ ${Format.decimal(diagonal.toFloat(), 1)}\" diagonal " +
                    "(${Format.decimal(widthIn, 1)}\" × ${Format.decimal(heightIn, 1)}\")"
            },
            probe.notExposedByAndroid(
                "Panel bit depth",
                "no public API on any Android version reports per-channel panel depth",
                searchTerms = listOf("bit depth", "10-bit", "8-bit", "banding"),
            ),
            probe.value("Display name", "Display.getName()") { display.name },
            probe.value("Display ID", "Display.getDisplayId()") { display.displayId.toString() },
            probe.value("Product info", "Display.getDeviceProductInfo()", minApi = 31) {
                val info = display.deviceProductInfo ?: return@value null
                buildList {
                    info.name?.takeIf { it.isNotBlank() }?.let { add(it) }
                    info.manufacturerPnpId?.takeIf { it.isNotBlank() }?.let { add("PnP $it") }
                    info.productId?.takeIf { it.isNotBlank() }?.let { add("product $it") }
                    info.modelYear.takeIf { it > 0 }?.let { add("model year $it") }
                }.joinToString(", ").ifBlank { null }
            },
        ),
    )

    private fun logicalResolution(display: Display): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = context.getSystemService(WindowManager::class.java)
            val bounds = wm?.maximumWindowMetrics?.bounds
            if (bounds != null) return Format.resolution(bounds.width(), bounds.height())
        }
        val point = Point()
        @Suppress("DEPRECATION")
        display.getSize(point)
        return Format.resolution(point.x, point.y)
    }

    /**
     * Refresh-rate support.
     *
     * `getSupportedModes()` is the only reliable source for the rate list: a device
     * may run its panel at 120 Hz while `getRefreshRate()` reports the mode active
     * at query time, so both are shown and the distinction is stated.
     */
    private fun refresh(display: Display) = Section(
        id = "refresh",
        title = "Refresh rate",
        subtitle = "Display.getMode(), Display.getSupportedModes()",
        facts = listOf(
            probe.value(
                "Current refresh rate",
                "Display.getRefreshRate()",
                domain = Domain.DISPLAY,
                searchTerms = listOf("hz", "refresh", "60hz", "90hz", "120hz", "144hz"),
                detail = "The rate of the mode active when this scan ran. Android may " +
                    "switch modes at any time, so a lower figure here does not mean " +
                    "the panel cannot go faster.",
            ) { Format.hertz(display.refreshRate) },
            probe.value(
                "Peak refresh rate",
                "Display.getSupportedModes()",
                minApi = 23,
                domain = Domain.DISPLAY,
                searchTerms = listOf("peak", "max refresh", "high refresh"),
            ) {
                display.supportedModes
                    ?.maxOfOrNull { it.refreshRate }
                    ?.let { Format.hertz(it) }
            },
            probe.value(
                "Supported refresh rates",
                "Display.getSupportedModes()",
                minApi = 23,
            ) {
                display.supportedModes
                    ?.map { it.refreshRate }
                    ?.distinct()
                    ?.sortedDescending()
                    ?.joinToString(", ") { Format.hertz(it) }
                    ?.ifBlank { null }
            },
            probe.value("Active mode", "Display.getMode()", minApi = 23) {
                display.mode?.let { modeText(it) }
            },
            probe.flag(
                "Variable refresh rate",
                "Display.getSupportedModes()",
                minApi = 23,
                domain = Domain.DISPLAY,
                searchTerms = listOf("vrr", "variable refresh", "ltpo", "adaptive"),
                supportedText = "Multiple modes at one resolution",
                unsupportedText = "Single refresh rate per resolution",
                detail = "True when the panel advertises more than one refresh rate at " +
                    "the same resolution. Android exposes no flag for LTPO or for a " +
                    "continuous rate range, so this is the strongest available signal.",
            ) {
                val modes = display.supportedModes ?: return@flag null
                modes.groupBy { it.physicalWidth to it.physicalHeight }
                    .any { (_, group) -> group.map { it.refreshRate }.distinct().size > 1 }
            },
            probe.value("Mode count", "Display.getSupportedModes().size", minApi = 23) {
                display.supportedModes?.size?.toString()
            },
        ),
        children = listOfNotNull(modesSection(display)),
    )

    private fun modesSection(display: Display): Section? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val modes = probe.attempt<Array<Display.Mode>?>(null) { display.supportedModes }
            ?: return null
        if (modes.isEmpty()) return null
        val activeId = probe.attempt(-1) { display.mode?.modeId ?: -1 }
        return Section(
            id = "modes",
            title = "All display modes",
            subtitle = "${modes.size} reported",
            facts = modes.sortedWith(
                compareByDescending<Display.Mode> { it.physicalWidth.toLong() * it.physicalHeight }
                    .thenByDescending { it.refreshRate },
            ).map { mode ->
                probe.value(
                    "Mode ${mode.modeId}" + if (mode.modeId == activeId) " (active)" else "",
                    "Display.Mode",
                    minApi = 23,
                ) { modeText(mode) }
            },
        )
    }

    private fun modeText(mode: Display.Mode): String {
        val base = "${Format.resolution(mode.physicalWidth, mode.physicalHeight)} @ " +
            Format.hertz(mode.refreshRate)
        if (Build.VERSION.SDK_INT >= 34) {
            val hdr = probe.attempt(intArrayOf()) { mode.supportedHdrTypes }
            if (hdr.isNotEmpty()) {
                return base + " · HDR: " + hdr.joinToString(", ") { hdrTypeName(it) }
            }
        }
        return base
    }

    private fun hdr(display: Display) = Section(
        id = "hdr",
        title = "High dynamic range",
        subtitle = "Display.getHdrCapabilities()",
        facts = listOf(
            probe.flag(
                "HDR",
                "Display.isHdr()",
                minApi = 24,
                domain = Domain.DISPLAY,
                searchTerms = listOf("hdr", "high dynamic range"),
            ) { display.isHdr },
            probe.value(
                "HDR formats",
                "HdrCapabilities.getSupportedHdrTypes()",
                minApi = 24,
                absentText = Absent.NONE,
                searchTerms = listOf("hdr10", "hdr10+", "dolby vision", "hlg"),
            ) {
                @Suppress("DEPRECATION")
                display.hdrCapabilities?.supportedHdrTypes
                    ?.map { hdrTypeName(it) }
                    ?.joinToString(", ")
                    ?.ifBlank { null }
            },
            hdrFormatFact(display, "HDR10", 2),
            hdrFormatFact(display, "HDR10+", 4),
            hdrFormatFact(display, "Dolby Vision", 1),
            hdrFormatFact(display, "HLG", 3),
            probe.value("Max luminance", "HdrCapabilities.getDesiredMaxLuminance()", minApi = 24) {
                @Suppress("DEPRECATION")
                display.hdrCapabilities?.desiredMaxLuminance
                    ?.takeIf { it > 0f }
                    ?.let { "${it.roundToInt()} cd/m²" }
            },
            probe.value(
                "Max average luminance",
                "HdrCapabilities.getDesiredMaxAverageLuminance()",
                minApi = 24,
            ) {
                @Suppress("DEPRECATION")
                display.hdrCapabilities?.desiredMaxAverageLuminance
                    ?.takeIf { it > 0f }
                    ?.let { "${it.roundToInt()} cd/m²" }
            },
            probe.value(
                "Min luminance",
                "HdrCapabilities.getDesiredMinLuminance()",
                minApi = 24,
            ) {
                @Suppress("DEPRECATION")
                display.hdrCapabilities?.desiredMinLuminance
                    ?.takeIf { it > 0f }
                    ?.let { Format.decimal(it, 4) + " cd/m²" }
            },
            probe.flag(
                "HDR/SDR ratio control",
                "Display.isHdrSdrRatioAvailable()",
                minApi = 34,
                searchTerms = listOf("hdr sdr ratio", "ultra hdr"),
            ) { display.isHdrSdrRatioAvailable },
        ),
    )

    /**
     * One row per HDR format, because a table with a row for HDR10+ that reads
     * "not supported" is more informative than a single joined list the reader has
     * to scan for an absence.
     */
    private fun hdrFormatFact(display: Display, name: String, type: Int): Fact =
        probe.flag(
            name,
            "HdrCapabilities.getSupportedHdrTypes()",
            minApi = if (type == 4) 29 else 24,
            searchTerms = listOf(name.lowercase()),
        ) {
            @Suppress("DEPRECATION")
            val types = display.hdrCapabilities?.supportedHdrTypes ?: return@flag null
            types.contains(type)
        }

    private fun hdrTypeName(type: Int): String = when (type) {
        1 -> "Dolby Vision"
        2 -> "HDR10"
        3 -> "HLG"
        4 -> "HDR10+"
        else -> "Type $type"
    }

    private fun colour(display: Display, config: Configuration) = Section(
        id = "colour",
        title = "Colour",
        subtitle = "Display, Configuration",
        facts = listOf(
            probe.flag(
                "Wide colour gamut",
                "Display.isWideColorGamut()",
                minApi = 26,
                domain = Domain.DISPLAY,
                searchTerms = listOf("wide color", "wide colour", "p3", "gamut"),
            ) { display.isWideColorGamut },
            probe.flag(
                "Wide gamut in this configuration",
                "Configuration.isScreenWideColorGamut()",
                minApi = 26,
                detail = "Whether the current screen configuration is running wide-gamut, " +
                    "which can differ from what the panel is capable of.",
            ) { config.isScreenWideColorGamut },
            probe.notExposedByAndroid(
                "Current colour mode",
                "Display.getColorMode() exists on the platform but is not in the public SDK, " +
                    "so the active colour mode of the panel cannot be read. The two rows " +
                    "above are what is exposed: whether the panel is wide-gamut capable, and " +
                    "whether the current configuration is running wide-gamut.",
                domain = Domain.DISPLAY,
                searchTerms = listOf("color mode", "colour mode"),
            ),
            probe.value(
                "Preferred wide gamut space",
                "Display.getPreferredWideGamutColorSpace()",
                minApi = 29,
            ) { display.preferredWideGamutColorSpace?.name },
            probe.flag(
                "HDR in this configuration",
                "Configuration.isScreenHdr()",
                minApi = 26,
            ) { config.isScreenHdr },
            probe.flag(
                "Minimal post-processing",
                "Display.isMinimalPostProcessingSupported()",
                minApi = 30,
                searchTerms = listOf("game mode", "auto low latency", "allm", "post processing"),
                detail = "Auto Low Latency Mode: whether the display can be asked to skip " +
                    "its own picture processing.",
            ) { display.isMinimalPostProcessingSupported },
        ),
    )

    /**
     * Cutout geometry.
     *
     * `Display.getCutout()` arrived in API 29. Below that the only access is through
     * a window's insets, which needs an attached view rather than an application
     * context, so on API 28 this reports the API-level limitation instead of
     * pretending there is no cutout.
     */
    private fun cutout(display: Display) = Section(
        id = "cutout",
        title = "Cutout & rounded corners",
        subtitle = "Display.getCutout()",
        facts = buildList {
            add(
                probe.verdict(
                    "Display cutout",
                    "Display.getCutout()",
                    minApi = 29,
                    searchTerms = listOf("notch", "cutout", "punch hole", "hole punch"),
                ) {
                    val cutout = display.cutout
                    if (cutout == null) {
                        Probe.Verdict.no("No cutout reported")
                    } else {
                        Probe.Verdict.yes(
                            "Present",
                            "Safe insets — left ${cutout.safeInsetLeft}, " +
                                "top ${cutout.safeInsetTop}, right ${cutout.safeInsetRight}, " +
                                "bottom ${cutout.safeInsetBottom} px",
                        )
                    }
                },
            )
            add(
                probe.value("Cutout bounding boxes", "DisplayCutout.getBoundingRects()", minApi = 29) {
                    display.cutout?.boundingRects
                        ?.takeIf { it.isNotEmpty() }
                        ?.joinToString("; ") { "${it.width()}×${it.height()} at (${it.left},${it.top})" }
                },
            )
            add(
                probe.value("Waterfall insets", "DisplayCutout.getWaterfallInsets()", minApi = 30) {
                    val insets = display.cutout?.waterfallInsets ?: return@value null
                    if (insets.left == 0 && insets.top == 0 && insets.right == 0 && insets.bottom == 0) {
                        return@value "None"
                    }
                    "left ${insets.left}, top ${insets.top}, right ${insets.right}, bottom ${insets.bottom} px"
                },
            )
            add(
                probe.value("Rounded corner radii", "Display.getRoundedCorner()", minApi = 31) {
                    val corners = (0..3).mapNotNull { position ->
                        display.getRoundedCorner(position)?.let { corner ->
                            "${cornerName(position)} ${corner.radius}px"
                        }
                    }
                    corners.joinToString(", ").ifBlank { "None reported" }
                },
            )
        },
    )

    private fun cornerName(position: Int): String = when (position) {
        0 -> "top-left"
        1 -> "top-right"
        2 -> "bottom-right"
        3 -> "bottom-left"
        else -> "corner $position"
    }

    private fun orientationAndLayout(display: Display, config: Configuration) = Section(
        id = "orientation",
        title = "Orientation & layout",
        facts = listOf(
            probe.value("Current orientation", "Configuration.orientation") {
                when (config.orientation) {
                    Configuration.ORIENTATION_PORTRAIT -> "Portrait"
                    Configuration.ORIENTATION_LANDSCAPE -> "Landscape"
                    else -> "Undefined"
                }
            },
            probe.value("Rotation", "Display.getRotation()") {
                when (display.rotation) {
                    android.view.Surface.ROTATION_0 -> "0° (natural)"
                    android.view.Surface.ROTATION_90 -> "90°"
                    android.view.Surface.ROTATION_180 -> "180°"
                    android.view.Surface.ROTATION_270 -> "270°"
                    else -> null
                }
            },
            probe.value("Screen layout size", "Configuration.screenLayout") {
                when (config.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) {
                    Configuration.SCREENLAYOUT_SIZE_SMALL -> "Small"
                    Configuration.SCREENLAYOUT_SIZE_NORMAL -> "Normal"
                    Configuration.SCREENLAYOUT_SIZE_LARGE -> "Large"
                    Configuration.SCREENLAYOUT_SIZE_XLARGE -> "Extra large"
                    else -> "Undefined"
                }
            },
            probe.value("Aspect", "Configuration.screenLayout") {
                if ((config.screenLayout and Configuration.SCREENLAYOUT_LONG_MASK) ==
                    Configuration.SCREENLAYOUT_LONG_YES
                ) {
                    "Long (taller than 16:9-ish)"
                } else {
                    "Not long"
                }
            },
            probe.value("Round display", "Configuration.isScreenRound()", minApi = 23) {
                if (config.isScreenRound) "Yes" else "No"
            },
            probe.value("UI mode", "Configuration.uiMode") {
                val type = when (config.uiMode and Configuration.UI_MODE_TYPE_MASK) {
                    Configuration.UI_MODE_TYPE_NORMAL -> "Normal"
                    Configuration.UI_MODE_TYPE_DESK -> "Desk"
                    Configuration.UI_MODE_TYPE_CAR -> "Car"
                    Configuration.UI_MODE_TYPE_TELEVISION -> "Television"
                    Configuration.UI_MODE_TYPE_APPLIANCE -> "Appliance"
                    Configuration.UI_MODE_TYPE_WATCH -> "Watch"
                    Configuration.UI_MODE_TYPE_VR_HEADSET -> "VR headset"
                    else -> "Undefined"
                }
                val night = when (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
                    Configuration.UI_MODE_NIGHT_YES -> "night"
                    Configuration.UI_MODE_NIGHT_NO -> "day"
                    else -> "night mode undefined"
                }
                "$type, $night"
            },
            probe.value("Font scale", "Configuration.fontScale") {
                "${Format.decimal(config.fontScale)}×"
            },
        ),
    )

    private fun multiDisplay(dm: DisplayManager): Section {
        val displays = probe.attempt(emptyArray<Display>()) { dm.displays }
        return Section(
            id = "multi-display",
            title = "Attached displays",
            subtitle = "DisplayManager.getDisplays()",
            facts = listOf(
                probe.value(
                    "Display count",
                    "DisplayManager.getDisplays()",
                    domain = Domain.DISPLAY,
                    searchTerms = listOf("multi display", "external display", "second screen"),
                ) { displays.size.toString() },
                probe.flag(
                    "Secondary display attached",
                    "DisplayManager.getDisplays()",
                    supportedText = "Yes",
                    unsupportedText = "No — only the built-in display",
                ) { displays.size > 1 },
            ),
            children = displays.map { d ->
                Section(
                    id = "display-${d.displayId}",
                    title = probe.attempt("Display ${d.displayId}") {
                        "${d.name} (id ${d.displayId})"
                    },
                    facts = listOf(
                        probe.value("State", "Display.getState()") {
                            when (d.state) {
                                Display.STATE_OFF -> "Off"
                                Display.STATE_ON -> "On"
                                Display.STATE_DOZE -> "Doze"
                                Display.STATE_DOZE_SUSPEND -> "Doze (suspended)"
                                Display.STATE_ON_SUSPEND -> "On (suspended)"
                                Display.STATE_VR -> "VR"
                                else -> "Unknown (${d.state})"
                            }
                        },
                        probe.value("Refresh rate", "Display.getRefreshRate()") {
                            Format.hertz(d.refreshRate)
                        },
                        probe.value("Resolution", "Display.getRealMetrics()") {
                            val m = DisplayMetrics()
                            @Suppress("DEPRECATION")
                            d.getRealMetrics(m)
                            Format.resolution(m.widthPixels, m.heightPixels)
                        },
                        probe.flag("Valid", "Display.isValid()") { d.isValid },
                        probe.value("Flags", "Display.getFlags()") { displayFlags(d.flags) },
                    ),
                )
            },
        )
    }

    private fun displayFlags(flags: Int): String {
        val named = buildList {
            if (flags and Display.FLAG_SECURE != 0) add("SECURE")
            if (flags and Display.FLAG_SUPPORTS_PROTECTED_BUFFERS != 0) {
                add("SUPPORTS_PROTECTED_BUFFERS")
            }
            if (flags and Display.FLAG_PRESENTATION != 0) add("PRESENTATION")
            if (Build.VERSION.SDK_INT >= 29 && flags and Display.FLAG_ROUND != 0) add("ROUND")
        }
        return if (named.isEmpty()) "0x${Integer.toHexString(flags)}" else named.joinToString(", ")
    }

    private fun densityBucket(dpi: Int): String = when {
        dpi <= 120 -> "ldpi"
        dpi <= 160 -> "mdpi"
        dpi <= 213 -> "tvdpi"
        dpi <= 240 -> "hdpi"
        dpi <= 320 -> "xhdpi"
        dpi <= 480 -> "xxhdpi"
        dpi <= 640 -> "xxxhdpi"
        else -> "above xxxhdpi"
    }
}
