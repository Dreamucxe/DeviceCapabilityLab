package com.devicelab.data.detect

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.os.Build
import android.util.Range
import android.util.Size
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
import kotlin.math.atan2
import kotlin.math.roundToInt

/** Fact labels the camera comparison view looks up by name. */
object CameraKeys {
    const val FACING = "Facing"
    const val HARDWARE_LEVEL = "Hardware level"
    const val MAX_RESOLUTION = "Maximum resolution"
    const val SENSOR_ORIENTATION = "Sensor orientation"
    const val RAW = "RAW capture"
    const val MANUAL_SENSOR = "Manual sensor"
    const val MANUAL_FOCUS = "Manual focus"
    const val MANUAL_EXPOSURE = "Manual exposure"
    const val OIS = "Optical stabilisation"
    const val EIS = "Video stabilisation"
    const val FLASH = "Flash"
    const val AUTOFOCUS = "Autofocus"
    const val HDR = "HDR"
    const val MAX_FPS = "Peak frame rate"
    const val ZOOM = "Maximum digital zoom"
    const val APERTURE = "Aperture"
    const val FOCAL_LENGTH = "Focal length"

    /** The rows the comparison table shows, in order. */
    val comparisonRows = listOf(
        FACING, HARDWARE_LEVEL, MAX_RESOLUTION, MAX_FPS, RAW, MANUAL_SENSOR,
        MANUAL_FOCUS, MANUAL_EXPOSURE, OIS, EIS, FLASH, AUTOFOCUS, HDR, ZOOM,
        APERTURE, FOCAL_LENGTH,
    )
}

/**
 * Per-camera Camera2 characteristics.
 *
 * `CameraManager.getCameraCharacteristics()` needs no CAMERA permission -- it
 * reports what the hardware *can* do without opening a session, which is exactly
 * this app's remit. No camera is ever opened and no frame is ever captured, so
 * nothing here can see through a lens.
 *
 * Two Android subtleties the code has to respect:
 *
 *  * **`getCameraIdList()` hides cameras.** From API 29 it returns only cameras the
 *    calling app may open, so physical lenses behind a logical multi-camera do not
 *    appear as top-level IDs. They are enumerated instead from
 *    `LOGICAL_MULTI_CAMERA_PHYSICAL_IDS` and shown nested under their logical camera,
 *    which is the true structure rather than a flat list that would under-report.
 *  * **`REQUEST_AVAILABLE_CAPABILITIES` is the only honest source for RAW and manual
 *    control.** The presence of a RAW output size does not mean an app may request
 *    RAW, so capability flags are read from that key, not inferred from formats.
 */
class CameraDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val probe: Probe,
) : CapabilityDetector {

    override val lab = Lab.CAMERA

    override suspend fun detect(): LabReport {
        val pm = context.packageManager
        val manager = context.getSystemService(CameraManager::class.java)
        if (manager == null) {
            return LabReport(
                lab,
                listOf(
                    Section(
                        "camera-unavailable",
                        "Cameras",
                        facts = listOf(
                            probe.value("Camera service", "getSystemService(CameraManager)") { null },
                        ),
                    ),
                ),
                listOf("This device does not provide a CameraManager service."),
            )
        }

        val ids = probe.attempt(emptyArray<String>()) { manager.cameraIdList }
        val notes = mutableListOf<String>()
        if (ids.isEmpty()) {
            notes += "CameraManager.getCameraIdList() returned no cameras. On API 29+ this " +
                "list only includes cameras this app is permitted to open."
        }

        return LabReport(
            lab = lab,
            sections = buildList {
                add(overview(pm, manager, ids))
                ids.forEach { id ->
                    add(camera(manager, id, logical = true))
                }
            },
            notes = notes,
        )
    }

    private fun overview(
        pm: PackageManager,
        manager: CameraManager,
        ids: Array<String>,
    ): Section {
        val facingCounts = ids.mapNotNull { id ->
            probe.attempt<Int?>(null) {
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            }
        }
        return Section(
            id = "overview",
            title = "Camera overview",
            subtitle = "CameraManager, PackageManager",
            facts = listOf(
                probe.flag(
                    "Any camera",
                    "PackageManager.FEATURE_CAMERA_ANY",
                    minApi = 17,
                    domain = Domain.CAMERA,
                    searchTerms = listOf("camera"),
                ) { pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) },
                probe.value(
                    "Cameras enumerable",
                    "CameraManager.getCameraIdList()",
                    domain = Domain.CAMERA,
                ) { ids.size.takeIf { it > 0 }?.toString() },
                probe.value("Camera IDs", "CameraManager.getCameraIdList()") {
                    ids.joinToString(", ").ifBlank { null }
                },
                probe.value("Back-facing", "CameraCharacteristics.LENS_FACING") {
                    facingCounts.count { it == CameraMetadata.LENS_FACING_BACK }.toString()
                },
                probe.value("Front-facing", "CameraCharacteristics.LENS_FACING") {
                    facingCounts.count { it == CameraMetadata.LENS_FACING_FRONT }.toString()
                },
                probe.value("External", "CameraCharacteristics.LENS_FACING") {
                    facingCounts.count { it == CameraMetadata.LENS_FACING_EXTERNAL }.toString()
                },
                probe.flag(
                    "Autofocus (any camera)",
                    "PackageManager.FEATURE_CAMERA_AUTOFOCUS",
                    domain = Domain.CAMERA,
                    searchTerms = listOf("autofocus", "af"),
                ) { pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_AUTOFOCUS) },
                probe.flag(
                    "Flash (any camera)",
                    "PackageManager.FEATURE_CAMERA_FLASH",
                    domain = Domain.CAMERA,
                    searchTerms = listOf("flash", "torch"),
                ) { pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH) },
                probe.flag(
                    "Manual sensor control (any camera)",
                    "FEATURE_CAMERA_CAPABILITY_MANUAL_SENSOR",
                    minApi = 21,
                    domain = Domain.CAMERA,
                    searchTerms = listOf("manual", "pro mode"),
                ) {
                    pm.hasSystemFeature(
                        PackageManager.FEATURE_CAMERA_CAPABILITY_MANUAL_SENSOR,
                    )
                },
                probe.flag(
                    "Manual post-processing (any camera)",
                    "FEATURE_CAMERA_CAPABILITY_MANUAL_POST_PROCESSING",
                    minApi = 21,
                ) {
                    pm.hasSystemFeature(
                        PackageManager.FEATURE_CAMERA_CAPABILITY_MANUAL_POST_PROCESSING,
                    )
                },
                probe.flag(
                    "RAW capability (any camera)",
                    "FEATURE_CAMERA_CAPABILITY_RAW",
                    minApi = 21,
                    domain = Domain.CAMERA,
                    searchTerms = listOf("raw", "dng"),
                ) { pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_CAPABILITY_RAW) },
                probe.flag(
                    "Concurrent camera streaming",
                    "CameraManager.getConcurrentCameraIds()",
                    minApi = 30,
                    searchTerms = listOf("concurrent", "multi camera", "dual capture"),
                    supportedText = "Supported",
                    unsupportedText = "No concurrent camera combination is reported",
                ) { manager.concurrentCameraIds.isNotEmpty() },
                probe.value(
                    "Concurrent combinations",
                    "CameraManager.getConcurrentCameraIds()",
                    minApi = 30,
                    absentText = Absent.NONE,
                ) {
                    manager.concurrentCameraIds
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString("; ") { set -> set.joinToString("+") }
                },
                probe.flag(
                    "External camera support",
                    "PackageManager.FEATURE_CAMERA_EXTERNAL",
                    minApi = 20,
                    searchTerms = listOf("usb camera", "uvc", "webcam"),
                ) { pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_EXTERNAL) },
                probe.flag(
                    "Camera permission held",
                    "checkSelfPermission(CAMERA)",
                    supportedText = "Yes",
                    unsupportedText = "No — not requested; capability enumeration does not need it",
                    detail = "Reported for completeness. Every value in this lab comes " +
                        "from getCameraCharacteristics(), which works without the " +
                        "permission because it opens nothing.",
                ) {
                    context.checkSelfPermission(android.Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                },
            ),
        )
    }

    private fun camera(manager: CameraManager, id: String, logical: Boolean): Section {
        val chars = probe.attempt<CameraCharacteristics?>(null) {
            manager.getCameraCharacteristics(id)
        }
        if (chars == null) {
            return Section(
                id = "camera-$id",
                title = "Camera $id",
                facts = listOf(
                    probe.value("Characteristics", "getCameraCharacteristics($id)") { null },
                ),
            )
        }

        val facing = chars.get(CameraCharacteristics.LENS_FACING)
        val map = probe.attempt<StreamConfigurationMap?>(null) {
            chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        }
        val capabilities = probe.attempt(intArrayOf()) {
            chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        }

        val physicalIds = if (Build.VERSION.SDK_INT >= 28 && logical) {
            probe.attempt(emptySet<String>()) { chars.physicalCameraIds }
        } else {
            emptySet()
        }

        return Section(
            id = "camera-$id",
            title = buildString {
                append(facingName(facing))
                append(" camera")
                append(" · id ")
                append(id)
            },
            subtitle = hardwareLevelName(
                chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
            ),
            facts = coreFacts(id, chars, map, capabilities, facing),
            children = buildList {
                add(resolutionsSection(id, map))
                add(frameRateSection(id, chars, map))
                add(lensSection(id, chars))
                add(sensorSection(id, chars))
                add(capabilitiesSection(id, capabilities))
                add(processingSection(id, chars))
                extensionsSection(id)?.let { add(it) }
                dynamicRangeSection(id, chars)?.let { add(it) }
                if (physicalIds.isNotEmpty()) {
                    add(
                        Section(
                            id = "camera-$id-physical",
                            title = "Physical cameras behind this logical camera",
                            subtitle = "${physicalIds.size} reported by " +
                                "LOGICAL_MULTI_CAMERA_PHYSICAL_IDS",
                            children = physicalIds.sorted().map { physicalId ->
                                camera(manager, physicalId, logical = false)
                            },
                        ),
                    )
                }
            },
        )
    }

    private fun coreFacts(
        id: String,
        chars: CameraCharacteristics,
        map: StreamConfigurationMap?,
        capabilities: IntArray,
        facing: Int?,
    ): List<Fact> {
        val jpegSizes = probe.attempt(emptyArray<Size>()) {
            map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
        }
        val largest = jpegSizes.maxByOrNull { it.width.toLong() * it.height }

        return listOf(
            probe.value(CameraKeys.FACING, "CameraCharacteristics.LENS_FACING") {
                facingName(facing)
            },
            probe.value(
                CameraKeys.HARDWARE_LEVEL,
                "INFO_SUPPORTED_HARDWARE_LEVEL",
                domain = Domain.CAMERA,
                searchTerms = listOf("hardware level", "full", "limited", "legacy", "level 3"),
                detail = "LEGACY is a shim over the old camera API; LIMITED supports " +
                    "Camera2 partially; FULL supports per-frame control; LEVEL_3 adds " +
                    "RAW reprocessing.",
            ) { hardwareLevelName(chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) },
            probe.value(
                CameraKeys.MAX_RESOLUTION,
                "StreamConfigurationMap.getOutputSizes(JPEG)",
                domain = Domain.CAMERA,
                searchTerms = listOf("megapixel", "resolution", "max resolution"),
            ) {
                largest?.let {
                    "${Format.resolution(it.width, it.height)} · ${Format.megapixels(it.width, it.height)}"
                }
            },
            probe.value(
                CameraKeys.SENSOR_ORIENTATION,
                "CameraCharacteristics.SENSOR_ORIENTATION",
            ) { chars.get(CameraCharacteristics.SENSOR_ORIENTATION)?.let { "$it°" } },
            capabilityFact(
                CameraKeys.RAW,
                capabilities,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW,
                domain = Domain.CAMERA,
                terms = listOf("raw", "dng", "raw_sensor"),
                detail = "Read from REQUEST_AVAILABLE_CAPABILITIES. A RAW output size " +
                    "alone would not prove an app may request RAW frames.",
            ),
            capabilityFact(
                CameraKeys.MANUAL_SENSOR,
                capabilities,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,
                domain = Domain.CAMERA,
                terms = listOf("manual", "pro", "shutter speed", "iso"),
            ),
            probe.verdict(
                CameraKeys.MANUAL_EXPOSURE,
                "SENSOR_INFO_EXPOSURE_TIME_RANGE",
                domain = Domain.CAMERA,
                searchTerms = listOf("manual exposure", "shutter", "long exposure"),
            ) {
                val range = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                val hasManual = capabilities.contains(
                    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,
                )
                when {
                    range != null && hasManual -> Probe.Verdict.yes(
                        "Supported",
                        "Exposure ${nanosToText(range.lower)} – ${nanosToText(range.upper)}",
                    )
                    range != null -> Probe.Verdict.partial(
                        "Range reported, manual capability absent",
                        "The sensor reports an exposure range of " +
                            "${nanosToText(range.lower)} – ${nanosToText(range.upper)}, but " +
                            "MANUAL_SENSOR is not in REQUEST_AVAILABLE_CAPABILITIES, so " +
                            "an app cannot set it.",
                    )
                    else -> Probe.Verdict.no()
                }
            },
            probe.verdict(
                CameraKeys.MANUAL_FOCUS,
                "LENS_INFO_MINIMUM_FOCUS_DISTANCE, CONTROL_AF_AVAILABLE_MODES",
                domain = Domain.CAMERA,
                searchTerms = listOf("manual focus", "focus distance", "af off"),
            ) {
                val minDistance = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                val hasOff = afModes?.contains(CameraMetadata.CONTROL_AF_MODE_OFF) == true
                when {
                    minDistance == null -> Probe.Verdict.no(
                        "Not supported",
                        "LENS_INFO_MINIMUM_FOCUS_DISTANCE is absent, which the platform " +
                            "uses to mark a fixed-focus lens.",
                    )
                    minDistance == 0f -> Probe.Verdict.no(
                        "Fixed focus",
                        "A minimum focus distance of 0 means a fixed-focus lens.",
                    )
                    hasOff -> Probe.Verdict.yes(
                        "Supported",
                        "Closest focus ${Format.decimal(1f / minDistance, 2)} m " +
                            "(${Format.decimal(minDistance, 2)} dioptres); AF can be " +
                            "switched off.",
                    )
                    else -> Probe.Verdict.partial(
                        "Focus range reported, AF cannot be disabled",
                        "CONTROL_AF_MODE_OFF is not offered, so focus distance cannot be " +
                            "driven manually.",
                    )
                }
            },
            probe.verdict(
                CameraKeys.OIS,
                "LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION",
                domain = Domain.CAMERA,
                searchTerms = listOf("ois", "optical stabilisation", "optical stabilization"),
            ) {
                val modes = chars.get(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION,
                ) ?: return@verdict Probe.Verdict.unknown()
                if (modes.any { it == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON }) {
                    Probe.Verdict.yes("Supported")
                } else {
                    Probe.Verdict.no("Only OFF is offered")
                }
            },
            probe.verdict(
                CameraKeys.EIS,
                "CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES",
                domain = Domain.CAMERA,
                searchTerms = listOf("eis", "video stabilisation", "video stabilization"),
            ) {
                val modes = chars.get(
                    CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES,
                ) ?: return@verdict Probe.Verdict.unknown()
                val hasOn = modes.any {
                    it == CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                }
                val hasPreview = Build.VERSION.SDK_INT >= 33 && modes.any {
                    it == CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                }
                when {
                    hasPreview -> Probe.Verdict.yes(
                        "Supported (including preview stabilisation)",
                        "PREVIEW_STABILIZATION is offered, which stabilises the preview " +
                            "stream as well as the recording.",
                    )
                    hasOn -> Probe.Verdict.yes("Supported")
                    else -> Probe.Verdict.no("Only OFF is offered")
                }
            },
            probe.flag(
                CameraKeys.FLASH,
                "FLASH_INFO_AVAILABLE",
                domain = Domain.CAMERA,
                searchTerms = listOf("flash", "torch", "led"),
            ) { chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) },
            probe.verdict(
                CameraKeys.AUTOFOCUS,
                "CONTROL_AF_AVAILABLE_MODES",
                domain = Domain.CAMERA,
                searchTerms = listOf("autofocus", "af", "continuous af"),
            ) {
                val modes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                    ?: return@verdict Probe.Verdict.unknown()
                val real = modes.filter { it != CameraMetadata.CONTROL_AF_MODE_OFF }
                if (real.isEmpty()) {
                    Probe.Verdict.no("Fixed focus — only AF_MODE_OFF")
                } else {
                    Probe.Verdict.yes(
                        "Supported",
                        "Modes: " + real.joinToString(", ") { afModeName(it) },
                    )
                }
            },
            probe.verdict(
                CameraKeys.HDR,
                "CONTROL_AVAILABLE_SCENE_MODES, CameraExtensionCharacteristics",
                domain = Domain.CAMERA,
                searchTerms = listOf("hdr", "high dynamic range"),
            ) {
                val sceneModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
                val sceneHdr = sceneModes?.contains(CameraMetadata.CONTROL_SCENE_MODE_HDR) == true
                val extensionHdr = if (Build.VERSION.SDK_INT >= 31) {
                    probe.attempt(false) {
                        val extChars = context.getSystemService(CameraManager::class.java)
                            ?.getCameraExtensionCharacteristics(id)
                        extChars?.supportedExtensions?.contains(
                            CameraExtensionCharacteristics.EXTENSION_HDR,
                        ) == true
                    }
                } else {
                    false
                }
                val tenBit = if (Build.VERSION.SDK_INT >= 33) {
                    probe.attempt(false) {
                        capabilities.contains(
                            CameraMetadata
                                .REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT,
                        )
                    }
                } else {
                    false
                }
                val sources = buildList {
                    if (sceneHdr) add("SCENE_MODE_HDR")
                    if (extensionHdr) add("camera extension EXTENSION_HDR")
                    if (tenBit) add("10-bit dynamic range capability")
                }
                when {
                    sources.isEmpty() -> Probe.Verdict.no(
                        "Not reported",
                        "Neither SCENE_MODE_HDR, the HDR camera extension, nor 10-bit " +
                            "dynamic range is advertised. Vendor camera apps often " +
                            "implement HDR privately, which no API can see.",
                    )
                    else -> Probe.Verdict.yes(
                        "Supported",
                        "Via " + sources.joinToString(", "),
                    )
                }
            },
            probe.value(
                CameraKeys.ZOOM,
                "SCALER_AVAILABLE_MAX_DIGITAL_ZOOM",
                searchTerms = listOf("zoom", "digital zoom"),
            ) {
                chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                    ?.let { "${Format.decimal(it, 1)}×" }
            },
            probe.value(
                "Zoom ratio range",
                "CONTROL_ZOOM_RATIO_RANGE",
                minApi = 30,
                searchTerms = listOf("zoom ratio", "ultra wide", "telephoto"),
                detail = "A lower bound below 1.0 indicates an ultra-wide lens behind a " +
                    "logical camera.",
            ) {
                chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let {
                    "${Format.decimal(it.lower, 2)}× – ${Format.decimal(it.upper, 2)}×"
                }
            },
        )
    }

    private fun resolutionsSection(id: String, map: StreamConfigurationMap?) = Section(
        id = "camera-$id-resolutions",
        title = "Output resolutions",
        subtitle = "StreamConfigurationMap.getOutputSizes()",
        facts = buildList {
            add(formatSizes("JPEG (stills)", map, ImageFormat.JPEG))
            add(formatSizes("YUV_420_888 (preview/analysis)", map, ImageFormat.YUV_420_888))
            add(formatSizes("PRIVATE (GPU/encoder)", map, ImageFormat.PRIVATE))
            add(formatSizes("RAW_SENSOR", map, ImageFormat.RAW_SENSOR))
            add(formatSizes("RAW10", map, ImageFormat.RAW10))
            add(formatSizes("RAW12", map, ImageFormat.RAW12))
            add(formatSizes("DEPTH16", map, ImageFormat.DEPTH16))
            if (Build.VERSION.SDK_INT >= 31) {
                add(formatSizes("HEIC", map, ImageFormat.HEIC))
            }
            add(
                probe.value(
                    "Video (MediaRecorder) sizes",
                    "getOutputSizes(MediaRecorder.class)",
                    searchTerms = listOf("video resolution", "4k", "8k", "1080p"),
                ) {
                    val sizes = probe.attempt(emptyArray<Size>()) {
                        map?.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()
                    }
                    if (sizes.isEmpty()) return@value null
                    val sorted = sizes.sortedByDescending { it.width.toLong() * it.height }
                    val top = sorted.first()
                    "${sizes.size} sizes, largest ${Format.resolution(top.width, top.height)}"
                },
            )
            add(
                probe.value("Largest video size", "getOutputSizes(MediaRecorder.class)") {
                    val sizes = probe.attempt(emptyArray<Size>()) {
                        map?.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()
                    }
                    sizes.maxByOrNull { it.width.toLong() * it.height }
                        ?.let { videoLabel(it) }
                },
            )
        },
    )

    private fun formatSizes(label: String, map: StreamConfigurationMap?, format: Int): Fact =
        probe.value(label, "getOutputSizes($format)", absentText = "Not offered") {
            val sizes = probe.attempt(emptyArray<Size>()) {
                if (map == null) return@attempt emptyArray()
                if (!map.isOutputSupportedFor(format)) return@attempt emptyArray()
                map.getOutputSizes(format) ?: emptyArray()
            }
            if (sizes.isEmpty()) return@value null
            val sorted = sizes.sortedByDescending { it.width.toLong() * it.height }
            val largest = sorted.first()
            "${sizes.size} sizes · largest ${Format.resolution(largest.width, largest.height)} " +
                "(${Format.megapixels(largest.width, largest.height)})"
        }

    private fun frameRateSection(
        id: String,
        chars: CameraCharacteristics,
        map: StreamConfigurationMap?,
    ) = Section(
        id = "camera-$id-fps",
        title = "Frame rates",
        subtitle = "CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES, high-speed configs",
        facts = listOf(
            probe.value(
                CameraKeys.MAX_FPS,
                "CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES",
                domain = Domain.CAMERA,
                searchTerms = listOf("fps", "frame rate", "60fps", "120fps"),
            ) {
                chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?.maxOfOrNull { it.upper }
                    ?.let { "$it fps" }
            },
            probe.value(
                "Target FPS ranges",
                "CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES",
            ) {
                chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?.sortedBy { it.upper }
                    ?.joinToString(", ") { rangeText(it) }
                    ?.ifBlank { null }
            },
            probe.verdict(
                "High-speed (slow motion) recording",
                "StreamConfigurationMap.getHighSpeedVideoFpsRanges()",
                searchTerms = listOf("slow motion", "high speed", "240fps", "960fps"),
            ) {
                val ranges = probe.attempt(emptyArray<Range<Int>>()) {
                    map?.highSpeedVideoFpsRanges ?: emptyArray()
                }
                if (ranges.isEmpty()) {
                    Probe.Verdict.no("Not offered")
                } else {
                    val peak = ranges.maxOf { it.upper }
                    Probe.Verdict.yes(
                        "Up to $peak fps",
                        "Ranges: " + ranges.sortedBy { it.upper }.joinToString(", ") {
                            rangeText(it)
                        },
                    )
                }
            },
            probe.value(
                "High-speed sizes",
                "StreamConfigurationMap.getHighSpeedVideoSizes()",
                absentText = Absent.NONE,
            ) {
                val sizes = probe.attempt(emptyArray<Size>()) {
                    map?.highSpeedVideoSizes ?: emptyArray()
                }
                sizes.takeIf { it.isNotEmpty() }
                    ?.sortedByDescending { it.width.toLong() * it.height }
                    ?.joinToString(", ") { "${it.width}×${it.height}" }
            },
            probe.value(
                "Minimum frame duration (JPEG, max size)",
                "getOutputMinFrameDuration()",
                detail = "The shortest time between full-resolution stills, which sets " +
                    "the practical burst rate.",
            ) {
                val sizes = probe.attempt(emptyArray<Size>()) {
                    map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
                }
                val largest = sizes.maxByOrNull { it.width.toLong() * it.height }
                    ?: return@value null
                val nanos = map?.getOutputMinFrameDuration(ImageFormat.JPEG, largest)
                    ?: return@value null
                if (nanos <= 0) return@value null
                val fps = 1_000_000_000.0 / nanos
                "${nanosToText(nanos)} (≈ ${Format.decimal(fps.toFloat(), 1)} fps)"
            },
            probe.value(
                "Maximum frame duration",
                "SENSOR_INFO_MAX_FRAME_DURATION",
            ) {
                chars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
                    ?.let { nanosToText(it) }
            },
        ),
    )

    private fun lensSection(id: String, chars: CameraCharacteristics): Section {
        val focalLengths = probe.attempt<FloatArray?>(null) {
            chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        }
        val physicalSize = probe.attempt<android.util.SizeF?>(null) {
            chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        }
        return Section(
            id = "camera-$id-lens",
            title = "Lens",
            subtitle = "CameraCharacteristics.LENS_*",
            facts = listOf(
                probe.value(
                    CameraKeys.FOCAL_LENGTH,
                    "LENS_INFO_AVAILABLE_FOCAL_LENGTHS",
                    searchTerms = listOf("focal length", "mm"),
                ) {
                    focalLengths?.takeIf { it.isNotEmpty() }
                        ?.joinToString(", ") { "${Format.decimal(it, 2)} mm" }
                },
                probe.value(
                    CameraKeys.APERTURE,
                    "LENS_INFO_AVAILABLE_APERTURES",
                    searchTerms = listOf("aperture", "f/1.8", "f-number"),
                ) {
                    chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                        ?.takeIf { it.isNotEmpty() }
                        ?.joinToString(", ") { "f/${Format.decimal(it, 1)}" }
                },
                probe.value(
                    "Field of view (computed)",
                    "focal length + SENSOR_INFO_PHYSICAL_SIZE",
                    searchTerms = listOf("fov", "field of view", "angle of view"),
                    detail = "Derived by trigonometry from the two values the camera does " +
                        "report -- focal length and physical sensor size. Android has no " +
                        "field-of-view key, and this figure describes the full sensor, so " +
                        "a camera app that crops will see less.",
                ) {
                    val focal = focalLengths?.minOrNull() ?: return@value null
                    val size = physicalSize ?: return@value null
                    if (focal <= 0f || size.width <= 0f || size.height <= 0f) return@value null
                    val horizontal = 2 * atan2(size.width / 2.0, focal.toDouble())
                    val vertical = 2 * atan2(size.height / 2.0, focal.toDouble())
                    val toDegrees = 180.0 / Math.PI
                    "≈ ${(horizontal * toDegrees).roundToInt()}° horizontal, " +
                        "${(vertical * toDegrees).roundToInt()}° vertical"
                },
                probe.value(
                    "Minimum focus distance",
                    "LENS_INFO_MINIMUM_FOCUS_DISTANCE",
                    searchTerms = listOf("macro", "close focus"),
                ) {
                    val dioptres = chars.get(
                        CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE,
                    ) ?: return@value null
                    if (dioptres <= 0f) {
                        "Fixed focus (0 dioptres)"
                    } else {
                        "${Format.decimal(1f / dioptres, 2)} m " +
                            "(${Format.decimal(dioptres, 2)} dioptres)"
                    }
                },
                probe.value("Hyperfocal distance", "LENS_INFO_HYPERFOCAL_DISTANCE") {
                    chars.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE)
                        ?.takeIf { it > 0f }
                        ?.let { "${Format.decimal(1f / it, 2)} m" }
                },
                probe.value("Focus distance calibration", "LENS_INFO_FOCUS_DISTANCE_CALIBRATION") {
                    when (chars.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)) {
                        CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED ->
                            "Uncalibrated"
                        CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE ->
                            "Approximate"
                        CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED ->
                            "Calibrated"
                        else -> null
                    }
                },
                probe.value("Optical stabilisation modes", "LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION") {
                    chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                        ?.joinToString(", ") { if (it == 0) "OFF" else "ON" }
                },
                probe.value("Lens pose reference", "LENS_POSE_REFERENCE", minApi = 28) {
                    when (chars.get(CameraCharacteristics.LENS_POSE_REFERENCE)) {
                        0 -> "Primary camera"
                        1 -> "Gyroscope"
                        2 -> "Undefined"
                        else -> null
                    }
                },
            ),
        )
    }

    private fun sensorSection(id: String, chars: CameraCharacteristics) = Section(
        id = "camera-$id-sensor",
        title = "Sensor",
        subtitle = "CameraCharacteristics.SENSOR_*",
        facts = listOf(
            probe.value(
                "Pixel array",
                "SENSOR_INFO_PIXEL_ARRAY_SIZE",
                searchTerms = listOf("sensor resolution", "pixel array"),
                detail = "The sensor's full pixel count, which can exceed the largest " +
                    "JPEG the camera will output.",
            ) {
                chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let {
                    "${Format.resolution(it.width, it.height)} · " +
                        Format.megapixels(it.width, it.height)
                }
            },
            probe.value("Active array", "SENSOR_INFO_ACTIVE_ARRAY_SIZE") {
                chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let {
                    "${it.width()} × ${it.height()} at (${it.left}, ${it.top})"
                }
            },
            probe.value(
                "Pre-correction active array",
                "SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE",
                minApi = 23,
            ) {
                chars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
                    ?.let { "${it.width()} × ${it.height()}" }
            },
            probe.value(
                "Physical size",
                "SENSOR_INFO_PHYSICAL_SIZE",
                searchTerms = listOf("sensor size", "mm"),
            ) {
                chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let {
                    "${Format.decimal(it.width, 2)} × ${Format.decimal(it.height, 2)} mm"
                }
            },
            probe.value(
                "Pixel pitch (computed)",
                "SENSOR_INFO_PHYSICAL_SIZE ÷ SENSOR_INFO_PIXEL_ARRAY_SIZE",
                detail = "Computed from the two reported values, not a datasheet figure.",
            ) {
                val size = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    ?: return@value null
                val array = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                    ?: return@value null
                if (array.width <= 0) return@value null
                val micronsPerPixel = size.width / array.width * 1000f
                "≈ ${Format.decimal(micronsPerPixel, 2)} µm"
            },
            probe.value(
                "ISO sensitivity range",
                "SENSOR_INFO_SENSITIVITY_RANGE",
                searchTerms = listOf("iso", "sensitivity"),
            ) {
                chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    ?.let { "ISO ${it.lower} – ${it.upper}" }
            },
            probe.value("Maximum analog sensitivity", "SENSOR_MAX_ANALOG_SENSITIVITY") {
                chars.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY)
                    ?.let { "ISO $it — above this, gain is digital" }
            },
            probe.value(
                "Exposure time range",
                "SENSOR_INFO_EXPOSURE_TIME_RANGE",
                searchTerms = listOf("shutter speed", "exposure"),
            ) {
                chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    ?.let { "${nanosToText(it.lower)} – ${nanosToText(it.upper)}" }
            },
            probe.value("Colour filter arrangement", "SENSOR_INFO_COLOR_FILTER_ARRANGEMENT") {
                when (chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)) {
                    CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> "RGGB Bayer"
                    CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> "GRBG Bayer"
                    CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> "GBRG Bayer"
                    CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> "BGGR Bayer"
                    CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB -> "RGB (not Bayer)"
                    else -> null
                }
            },
            probe.value("Timestamp source", "SENSOR_INFO_TIMESTAMP_SOURCE") {
                when (chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)) {
                    CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "Unknown clock"
                    CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME ->
                        "SystemClock.elapsedRealtimeNanos (syncable with other sensors)"
                    else -> null
                }
            },
            probe.value("White level", "SENSOR_INFO_WHITE_LEVEL") {
                chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)?.toString()
            },
            probe.value("Black level pattern", "SENSOR_BLACK_LEVEL_PATTERN") {
                chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.toString()
            },
            probe.flag(
                "Lens shading applied",
                "SENSOR_INFO_LENS_SHADING_APPLIED",
                minApi = 28,
            ) { chars.get(CameraCharacteristics.SENSOR_INFO_LENS_SHADING_APPLIED) },
        ),
    )

    private fun capabilitiesSection(id: String, capabilities: IntArray) = Section(
        id = "camera-$id-capabilities",
        title = "Declared capabilities",
        subtitle = "REQUEST_AVAILABLE_CAPABILITIES (${capabilities.size} reported)",
        facts = CAPABILITY_TABLE.map { (constant, entry) ->
            capabilityFact(entry.first, capabilities, constant, terms = entry.second)
        },
    )

    private fun processingSection(id: String, chars: CameraCharacteristics) = Section(
        id = "camera-$id-processing",
        title = "Processing & control",
        facts = listOf(
            probe.value("Scene modes", "CONTROL_AVAILABLE_SCENE_MODES", absentText = Absent.NONE) {
                chars.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
                    ?.takeIf { it.isNotEmpty() }
                    ?.joinToString(", ") { sceneModeName(it) }
            },
            probe.value("Effects", "CONTROL_AVAILABLE_EFFECTS", absentText = Absent.NONE) {
                chars.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS)
                    ?.takeIf { it.isNotEmpty() }
                    ?.size?.let { "$it available" }
            },
            probe.value("AE modes", "CONTROL_AE_AVAILABLE_MODES") {
                chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
                    ?.joinToString(", ") { aeModeName(it) }
            },
            probe.value("AWB modes", "CONTROL_AWB_AVAILABLE_MODES") {
                chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                    ?.size?.let { "$it available" }
            },
            probe.value("Exposure compensation", "CONTROL_AE_COMPENSATION_RANGE") {
                val range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                    ?: return@value null
                val step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                val stepText = step?.let {
                    " in steps of ${Format.decimal(it.numerator.toFloat() / it.denominator, 2)} EV"
                }.orEmpty()
                "${range.lower} to ${range.upper}$stepText"
            },
            probe.value("Face detection", "STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES") {
                val modes = chars.get(
                    CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES,
                ) ?: return@value null
                val real = modes.filter { it != CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF }
                if (real.isEmpty()) {
                    "Not supported"
                } else {
                    val max = chars.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT)
                    "Supported" + (max?.let { ", up to $it faces" } ?: "")
                }
            },
            probe.value("Edge modes", "EDGE_AVAILABLE_EDGE_MODES") {
                chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
                    ?.size?.let { "$it available" }
            },
            probe.value("Noise reduction modes", "NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES") {
                chars.get(
                    CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
                )?.size?.let { "$it available" }
            },
            probe.value("Tone-map modes", "TONEMAP_AVAILABLE_TONE_MAP_MODES") {
                chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
                    ?.size?.let { "$it available" }
            },
            probe.value("Max tone-map curve points", "TONEMAP_MAX_CURVE_POINTS") {
                chars.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS)?.toString()
            },
            probe.value("Sync latency", "SYNC_MAX_LATENCY") {
                when (val latency = chars.get(CameraCharacteristics.SYNC_MAX_LATENCY)) {
                    null -> null
                    CameraMetadata.SYNC_MAX_LATENCY_PER_FRAME_CONTROL ->
                        "Per-frame control (0 frames)"
                    CameraMetadata.SYNC_MAX_LATENCY_UNKNOWN -> "Unknown"
                    else -> "$latency frames"
                }
            },
            probe.value("Max output streams", "REQUEST_MAX_NUM_OUTPUT_PROC") {
                val proc = chars.get(CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_PROC)
                val raw = chars.get(CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_RAW)
                val stalling = chars.get(CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_PROC_STALLING)
                buildList {
                    proc?.let { add("$it processed") }
                    stalling?.let { add("$it stalling") }
                    raw?.let { add("$it RAW") }
                }.joinToString(", ").ifBlank { null }
            },
            probe.value("Partial result count", "REQUEST_PARTIAL_RESULT_COUNT") {
                chars.get(CameraCharacteristics.REQUEST_PARTIAL_RESULT_COUNT)?.toString()
            },
            probe.value("Pipeline max depth", "REQUEST_PIPELINE_MAX_DEPTH") {
                chars.get(CameraCharacteristics.REQUEST_PIPELINE_MAX_DEPTH)?.toString()
            },
            probe.value("Available characteristic keys", "getKeys()") {
                probe.attempt<String?>(null) { chars.keys.size.toString() }
            },
        ),
    )

    /**
     * Vendor camera extensions.
     *
     * These are the OEM's own night mode, bokeh and HDR pipelines exposed through a
     * standard interface. Available only from API 31, and only where the vendor has
     * implemented the extension library at all.
     */
    private fun extensionsSection(id: String): Section? {
        if (Build.VERSION.SDK_INT < 31) {
            return Section(
                id = "camera-$id-extensions",
                title = "Camera extensions",
                facts = listOf(
                    probe.value(
                        "Vendor extensions",
                        "CameraExtensionCharacteristics.getSupportedExtensions()",
                        minApi = 31,
                    ) { null },
                ),
            )
        }
        val manager = context.getSystemService(CameraManager::class.java) ?: return null
        val supported = probe.attempt(emptyList<Int>()) {
            manager.getCameraExtensionCharacteristics(id).supportedExtensions
        }
        return Section(
            id = "camera-$id-extensions",
            title = "Camera extensions",
            subtitle = "CameraExtensionCharacteristics — vendor pipelines",
            facts = listOf(
                extensionFact("Night mode", supported, CameraExtensionCharacteristics.EXTENSION_NIGHT),
                extensionFact("HDR", supported, CameraExtensionCharacteristics.EXTENSION_HDR),
                extensionFact("Bokeh / portrait", supported, CameraExtensionCharacteristics.EXTENSION_BOKEH),
                extensionFact(
                    "Face retouch",
                    supported,
                    CameraExtensionCharacteristics.EXTENSION_FACE_RETOUCH,
                ),
                extensionFact(
                    "Automatic",
                    supported,
                    CameraExtensionCharacteristics.EXTENSION_AUTOMATIC,
                ),
            ),
        )
    }

    private fun extensionFact(label: String, supported: List<Int>, extension: Int): Fact =
        probe.flag(
            label,
            "CameraExtensionCharacteristics.getSupportedExtensions()",
            minApi = 31,
            searchTerms = listOf("extension", label.lowercase()),
        ) { supported.contains(extension) }

    private fun dynamicRangeSection(id: String, chars: CameraCharacteristics): Section? {
        if (Build.VERSION.SDK_INT < 33) return null
        val profiles = probe.attempt<Set<Long>>(emptySet()) {
            chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                ?.supportedProfiles
                .orEmpty()
        }
        return Section(
            id = "camera-$id-dynamic-range",
            title = "10-bit dynamic range",
            subtitle = "REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES",
            facts = listOf(
                probe.value(
                    "Profiles",
                    "DynamicRangeProfiles.getSupportedProfiles()",
                    minApi = 33,
                    absentText = Absent.NONE,
                    searchTerms = listOf("hdr10", "hlg10", "dolby vision", "10-bit"),
                ) {
                    profiles.takeIf { it.isNotEmpty() }
                        ?.sorted()
                        ?.joinToString(", ") { dynamicRangeName(it) }
                },
                probe.flag("HLG10", "DynamicRangeProfiles", minApi = 33) {
                    profiles.contains(PROFILE_HLG10)
                },
                probe.flag("HDR10", "DynamicRangeProfiles", minApi = 33) {
                    profiles.contains(PROFILE_HDR10)
                },
                probe.flag("HDR10+", "DynamicRangeProfiles", minApi = 33) {
                    profiles.contains(PROFILE_HDR10_PLUS)
                },
            ),
        )
    }

    private fun capabilityFact(
        label: String,
        capabilities: IntArray,
        constant: Int,
        domain: Domain? = null,
        terms: List<String> = emptyList(),
        detail: String? = null,
    ): Fact = probe.flag(
        label,
        "REQUEST_AVAILABLE_CAPABILITIES",
        minApi = 21,
        domain = domain,
        detail = detail,
        searchTerms = terms,
    ) { capabilities.contains(constant) }

    // ---- naming helpers ---------------------------------------------------

    private fun facingName(facing: Int?): String = when (facing) {
        CameraMetadata.LENS_FACING_FRONT -> "Front"
        CameraMetadata.LENS_FACING_BACK -> "Back"
        CameraMetadata.LENS_FACING_EXTERNAL -> "External"
        else -> "Unknown-facing"
    }

    private fun hardwareLevelName(level: Int?): String = when (level) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> Absent.UNKNOWN
    }

    private fun afModeName(mode: Int): String = when (mode) {
        CameraMetadata.CONTROL_AF_MODE_OFF -> "OFF"
        CameraMetadata.CONTROL_AF_MODE_AUTO -> "AUTO"
        CameraMetadata.CONTROL_AF_MODE_MACRO -> "MACRO"
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "CONTINUOUS_VIDEO"
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONTINUOUS_PICTURE"
        CameraMetadata.CONTROL_AF_MODE_EDOF -> "EDOF"
        else -> "mode $mode"
    }

    private fun aeModeName(mode: Int): String = when (mode) {
        CameraMetadata.CONTROL_AE_MODE_OFF -> "OFF"
        CameraMetadata.CONTROL_AE_MODE_ON -> "ON"
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH -> "AUTO_FLASH"
        CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH -> "ALWAYS_FLASH"
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE -> "AUTO_FLASH_REDEYE"
        else -> "mode $mode"
    }

    private fun sceneModeName(mode: Int): String = when (mode) {
        CameraMetadata.CONTROL_SCENE_MODE_DISABLED -> "DISABLED"
        CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY -> "FACE_PRIORITY"
        CameraMetadata.CONTROL_SCENE_MODE_ACTION -> "ACTION"
        CameraMetadata.CONTROL_SCENE_MODE_PORTRAIT -> "PORTRAIT"
        CameraMetadata.CONTROL_SCENE_MODE_LANDSCAPE -> "LANDSCAPE"
        CameraMetadata.CONTROL_SCENE_MODE_NIGHT -> "NIGHT"
        CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT -> "NIGHT_PORTRAIT"
        CameraMetadata.CONTROL_SCENE_MODE_THEATRE -> "THEATRE"
        CameraMetadata.CONTROL_SCENE_MODE_BEACH -> "BEACH"
        CameraMetadata.CONTROL_SCENE_MODE_SNOW -> "SNOW"
        CameraMetadata.CONTROL_SCENE_MODE_SUNSET -> "SUNSET"
        CameraMetadata.CONTROL_SCENE_MODE_STEADYPHOTO -> "STEADYPHOTO"
        CameraMetadata.CONTROL_SCENE_MODE_FIREWORKS -> "FIREWORKS"
        CameraMetadata.CONTROL_SCENE_MODE_SPORTS -> "SPORTS"
        CameraMetadata.CONTROL_SCENE_MODE_PARTY -> "PARTY"
        CameraMetadata.CONTROL_SCENE_MODE_CANDLELIGHT -> "CANDLELIGHT"
        CameraMetadata.CONTROL_SCENE_MODE_BARCODE -> "BARCODE"
        CameraMetadata.CONTROL_SCENE_MODE_HDR -> "HDR"
        else -> "mode $mode"
    }

    private fun dynamicRangeName(profile: Long): String =
        DYNAMIC_RANGE_NAMES[profile] ?: "profile 0x${java.lang.Long.toHexString(profile)}"

    private fun videoLabel(size: Size): String {
        val name = when {
            size.width >= 7680 -> "8K"
            size.width >= 3840 -> "4K UHD"
            size.width >= 2560 -> "1440p"
            size.width >= 1920 -> "1080p"
            size.width >= 1280 -> "720p"
            else -> null
        }
        return Format.resolution(size.width, size.height) + (name?.let { " ($it)" } ?: "")
    }

    private fun <T : Comparable<T>> rangeText(range: Range<T>): String =
        if (range.lower == range.upper) "${range.lower}" else "${range.lower}–${range.upper}"

    private fun nanosToText(nanos: Long): String = when {
        nanos >= 1_000_000_000 -> "${Format.decimal(nanos / 1_000_000_000f, 1)} s"
        nanos >= 1_000_000 -> "${Format.decimal(nanos / 1_000_000f, 1)} ms"
        nanos >= 1_000 -> "${Format.decimal(nanos / 1_000f, 1)} µs"
        else -> "$nanos ns"
    }

    private companion object {
        /**
         * `DynamicRangeProfiles` bit values, written out rather than referenced.
         *
         * The class only exists from API 33, and referencing its fields from a method
         * body would make this class's verification depend on it being present. The
         * values are a stable part of the public API, so spelling them out keeps the
         * detector loadable on every supported release.
         */
        const val PROFILE_HLG10 = 2L
        const val PROFILE_HDR10 = 4L
        const val PROFILE_HDR10_PLUS = 8L

        val DYNAMIC_RANGE_NAMES: Map<Long, String> = mapOf(
            1L to "STANDARD",
            PROFILE_HLG10 to "HLG10",
            PROFILE_HDR10 to "HDR10",
            PROFILE_HDR10_PLUS to "HDR10+",
            16L to "DOLBY_VISION_10B_HDR_REF",
            32L to "DOLBY_VISION_10B_HDR_REF_PO",
            64L to "DOLBY_VISION_10B_HDR_OEM",
            128L to "DOLBY_VISION_10B_HDR_OEM_PO",
            256L to "DOLBY_VISION_8B_HDR_REF",
            512L to "DOLBY_VISION_8B_HDR_REF_PO",
            1024L to "DOLBY_VISION_8B_HDR_OEM",
            2048L to "DOLBY_VISION_8B_HDR_OEM_PO",
        )

        /** constant → (label, search terms) */
        val CAPABILITY_TABLE: List<Pair<Int, Pair<String, List<String>>>> = listOf(
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE to
                ("Backward compatible" to listOf("legacy api")),
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR to
                ("Manual sensor" to listOf("manual", "iso", "shutter")),
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING to
                ("Manual post-processing" to listOf("white balance", "tonemap")),
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW to
                ("RAW" to listOf("raw", "dng")),
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING to
                ("Private reprocessing" to listOf("reprocessing")),
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS to
                ("Read sensor settings" to emptyList()),
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE to
                ("Burst capture" to listOf("burst")),
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING to
                ("YUV reprocessing" to listOf("reprocessing")),
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT to
                ("Depth output" to listOf("depth", "tof", "portrait")),
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO to
                ("Constrained high-speed video" to listOf("slow motion", "high speed")),
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING to
                ("Motion tracking" to emptyList()),
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA to
                ("Logical multi-camera" to listOf("multi camera", "logical")),
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME to
                ("Monochrome" to listOf("mono", "black and white")),
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA to
                ("Secure image data" to listOf("secure", "face unlock")),
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA to
                ("System camera" to emptyList()),
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING to
                ("Offline processing" to emptyList()),
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR to
                ("Ultra-high-resolution sensor" to listOf("108mp", "200mp", "pixel binning")),
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING to
                ("Remosaic reprocessing" to emptyList()),
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT to
                ("10-bit dynamic range" to listOf("hdr10", "10-bit")),
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE to
                ("Stream use case" to emptyList()),
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_COLOR_SPACE_PROFILES to
                ("Colour space profiles" to listOf("display p3", "colour space")),
        )
    }
}
