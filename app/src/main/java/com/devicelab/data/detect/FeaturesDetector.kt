package com.devicelab.data.detect

import android.content.Context
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import com.devicelab.core.common.Format
import com.devicelab.core.detect.Probe
import com.devicelab.core.model.Fact
import com.devicelab.core.model.Lab
import com.devicelab.core.model.LabReport
import com.devicelab.core.model.Section
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject

/**
 * Hardware features: every flag the platform will admit to, and the ones it will not.
 *
 * `PackageManager.hasSystemFeature` is the oldest and bluntest capability API Android
 * has, and it hides a trap that this lab exists to close. The method takes a string.
 * Ask an Android 8 device about `android.hardware.uwb` -- a name Android did not define
 * until API 34 -- and it returns `false`, cheerfully and instantly. That `false` is not
 * an answer about the hardware; it is the platform not recognising the question. A tool
 * that prints it as "Ultra-wideband: unsupported" has invented a fact.
 *
 * So every row below is gated on the API level that introduced its feature *constant*,
 * not on the API level of the method. A device older than the name reports
 * "Requires API 34+ — this device is running API 28", which is Section 18's distinction
 * applied to the one API where it matters most. The gate levels are not remembered:
 * they were read out of the `data/api-versions.xml` shipped with the compile SDK, and
 * the constant name is printed next to each row so the claim can be checked.
 *
 * Three sources are combined, and each row says which answered it:
 *
 *  * `hasSystemFeature(name)` for presence -- the call an app would actually make.
 *  * `getSystemAvailableFeatures()` for the platform's own list, which carries exact
 *    version numbers in `FeatureInfo.version` and, unlike any fixed table, includes
 *    the OEM's own features. Those get a section of their own rather than being
 *    discarded for not being recognised.
 *  * `getSystemSharedLibraryNames()` for the optional libraries on the image.
 *
 * Two honest limits are worth stating up front. A feature flag is a *declaration* by
 * the OEM, not a measurement -- a device can ship a barometer and neglect to declare
 * `android.hardware.sensor.barometer`, and the Sensors lab, which enumerates the real
 * sensor list, is the better answer when the two disagree. And nothing here is tagged
 * to a scorecard domain: the dashboard rolls up from the labs that measure hardware
 * directly, so a long tail of obscure flags cannot move it.
 */
class FeaturesDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val probe: Probe,
) : CapabilityDetector {

    override val lab = Lab.FEATURES

    override suspend fun detect(): LabReport {
        val pm = context.packageManager
        val declared = SystemFeatures.byName(pm)
        val tabled = FEATURES.mapTo(HashSet()) { it.name }
        val extra = declared.keys.filterNot { it in tabled }.sorted()

        return LabReport(
            lab = lab,
            sections = listOf(summary(pm, declared, extra)) +
                GROUPS.mapNotNull { group -> groupSection(pm, declared, group) } +
                listOf(
                    vendorSection(declared, extra),
                    sharedLibraries(pm),
                    limits(),
                ),
            notes = listOf(
                "hasSystemFeature() returns false for a feature name the running " +
                    "Android version has never heard of, which is indistinguishable from " +
                    "absent hardware. Every row is gated on the API level that introduced " +
                    "its constant, so a device too old to know the question says so " +
                    "instead of answering no.",
                "A feature flag is what the OEM declared, not what was measured. Where a " +
                    "flag and a dedicated lab disagree — no barometer flag but a barometer " +
                    "in the sensor list — the lab that queried the hardware is right.",
                "Rows here carry no scorecard domain. The dashboard is rolled up from the " +
                    "labs that query hardware directly, so this reference list cannot " +
                    "skew it.",
            ),
        )
    }

    // ------------------------------------------------------------------ summary

    private fun summary(
        pm: PackageManager,
        declared: Map<String, FeatureInfo>,
        extra: List<String>,
    ) = Section(
        id = "features-summary",
        title = "Feature list",
        subtitle = "PackageManager.getSystemAvailableFeatures()",
        facts = listOf(
            probe.value(
                "Features declared by this device",
                "PackageManager.getSystemAvailableFeatures()",
                searchTerms = listOf("feature count", "features", "how many features"),
                detail = "The platform's own list. It is the authority on what this " +
                    "build declares, and it is longer than any fixed table because it " +
                    "includes the OEM's own additions.",
            ) { declared.size.takeIf { it > 0 }?.toString() },
            probe.value(
                "Named by this build",
                "FEATURE_* constants in the compile SDK",
                searchTerms = listOf("known features", "aosp features"),
                detail = "How many of the ${FEATURES.size} AOSP feature constants this " +
                    "app asks about are declared here. The remaining constants are " +
                    "reported as queried-and-absent, not omitted.",
            ) {
                val matched = FEATURES.count { it.name in declared }
                "$matched of ${FEATURES.size} AOSP constants"
            },
            probe.value(
                "Outside the AOSP set",
                "PackageManager.getSystemAvailableFeatures()",
                searchTerms = listOf("vendor features", "oem features", "custom features"),
                detail = "Vendor and OEM features, listed in full further down. A high " +
                    "count is normal on a manufacturer build.",
            ) { extra.size.toString() },
            probe.value(
                "OpenGL ES version in the feature list",
                "FeatureInfo.getGlEsVersion()",
                searchTerms = listOf("opengl es", "gles", "gl version"),
                detail = "The platform stores this in a single unnamed entry in the " +
                    "feature array rather than as a named feature. It is the version " +
                    "declared for app targeting; the Graphics lab reads the version the " +
                    "live GL context reports, which is the one that matters at runtime.",
            ) { SystemFeatures.glEsVersion(pm) },
            probe.value(
                "Entries flagged as required",
                "FeatureInfo.flags & FLAG_REQUIRED",
                searchTerms = listOf("flag required", "flags"),
                detail = "FLAG_REQUIRED describes what an app's manifest demands, not " +
                    "what a device provides, so the system list normally leaves it " +
                    "clear. The count is shown because a non-zero value would be " +
                    "unusual and worth knowing about.",
            ) {
                val required = declared.values.count {
                    it.flags and FeatureInfo.FLAG_REQUIRED != 0
                }
                "$required of ${declared.size}"
            },
        ),
    )

    // ----------------------------------------------------------------- features

    private fun groupSection(
        pm: PackageManager,
        declared: Map<String, FeatureInfo>,
        group: FeatureGroup,
    ): Section? {
        val specs = FEATURES.filter { it.group == group.id }
        if (specs.isEmpty()) return null
        val facts = specs.map { fact(pm, declared, it) }
        val present = facts.count { it.support.isAffirmative }
        return Section(
            id = "features-${group.id}",
            title = group.title,
            subtitle = "$present of ${facts.size} present",
            facts = facts,
        )
    }

    /**
     * One feature, asked properly.
     *
     * `minApi` is the level that introduced the *constant*, which is what makes the
     * "not exposed on this API level" case reportable at all -- see the class notes.
     * The platform's feature list is consulted as well as the boolean call, because the
     * two occasionally disagree on OEM builds and reporting the disagreement is more
     * useful than silently trusting one of them.
     */
    private fun fact(
        pm: PackageManager,
        declared: Map<String, FeatureInfo>,
        spec: FeatureSpec,
    ): Fact = probe.verdict(
        spec.label,
        "PackageManager.hasSystemFeature(${spec.constant})",
        minApi = spec.since,
        detail = spec.name,
        searchTerms = spec.searchTerms + spec.name,
    ) {
        val info = declared[spec.name]
        val present = pm.hasSystemFeature(spec.name)
        when {
            present && info != null -> Probe.Verdict.yes(
                describeVersion(spec, info.version),
                "${spec.name} — declared, and confirmed by the platform's feature list.",
            )
            present -> Probe.Verdict.yes(
                "Present",
                "${spec.name} — hasSystemFeature says yes. The name is absent from " +
                    "getSystemAvailableFeatures(), which some OEM builds do; the " +
                    "affirmative call is taken as the answer.",
            )
            info != null -> Probe.Verdict.partial(
                "Declared in the feature list only",
                "${spec.name} appears in getSystemAvailableFeatures() while " +
                    "hasSystemFeature() says no. That contradiction is the device's, " +
                    "not this app's, and both halves are shown rather than one being " +
                    "picked as the truth.",
            )
            else -> Probe.Verdict.no(
                "Queried — not declared",
                "${spec.name} is absent from this device. Android reports presence " +
                    "only, never the reason, so this does not distinguish missing " +
                    "hardware from hardware the OEM did not declare.",
            )
        }
    }

    /**
     * Some features carry a number in `FeatureInfo.version` and mean something
     * different by it: a Vulkan API version is a packed `VK_MAKE_VERSION`, a deqp
     * level is a date, a keystore version names a KeyMint or Keymaster release. A
     * version of zero is only meaningful for the Vulkan levels, where level 0 is a
     * real baseline; everywhere else it means the feature simply carries no version.
     */
    private fun describeVersion(spec: FeatureSpec, version: Int): String {
        if (!spec.versioned) return "Present"
        return when (spec.constant) {
            "FEATURE_VULKAN_HARDWARE_VERSION" ->
                if (version > 0) "Present — Vulkan ${Format.vulkanVersion(version)}" else "Present"
            "FEATURE_VULKAN_HARDWARE_LEVEL" -> "Present — hardware level $version"
            "FEATURE_VULKAN_HARDWARE_COMPUTE" -> "Present — compute level $version"
            "FEATURE_VULKAN_DEQP_LEVEL", "FEATURE_OPENGLES_DEQP_LEVEL" -> when {
                version <= 0 -> "Present"
                else -> "Present — deqp level $version" +
                    (deqpDate(version)?.let { " ($it)" } ?: "")
            }
            "FEATURE_HARDWARE_KEYSTORE", "FEATURE_STRONGBOX_KEYSTORE" ->
                if (version > 0) {
                    "Present — ${SystemFeatures.keystoreVersionName(version)}"
                } else {
                    "Present"
                }
            else -> if (version > 0) "Present — version $version" else "Present"
        }
    }

    // ------------------------------------------------------------------- vendor

    /**
     * Features the platform declares that no AOSP constant names.
     *
     * These are where a manufacturer records its own hardware -- a Samsung S Pen, a
     * Sony camera mode, a Huawei NPU. They are grouped by namespace so the list reads
     * as something structured rather than a wall of reverse-DNS strings, and nothing is
     * truncated: an OEM build declaring two hundred of these gets two hundred rows.
     */
    private fun vendorSection(declared: Map<String, FeatureInfo>, extra: List<String>): Section {
        if (extra.isEmpty()) {
            return Section(
                id = "features-vendor",
                title = "Vendor and OEM features",
                subtitle = "None beyond the AOSP set",
                facts = listOf(
                    probe.value(
                        "Features outside the AOSP set",
                        "PackageManager.getSystemAvailableFeatures()",
                        searchTerms = listOf("vendor features", "oem features"),
                        detail = "Every feature this device declares is one Android " +
                            "itself defines. Typical of an AOSP or Pixel-style build.",
                    ) { "None" },
                ),
            )
        }
        val byNamespace = extra.groupBy { namespaceOf(it) }.toSortedMap()
        return Section(
            id = "features-vendor",
            title = "Vendor and OEM features",
            subtitle = "${extra.size} across ${byNamespace.size} namespaces",
            facts = emptyList(),
            children = byNamespace.map { (namespace, names) ->
                Section(
                    id = "features-vendor-${namespace.replace('.', '-')}",
                    title = namespace,
                    subtitle = "${names.size} declared",
                    facts = names.map { name ->
                        val version = declared[name]?.version ?: 0
                        probe.value(
                            name,
                            "PackageManager.getSystemAvailableFeatures()",
                            searchTerms = listOf(
                                namespace,
                                name.substringAfterLast('.').replace('_', ' '),
                            ),
                            detail = "Declared by this build and not defined by AOSP, so " +
                                "only the vendor knows what it guarantees. The name is " +
                                "shown exactly as reported.",
                        ) {
                            if (version > 0) "Declared, version $version" else "Declared"
                        }
                    },
                )
            },
        )
    }

    /** The first two reverse-DNS segments, which is the vendor in practice. */
    private fun namespaceOf(feature: String): String {
        val parts = feature.split('.')
        return when {
            parts.size >= 2 -> "${parts[0]}.${parts[1]}"
            parts.isNotEmpty() -> parts[0]
            else -> "(unnamed)"
        }
    }

    // ---------------------------------------------------------------- libraries

    /**
     * Optional shared libraries on the image.
     *
     * A genuine capability list rather than a curiosity: an app that links
     * `com.google.android.maps` or a vendor camera library will not run without the
     * entry being here. No permission is involved and the names are the same ones
     * `pm list libraries` prints.
     */
    private fun sharedLibraries(pm: PackageManager): Section {
        val libraries = probe.attempt(emptyList<String>()) {
            pm.systemSharedLibraryNames?.filterNotNull()?.sorted() ?: emptyList()
        }
        return Section(
            id = "features-libraries",
            title = "Shared libraries",
            subtitle = "PackageManager.getSystemSharedLibraryNames()",
            facts = listOf(
                probe.value(
                    "Optional libraries on this image",
                    "PackageManager.getSystemSharedLibraryNames()",
                    searchTerms = listOf("shared libraries", "libraries", "uses-library"),
                    detail = "Libraries an app can declare with <uses-library>. Missing " +
                        "one is why an app that installs elsewhere refuses to start here.",
                ) { libraries.size.takeIf { it > 0 }?.toString() },
            ) + libraries.map { library ->
                probe.value(
                    library,
                    "PackageManager.getSystemSharedLibraryNames()",
                    searchTerms = listOf(library.substringAfterLast('.')),
                ) { "Available" }
            },
        )
    }

    // ------------------------------------------------------------------- limits

    private fun limits() = Section(
        id = "features-limits",
        title = "What the feature list will not say",
        subtitle = "Asked for completeness",
        facts = listOf(
            probe.notExposedByAndroid(
                "Why a feature is absent",
                "hasSystemFeature() returns a bare boolean. There is no API that " +
                    "distinguishes hardware that is genuinely missing from hardware " +
                    "the OEM chose not to declare, and both are common. Where a " +
                    "dedicated lab can query the hardware itself — the sensor list, " +
                    "the camera characteristics, the codec list — it is the better " +
                    "answer",
                searchTerms = listOf("why absent", "missing feature", "not declared"),
            ),
            probe.notExposedByAndroid(
                "Which features the CDD requires of this device",
                "the Compatibility Definition Document ties required features to a " +
                    "device class and screen size, and none of that mapping is " +
                    "queryable. Whether an absent feature is a permitted omission or " +
                    "a compliance failure cannot be determined on-device",
                searchTerms = listOf("cdd", "compatibility", "required features"),
            ),
            probe.notExposedByAndroid(
                "Play Protect certification status",
                "certification is a server-side attestation about the build, not a " +
                    "system feature. No PackageManager call reports it, and reading it " +
                    "would need a network round trip this app does not make",
                searchTerms = listOf("play protect", "certified", "gms", "safetynet"),
            ),
            probe.notExposedByAndroid(
                "Feature versions for unversioned features",
                "most feature flags carry no version at all — FeatureInfo.version is " +
                    "zero for them. Where a version exists it is shown; where it does " +
                    "not, there is nothing finer-grained than present or absent to " +
                    "report",
                searchTerms = listOf("feature version", "featureinfo version"),
            ),
        ),
    )

    // -------------------------------------------------------------------- table

    /** A section heading for the feature table. */
    internal data class FeatureGroup(val id: String, val title: String)

    /**
     * One feature constant.
     *
     * @param constant the SDK field name, printed in the provenance so the row can be
     *   checked against the platform documentation
     * @param name the feature string itself, which is what is actually queried
     * @param since the API level that introduced [constant]. This is the gate: below
     *   it, `hasSystemFeature` would answer a question it does not understand.
     * @param versioned true when `FeatureInfo.version` carries a meaningful number
     */
    internal data class FeatureSpec(
        val group: String,
        val constant: String,
        val name: String,
        val label: String,
        val since: Int,
        val versioned: Boolean = false,
        val searchTerms: List<String> = emptyList(),
    )

    internal companion object {

        /**
         * CTS encodes a deqp conformance level as a date packed into an integer: the
         * year in the high sixteen bits, then month and day a byte each. Decoded only
         * when the result is a plausible date -- an unrecognised encoding is left as
         * the raw number rather than rendered as a nonsense day in year 3.
         *
         * On the companion rather than the instance so a JVM test can exercise it
         * directly: constructing a [FeaturesDetector] needs a [Context], and this
         * arithmetic needs nothing at all.
         */
        internal fun deqpDate(packed: Int): String? {
            val year = packed shr 16
            val month = (packed shr 8) and 0xFF
            val day = packed and 0xFF
            if (year !in 2000..2100 || month !in 1..12 || day !in 1..31) return null
            return String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
        }


        val GROUPS = listOf(
            FeatureGroup("camera", "Camera"),
            FeatureGroup("sensors", "Sensors"),
            FeatureGroup("biometrics", "Biometrics"),
            FeatureGroup("telephony", "Telephony"),
            FeatureGroup("radio", "Radios and networking"),
            FeatureGroup("nfc", "NFC and secure element"),
            FeatureGroup("location", "Location"),
            FeatureGroup("audio", "Audio"),
            FeatureGroup("graphics", "Graphics"),
            FeatureGroup("security", "Security and keystore"),
            FeatureGroup("input", "Input and windowing"),
            FeatureGroup("usb", "USB"),
            FeatureGroup("vr", "Virtual reality"),
            FeatureGroup("form", "Form factor"),
            FeatureGroup("memory", "Memory class"),
            FeatureGroup("system", "Platform and system"),
        )

        /**
         * Every `PackageManager.FEATURE_*` constant in the compile SDK, with the API
         * level that introduced it.
         *
         * Generated from the SDK itself -- the constants from `android.jar`, the `since`
         * values from the `data/api-versions.xml` beside it -- rather than typed from
         * memory, because a wrong gate here would produce exactly the confident-and-false
         * "unsupported" this lab exists to prevent. The labels are written by hand; every
         * other field is the platform's.
         */
        val FEATURES = listOf(
            // Camera
            FeatureSpec(
                group = "camera",
                constant = "FEATURE_CAMERA",
                name = PackageManager.FEATURE_CAMERA,
                label = "Rear-facing camera",
                since = 7,
                searchTerms = listOf("camera"),
            ),
            FeatureSpec(
                group = "camera",
                constant = "FEATURE_CAMERA_AUTOFOCUS",
                name = PackageManager.FEATURE_CAMERA_AUTOFOCUS,
                label = "Camera autofocus",
                since = 7,
                searchTerms = listOf("camera autofocus", "autofocus"),
            ),
            FeatureSpec(
                group = "camera",
                constant = "FEATURE_CAMERA_FLASH",
                name = PackageManager.FEATURE_CAMERA_FLASH,
                label = "Camera flash",
                since = 7,
                searchTerms = listOf("camera flash", "flash"),
            ),
            FeatureSpec(
                group = "camera",
                constant = "FEATURE_CAMERA_FRONT",
                name = PackageManager.FEATURE_CAMERA_FRONT,
                label = "Front-facing camera",
                since = 9,
                searchTerms = listOf("camera front", "front"),
            ),
            FeatureSpec(
                group = "camera",
                constant = "FEATURE_CAMERA_ANY",
                name = PackageManager.FEATURE_CAMERA_ANY,
                label = "Any camera (including external)",
                since = 17,
                searchTerms = listOf("camera any", "any"),
            ),
            FeatureSpec(
                group = "camera",
                constant = "FEATURE_CAMERA_EXTERNAL",
                name = PackageManager.FEATURE_CAMERA_EXTERNAL,
                label = "External camera",
                since = 20,
                searchTerms = listOf("camera external", "external"),
            ),
            FeatureSpec(
                group = "camera",
                constant = "FEATURE_CAMERA_CAPABILITY_MANUAL_POST_PROCESSING",
                name = PackageManager.FEATURE_CAMERA_CAPABILITY_MANUAL_POST_PROCESSING,
                label = "Manual post-processing control",
                since = 21,
                searchTerms = listOf("camera capability manual post processing", "manual post processing"),
            ),
            FeatureSpec(
                group = "camera",
                constant = "FEATURE_CAMERA_CAPABILITY_MANUAL_SENSOR",
                name = PackageManager.FEATURE_CAMERA_CAPABILITY_MANUAL_SENSOR,
                label = "Manual sensor control",
                since = 21,
                searchTerms = listOf("camera capability manual sensor", "manual sensor"),
            ),
            FeatureSpec(
                group = "camera",
                constant = "FEATURE_CAMERA_CAPABILITY_RAW",
                name = PackageManager.FEATURE_CAMERA_CAPABILITY_RAW,
                label = "RAW capture",
                since = 21,
                searchTerms = listOf("camera capability raw", "raw"),
            ),
            FeatureSpec(
                group = "camera",
                constant = "FEATURE_CAMERA_LEVEL_FULL",
                name = PackageManager.FEATURE_CAMERA_LEVEL_FULL,
                label = "A camera at hardware level FULL",
                since = 21,
                searchTerms = listOf("camera level full", "full"),
            ),
            FeatureSpec(
                group = "camera",
                constant = "FEATURE_CAMERA_AR",
                name = PackageManager.FEATURE_CAMERA_AR,
                label = "Motion tracking camera (AR)",
                since = 28,
                searchTerms = listOf("camera ar", "ar"),
            ),
            FeatureSpec(
                group = "camera",
                constant = "FEATURE_CAMERA_CONCURRENT",
                name = PackageManager.FEATURE_CAMERA_CONCURRENT,
                label = "Concurrent front and rear capture",
                since = 30,
                searchTerms = listOf("camera concurrent", "concurrent"),
            ),
            // Sensors
            FeatureSpec(
                group = "sensors",
                constant = "FEATURE_SENSOR_LIGHT",
                name = PackageManager.FEATURE_SENSOR_LIGHT,
                label = "Ambient light sensor",
                since = 7,
                searchTerms = listOf("sensor light", "light"),
            ),
            FeatureSpec(
                group = "sensors",
                constant = "FEATURE_SENSOR_PROXIMITY",
                name = PackageManager.FEATURE_SENSOR_PROXIMITY,
                label = "Proximity sensor",
                since = 7,
                searchTerms = listOf("sensor proximity", "proximity"),
            ),
            FeatureSpec(
                group = "sensors",
                constant = "FEATURE_SENSOR_ACCELEROMETER",
                name = PackageManager.FEATURE_SENSOR_ACCELEROMETER,
                label = "Accelerometer",
                since = 8,
                searchTerms = listOf("sensor accelerometer", "accelerometer"),
            ),
            FeatureSpec(
                group = "sensors",
                constant = "FEATURE_SENSOR_COMPASS",
                name = PackageManager.FEATURE_SENSOR_COMPASS,
                label = "Magnetometer (compass)",
                since = 8,
                searchTerms = listOf("sensor compass", "compass"),
            ),
            FeatureSpec(
                group = "sensors",
                constant = "FEATURE_SENSOR_BAROMETER",
                name = PackageManager.FEATURE_SENSOR_BAROMETER,
                label = "Barometer",
                since = 9,
                searchTerms = listOf("sensor barometer", "barometer"),
            ),
            FeatureSpec(
                group = "sensors",
                constant = "FEATURE_SENSOR_GYROSCOPE",
                name = PackageManager.FEATURE_SENSOR_GYROSCOPE,
                label = "Gyroscope",
                since = 9,
                searchTerms = listOf("sensor gyroscope", "gyroscope"),
            ),
            FeatureSpec(
                group = "sensors",
                constant = "FEATURE_SENSOR_STEP_COUNTER",
                name = PackageManager.FEATURE_SENSOR_STEP_COUNTER,
                label = "Step counter",
                since = 19,
                searchTerms = listOf("sensor step counter", "stepcounter"),
            ),
            FeatureSpec(
                group = "sensors",
                constant = "FEATURE_SENSOR_STEP_DETECTOR",
                name = PackageManager.FEATURE_SENSOR_STEP_DETECTOR,
                label = "Step detector",
                since = 19,
                searchTerms = listOf("sensor step detector", "stepdetector"),
            ),
            FeatureSpec(
                group = "sensors",
                constant = "FEATURE_SENSOR_HEART_RATE",
                name = PackageManager.FEATURE_SENSOR_HEART_RATE,
                label = "Heart-rate sensor",
                since = 20,
                searchTerms = listOf("sensor heart rate", "heartrate"),
            ),
            FeatureSpec(
                group = "sensors",
                constant = "FEATURE_SENSOR_AMBIENT_TEMPERATURE",
                name = PackageManager.FEATURE_SENSOR_AMBIENT_TEMPERATURE,
                label = "Ambient thermometer",
                since = 21,
                searchTerms = listOf("sensor ambient temperature", "ambient temperature"),
            ),
            FeatureSpec(
                group = "sensors",
                constant = "FEATURE_SENSOR_HEART_RATE_ECG",
                name = PackageManager.FEATURE_SENSOR_HEART_RATE_ECG,
                label = "Heart-rate sensor (ECG)",
                since = 21,
                searchTerms = listOf("sensor heart rate ecg", "ecg"),
            ),
            FeatureSpec(
                group = "sensors",
                constant = "FEATURE_SENSOR_RELATIVE_HUMIDITY",
                name = PackageManager.FEATURE_SENSOR_RELATIVE_HUMIDITY,
                label = "Humidity sensor",
                since = 21,
                searchTerms = listOf("sensor relative humidity", "relative humidity"),
            ),
            FeatureSpec(
                group = "sensors",
                constant = "FEATURE_HIFI_SENSORS",
                name = PackageManager.FEATURE_HIFI_SENSORS,
                label = "High-fidelity sensors",
                since = 23,
                searchTerms = listOf("hifi sensors"),
            ),
            FeatureSpec(
                group = "sensors",
                constant = "FEATURE_SENSOR_HINGE_ANGLE",
                name = PackageManager.FEATURE_SENSOR_HINGE_ANGLE,
                label = "Hinge angle sensor (foldable)",
                since = 30,
                searchTerms = listOf("sensor hinge angle", "hinge angle"),
            ),
            FeatureSpec(
                group = "sensors",
                constant = "FEATURE_SENSOR_ACCELEROMETER_LIMITED_AXES",
                name = PackageManager.FEATURE_SENSOR_ACCELEROMETER_LIMITED_AXES,
                label = "Limited-axes accelerometer",
                since = 33,
                searchTerms = listOf("sensor accelerometer limited axes", "accelerometer limited axes"),
            ),
            FeatureSpec(
                group = "sensors",
                constant = "FEATURE_SENSOR_ACCELEROMETER_LIMITED_AXES_UNCALIBRATED",
                name = PackageManager.FEATURE_SENSOR_ACCELEROMETER_LIMITED_AXES_UNCALIBRATED,
                label = "Limited-axes accelerometer, uncalibrated",
                since = 33,
                searchTerms = listOf("sensor accelerometer limited axes uncalibrated", "accelerometer limited axes uncalibrated"),
            ),
            FeatureSpec(
                group = "sensors",
                constant = "FEATURE_SENSOR_DYNAMIC_HEAD_TRACKER",
                name = PackageManager.FEATURE_SENSOR_DYNAMIC_HEAD_TRACKER,
                label = "Head tracker (spatial audio)",
                since = 33,
                versioned = true,
                searchTerms = listOf("sensor dynamic head tracker", "head tracker"),
            ),
            FeatureSpec(
                group = "sensors",
                constant = "FEATURE_SENSOR_GYROSCOPE_LIMITED_AXES",
                name = PackageManager.FEATURE_SENSOR_GYROSCOPE_LIMITED_AXES,
                label = "Limited-axes gyroscope",
                since = 33,
                searchTerms = listOf("sensor gyroscope limited axes", "gyroscope limited axes"),
            ),
            FeatureSpec(
                group = "sensors",
                constant = "FEATURE_SENSOR_GYROSCOPE_LIMITED_AXES_UNCALIBRATED",
                name = PackageManager.FEATURE_SENSOR_GYROSCOPE_LIMITED_AXES_UNCALIBRATED,
                label = "Limited-axes gyroscope, uncalibrated",
                since = 33,
                searchTerms = listOf("sensor gyroscope limited axes uncalibrated", "gyroscope limited axes uncalibrated"),
            ),
            FeatureSpec(
                group = "sensors",
                constant = "FEATURE_SENSOR_HEADING",
                name = PackageManager.FEATURE_SENSOR_HEADING,
                label = "Heading sensor",
                since = 33,
                searchTerms = listOf("sensor heading", "heading"),
            ),
            // Biometrics
            FeatureSpec(
                group = "biometrics",
                constant = "FEATURE_FINGERPRINT",
                name = PackageManager.FEATURE_FINGERPRINT,
                label = "Fingerprint hardware",
                since = 23,
                searchTerms = listOf("fingerprint"),
            ),
            FeatureSpec(
                group = "biometrics",
                constant = "FEATURE_FACE",
                name = PackageManager.FEATURE_FACE,
                label = "Face authentication hardware",
                since = 29,
                searchTerms = listOf("face"),
            ),
            FeatureSpec(
                group = "biometrics",
                constant = "FEATURE_IRIS",
                name = PackageManager.FEATURE_IRIS,
                label = "Iris authentication hardware",
                since = 29,
                searchTerms = listOf("iris"),
            ),
            // Telephony
            FeatureSpec(
                group = "telephony",
                constant = "FEATURE_TELEPHONY",
                name = PackageManager.FEATURE_TELEPHONY,
                label = "Telephony radio",
                since = 7,
                searchTerms = listOf("telephony"),
            ),
            FeatureSpec(
                group = "telephony",
                constant = "FEATURE_TELEPHONY_CDMA",
                name = PackageManager.FEATURE_TELEPHONY_CDMA,
                label = "CDMA radio",
                since = 7,
                searchTerms = listOf("telephony cdma", "cdma"),
            ),
            FeatureSpec(
                group = "telephony",
                constant = "FEATURE_TELEPHONY_GSM",
                name = PackageManager.FEATURE_TELEPHONY_GSM,
                label = "GSM radio",
                since = 7,
                searchTerms = listOf("telephony gsm", "gsm"),
            ),
            FeatureSpec(
                group = "telephony",
                constant = "FEATURE_SIP",
                name = PackageManager.FEATURE_SIP,
                label = "SIP",
                since = 9,
                searchTerms = listOf("sip"),
            ),
            FeatureSpec(
                group = "telephony",
                constant = "FEATURE_SIP_VOIP",
                name = PackageManager.FEATURE_SIP_VOIP,
                label = "SIP-based VoIP",
                since = 9,
                searchTerms = listOf("sip voip", "voip"),
            ),
            FeatureSpec(
                group = "telephony",
                constant = "FEATURE_CONNECTION_SERVICE",
                name = PackageManager.FEATURE_CONNECTION_SERVICE,
                label = "ConnectionService (VoIP calling)",
                since = 21,
                searchTerms = listOf("connection service", "connectionservice"),
            ),
            FeatureSpec(
                group = "telephony",
                constant = "FEATURE_TELEPHONY_EUICC",
                name = PackageManager.FEATURE_TELEPHONY_EUICC,
                label = "eUICC (embedded SIM)",
                since = 28,
                versioned = true,
                searchTerms = listOf("telephony euicc", "euicc"),
            ),
            FeatureSpec(
                group = "telephony",
                constant = "FEATURE_TELEPHONY_MBMS",
                name = PackageManager.FEATURE_TELEPHONY_MBMS,
                label = "Cell-broadcast (MBMS)",
                since = 28,
                searchTerms = listOf("telephony mbms", "mbms"),
            ),
            FeatureSpec(
                group = "telephony",
                constant = "FEATURE_TELEPHONY_IMS",
                name = PackageManager.FEATURE_TELEPHONY_IMS,
                label = "IMS",
                since = 29,
                searchTerms = listOf("telephony ims", "ims"),
            ),
            FeatureSpec(
                group = "telephony",
                constant = "FEATURE_TELECOM",
                name = PackageManager.FEATURE_TELECOM,
                label = "Telecom framework",
                since = 33,
                searchTerms = listOf("telecom"),
            ),
            FeatureSpec(
                group = "telephony",
                constant = "FEATURE_TELEPHONY_CALLING",
                name = PackageManager.FEATURE_TELEPHONY_CALLING,
                label = "Voice calling",
                since = 33,
                searchTerms = listOf("telephony calling", "calling"),
            ),
            FeatureSpec(
                group = "telephony",
                constant = "FEATURE_TELEPHONY_DATA",
                name = PackageManager.FEATURE_TELEPHONY_DATA,
                label = "Cellular data",
                since = 33,
                searchTerms = listOf("telephony data", "data"),
            ),
            FeatureSpec(
                group = "telephony",
                constant = "FEATURE_TELEPHONY_EUICC_MEP",
                name = PackageManager.FEATURE_TELEPHONY_EUICC_MEP,
                label = "eUICC multiple enabled profiles",
                since = 33,
                searchTerms = listOf("telephony euicc mep", "mep"),
            ),
            FeatureSpec(
                group = "telephony",
                constant = "FEATURE_TELEPHONY_MESSAGING",
                name = PackageManager.FEATURE_TELEPHONY_MESSAGING,
                label = "SMS and MMS",
                since = 33,
                searchTerms = listOf("telephony messaging", "messaging"),
            ),
            FeatureSpec(
                group = "telephony",
                constant = "FEATURE_TELEPHONY_RADIO_ACCESS",
                name = PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS,
                label = "Cellular radio access",
                since = 33,
                searchTerms = listOf("telephony radio access", "access"),
            ),
            FeatureSpec(
                group = "telephony",
                constant = "FEATURE_TELEPHONY_SUBSCRIPTION",
                name = PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION,
                label = "Cellular subscriptions",
                since = 33,
                searchTerms = listOf("telephony subscription", "subscription"),
            ),
            // Radios and networking
            FeatureSpec(
                group = "radio",
                constant = "FEATURE_BLUETOOTH",
                name = PackageManager.FEATURE_BLUETOOTH,
                label = "Bluetooth",
                since = 8,
                searchTerms = listOf("bluetooth"),
            ),
            FeatureSpec(
                group = "radio",
                constant = "FEATURE_WIFI",
                name = PackageManager.FEATURE_WIFI,
                label = "Wi-Fi",
                since = 8,
                searchTerms = listOf("wifi"),
            ),
            FeatureSpec(
                group = "radio",
                constant = "FEATURE_WIFI_DIRECT",
                name = PackageManager.FEATURE_WIFI_DIRECT,
                label = "Wi-Fi Direct",
                since = 14,
                searchTerms = listOf("wifi direct", "direct"),
            ),
            FeatureSpec(
                group = "radio",
                constant = "FEATURE_BLUETOOTH_LE",
                name = PackageManager.FEATURE_BLUETOOTH_LE,
                label = "Bluetooth Low Energy",
                since = 18,
                searchTerms = listOf("bluetooth le"),
            ),
            FeatureSpec(
                group = "radio",
                constant = "FEATURE_CONSUMER_IR",
                name = PackageManager.FEATURE_CONSUMER_IR,
                label = "Consumer infrared blaster",
                since = 19,
                searchTerms = listOf("consumer ir", "consumerir"),
            ),
            FeatureSpec(
                group = "radio",
                constant = "FEATURE_ETHERNET",
                name = PackageManager.FEATURE_ETHERNET,
                label = "Ethernet",
                since = 24,
                searchTerms = listOf("ethernet"),
            ),
            FeatureSpec(
                group = "radio",
                constant = "FEATURE_WIFI_AWARE",
                name = PackageManager.FEATURE_WIFI_AWARE,
                label = "Wi-Fi Aware (NAN)",
                since = 26,
                searchTerms = listOf("wifi aware", "aware"),
            ),
            FeatureSpec(
                group = "radio",
                constant = "FEATURE_WIFI_PASSPOINT",
                name = PackageManager.FEATURE_WIFI_PASSPOINT,
                label = "Wi-Fi Passpoint",
                since = 27,
                searchTerms = listOf("wifi passpoint", "passpoint"),
            ),
            FeatureSpec(
                group = "radio",
                constant = "FEATURE_WIFI_RTT",
                name = PackageManager.FEATURE_WIFI_RTT,
                label = "Wi-Fi RTT ranging (802.11mc)",
                since = 28,
                searchTerms = listOf("wifi rtt", "rtt"),
            ),
            FeatureSpec(
                group = "radio",
                constant = "FEATURE_IPSEC_TUNNELS",
                name = PackageManager.FEATURE_IPSEC_TUNNELS,
                label = "IPsec tunnels",
                since = 29,
                searchTerms = listOf("ipsec tunnels"),
            ),
            FeatureSpec(
                group = "radio",
                constant = "FEATURE_IPSEC_TUNNEL_MIGRATION",
                name = PackageManager.FEATURE_IPSEC_TUNNEL_MIGRATION,
                label = "IPsec tunnel migration",
                since = 34,
                searchTerms = listOf("ipsec tunnel migration"),
            ),
            FeatureSpec(
                group = "radio",
                constant = "FEATURE_UWB",
                name = PackageManager.FEATURE_UWB,
                label = "Ultra-wideband",
                since = 34,
                searchTerms = listOf("uwb"),
            ),
            // NFC and secure element
            FeatureSpec(
                group = "nfc",
                constant = "FEATURE_NFC",
                name = PackageManager.FEATURE_NFC,
                label = "NFC",
                since = 9,
                searchTerms = listOf("nfc"),
            ),
            FeatureSpec(
                group = "nfc",
                constant = "FEATURE_NFC_HOST_CARD_EMULATION",
                name = PackageManager.FEATURE_NFC_HOST_CARD_EMULATION,
                label = "Host card emulation (ISO-DEP)",
                since = 19,
                searchTerms = listOf("nfc host card emulation", "hce"),
            ),
            FeatureSpec(
                group = "nfc",
                constant = "FEATURE_NFC_HOST_CARD_EMULATION_NFCF",
                name = PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF,
                label = "Host card emulation (NFC-F)",
                since = 24,
                searchTerms = listOf("nfc host card emulation nfcf", "hcef"),
            ),
            FeatureSpec(
                group = "nfc",
                constant = "FEATURE_NFC_BEAM",
                name = PackageManager.FEATURE_NFC_BEAM,
                label = "Android Beam",
                since = 29,
                searchTerms = listOf("nfc beam", "beam"),
            ),
            FeatureSpec(
                group = "nfc",
                constant = "FEATURE_NFC_OFF_HOST_CARD_EMULATION_ESE",
                name = PackageManager.FEATURE_NFC_OFF_HOST_CARD_EMULATION_ESE,
                label = "Off-host card emulation via eSE",
                since = 29,
                searchTerms = listOf("nfc off host card emulation ese", "ese"),
            ),
            FeatureSpec(
                group = "nfc",
                constant = "FEATURE_NFC_OFF_HOST_CARD_EMULATION_UICC",
                name = PackageManager.FEATURE_NFC_OFF_HOST_CARD_EMULATION_UICC,
                label = "Off-host card emulation via UICC",
                since = 29,
                searchTerms = listOf("nfc off host card emulation uicc", "uicc"),
            ),
            FeatureSpec(
                group = "nfc",
                constant = "FEATURE_SE_OMAPI_ESE",
                name = PackageManager.FEATURE_SE_OMAPI_ESE,
                label = "Embedded secure element (OMAPI)",
                since = 30,
                searchTerms = listOf("se omapi ese", "ese"),
            ),
            FeatureSpec(
                group = "nfc",
                constant = "FEATURE_SE_OMAPI_SD",
                name = PackageManager.FEATURE_SE_OMAPI_SD,
                label = "SD-card secure element (OMAPI)",
                since = 30,
                searchTerms = listOf("se omapi sd", "sd"),
            ),
            FeatureSpec(
                group = "nfc",
                constant = "FEATURE_SE_OMAPI_UICC",
                name = PackageManager.FEATURE_SE_OMAPI_UICC,
                label = "UICC secure element (OMAPI)",
                since = 30,
                searchTerms = listOf("se omapi uicc", "uicc"),
            ),
            // Location
            FeatureSpec(
                group = "location",
                constant = "FEATURE_LOCATION",
                name = PackageManager.FEATURE_LOCATION,
                label = "Location",
                since = 8,
                searchTerms = listOf("location"),
            ),
            FeatureSpec(
                group = "location",
                constant = "FEATURE_LOCATION_GPS",
                name = PackageManager.FEATURE_LOCATION_GPS,
                label = "GNSS receiver",
                since = 8,
                searchTerms = listOf("location gps", "gps"),
            ),
            FeatureSpec(
                group = "location",
                constant = "FEATURE_LOCATION_NETWORK",
                name = PackageManager.FEATURE_LOCATION_NETWORK,
                label = "Network-based location",
                since = 8,
                searchTerms = listOf("location network", "network"),
            ),
            FeatureSpec(
                group = "location",
                constant = "FEATURE_WALLET_LOCATION_BASED_SUGGESTIONS",
                name = PackageManager.FEATURE_WALLET_LOCATION_BASED_SUGGESTIONS,
                label = "Location-based wallet suggestions",
                since = 34,
                searchTerms = listOf("wallet location based suggestions"),
            ),
            // Audio
            FeatureSpec(
                group = "audio",
                constant = "FEATURE_MICROPHONE",
                name = PackageManager.FEATURE_MICROPHONE,
                label = "Microphone",
                since = 8,
                searchTerms = listOf("microphone"),
            ),
            FeatureSpec(
                group = "audio",
                constant = "FEATURE_AUDIO_LOW_LATENCY",
                name = PackageManager.FEATURE_AUDIO_LOW_LATENCY,
                label = "Low-latency audio",
                since = 9,
                searchTerms = listOf("audio low latency", "low latency"),
            ),
            FeatureSpec(
                group = "audio",
                constant = "FEATURE_AUDIO_OUTPUT",
                name = PackageManager.FEATURE_AUDIO_OUTPUT,
                label = "Audio output",
                since = 21,
                searchTerms = listOf("audio output", "output"),
            ),
            FeatureSpec(
                group = "audio",
                constant = "FEATURE_AUDIO_PRO",
                name = PackageManager.FEATURE_AUDIO_PRO,
                label = "Professional audio",
                since = 23,
                searchTerms = listOf("audio pro", "pro"),
            ),
            FeatureSpec(
                group = "audio",
                constant = "FEATURE_MIDI",
                name = PackageManager.FEATURE_MIDI,
                label = "MIDI over USB or Bluetooth",
                since = 23,
                searchTerms = listOf("midi"),
            ),
            // Graphics
            FeatureSpec(
                group = "graphics",
                constant = "FEATURE_OPENGLES_EXTENSION_PACK",
                name = PackageManager.FEATURE_OPENGLES_EXTENSION_PACK,
                label = "Android Extension Pack (OpenGL ES)",
                since = 21,
                searchTerms = listOf("opengles extension pack", "aep"),
            ),
            FeatureSpec(
                group = "graphics",
                constant = "FEATURE_VULKAN_HARDWARE_LEVEL",
                name = PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL,
                label = "Vulkan hardware level",
                since = 24,
                versioned = true,
                searchTerms = listOf("vulkan hardware level", "level"),
            ),
            FeatureSpec(
                group = "graphics",
                constant = "FEATURE_VULKAN_HARDWARE_VERSION",
                name = PackageManager.FEATURE_VULKAN_HARDWARE_VERSION,
                label = "Vulkan hardware version",
                since = 24,
                versioned = true,
                searchTerms = listOf("vulkan hardware version", "version"),
            ),
            FeatureSpec(
                group = "graphics",
                constant = "FEATURE_VULKAN_HARDWARE_COMPUTE",
                name = PackageManager.FEATURE_VULKAN_HARDWARE_COMPUTE,
                label = "Vulkan compute level",
                since = 26,
                versioned = true,
                searchTerms = listOf("vulkan hardware compute", "compute"),
            ),
            FeatureSpec(
                group = "graphics",
                constant = "FEATURE_VULKAN_DEQP_LEVEL",
                name = PackageManager.FEATURE_VULKAN_DEQP_LEVEL,
                label = "Vulkan deqp conformance level",
                since = 30,
                versioned = true,
                searchTerms = listOf("vulkan deqp level", "level"),
            ),
            FeatureSpec(
                group = "graphics",
                constant = "FEATURE_OPENGLES_DEQP_LEVEL",
                name = PackageManager.FEATURE_OPENGLES_DEQP_LEVEL,
                label = "OpenGL ES deqp conformance level",
                since = 31,
                versioned = true,
                searchTerms = listOf("opengles deqp level", "level"),
            ),
            // Security and keystore
            FeatureSpec(
                group = "security",
                constant = "FEATURE_DEVICE_ADMIN",
                name = PackageManager.FEATURE_DEVICE_ADMIN,
                label = "Device administration",
                since = 19,
                searchTerms = listOf("device admin"),
            ),
            FeatureSpec(
                group = "security",
                constant = "FEATURE_MANAGED_USERS",
                name = PackageManager.FEATURE_MANAGED_USERS,
                label = "Managed profiles",
                since = 21,
                searchTerms = listOf("managed users"),
            ),
            FeatureSpec(
                group = "security",
                constant = "FEATURE_SECURELY_REMOVES_USERS",
                name = PackageManager.FEATURE_SECURELY_REMOVES_USERS,
                label = "Securely removes users",
                since = 21,
                searchTerms = listOf("securely removes users"),
            ),
            FeatureSpec(
                group = "security",
                constant = "FEATURE_VERIFIED_BOOT",
                name = PackageManager.FEATURE_VERIFIED_BOOT,
                label = "Verified boot",
                since = 21,
                searchTerms = listOf("verified boot"),
            ),
            FeatureSpec(
                group = "security",
                constant = "FEATURE_STRONGBOX_KEYSTORE",
                name = PackageManager.FEATURE_STRONGBOX_KEYSTORE,
                label = "StrongBox keystore",
                since = 28,
                versioned = true,
                searchTerms = listOf("strongbox keystore"),
            ),
            FeatureSpec(
                group = "security",
                constant = "FEATURE_SECURE_LOCK_SCREEN",
                name = PackageManager.FEATURE_SECURE_LOCK_SCREEN,
                label = "Secure lock screen available",
                since = 29,
                searchTerms = listOf("secure lock screen"),
            ),
            FeatureSpec(
                group = "security",
                constant = "FEATURE_HARDWARE_KEYSTORE",
                name = PackageManager.FEATURE_HARDWARE_KEYSTORE,
                label = "Hardware-backed keystore (KeyMint version)",
                since = 31,
                versioned = true,
                searchTerms = listOf("hardware keystore"),
            ),
            FeatureSpec(
                group = "security",
                constant = "FEATURE_IDENTITY_CREDENTIAL_HARDWARE",
                name = PackageManager.FEATURE_IDENTITY_CREDENTIAL_HARDWARE,
                label = "Identity credential hardware",
                since = 31,
                versioned = true,
                searchTerms = listOf("identity credential hardware", "identity credential"),
            ),
            FeatureSpec(
                group = "security",
                constant = "FEATURE_IDENTITY_CREDENTIAL_HARDWARE_DIRECT_ACCESS",
                name = PackageManager.FEATURE_IDENTITY_CREDENTIAL_HARDWARE_DIRECT_ACCESS,
                label = "Identity credential direct access",
                since = 31,
                versioned = true,
                searchTerms = listOf("identity credential hardware direct access", "identity credential direct access"),
            ),
            FeatureSpec(
                group = "security",
                constant = "FEATURE_KEYSTORE_APP_ATTEST_KEY",
                name = PackageManager.FEATURE_KEYSTORE_APP_ATTEST_KEY,
                label = "Keystore app attest key",
                since = 31,
                searchTerms = listOf("keystore app attest key", "app attest key"),
            ),
            FeatureSpec(
                group = "security",
                constant = "FEATURE_KEYSTORE_LIMITED_USE_KEY",
                name = PackageManager.FEATURE_KEYSTORE_LIMITED_USE_KEY,
                label = "Keystore limited-use keys",
                since = 31,
                searchTerms = listOf("keystore limited use key", "limited use key"),
            ),
            FeatureSpec(
                group = "security",
                constant = "FEATURE_KEYSTORE_SINGLE_USE_KEY",
                name = PackageManager.FEATURE_KEYSTORE_SINGLE_USE_KEY,
                label = "Keystore single-use keys",
                since = 31,
                searchTerms = listOf("keystore single use key", "single use key"),
            ),
            FeatureSpec(
                group = "security",
                constant = "FEATURE_SECURITY_MODEL_COMPATIBLE",
                name = PackageManager.FEATURE_SECURITY_MODEL_COMPATIBLE,
                label = "Compatible with the Android security model",
                since = 31,
                searchTerms = listOf("security model compatible", "compatible"),
            ),
            FeatureSpec(
                group = "security",
                constant = "FEATURE_CREDENTIALS",
                name = PackageManager.FEATURE_CREDENTIALS,
                label = "Credential Manager",
                since = 34,
                searchTerms = listOf("credentials"),
            ),
            FeatureSpec(
                group = "security",
                constant = "FEATURE_DEVICE_LOCK",
                name = PackageManager.FEATURE_DEVICE_LOCK,
                label = "Device lock service",
                since = 34,
                searchTerms = listOf("device lock"),
            ),
            // Input and windowing
            FeatureSpec(
                group = "input",
                constant = "FEATURE_TOUCHSCREEN_MULTITOUCH",
                name = PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH,
                label = "Multitouch",
                since = 7,
                searchTerms = listOf("touchscreen multitouch", "multitouch"),
            ),
            FeatureSpec(
                group = "input",
                constant = "FEATURE_TOUCHSCREEN",
                name = PackageManager.FEATURE_TOUCHSCREEN,
                label = "Touchscreen",
                since = 8,
                searchTerms = listOf("touchscreen"),
            ),
            FeatureSpec(
                group = "input",
                constant = "FEATURE_TOUCHSCREEN_MULTITOUCH_DISTINCT",
                name = PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_DISTINCT,
                label = "Multitouch, two distinct points",
                since = 8,
                searchTerms = listOf("touchscreen multitouch distinct", "distinct"),
            ),
            FeatureSpec(
                group = "input",
                constant = "FEATURE_TOUCHSCREEN_MULTITOUCH_JAZZHAND",
                name = PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_JAZZHAND,
                label = "Multitouch, five distinct points",
                since = 9,
                searchTerms = listOf("touchscreen multitouch jazzhand", "jazzhand"),
            ),
            FeatureSpec(
                group = "input",
                constant = "FEATURE_FAKETOUCH",
                name = PackageManager.FEATURE_FAKETOUCH,
                label = "Emulated touch (pointer device)",
                since = 11,
                searchTerms = listOf("faketouch"),
            ),
            FeatureSpec(
                group = "input",
                constant = "FEATURE_FAKETOUCH_MULTITOUCH_DISTINCT",
                name = PackageManager.FEATURE_FAKETOUCH_MULTITOUCH_DISTINCT,
                label = "Emulated touch, two points",
                since = 13,
                searchTerms = listOf("faketouch multitouch distinct", "distinct"),
            ),
            FeatureSpec(
                group = "input",
                constant = "FEATURE_FAKETOUCH_MULTITOUCH_JAZZHAND",
                name = PackageManager.FEATURE_FAKETOUCH_MULTITOUCH_JAZZHAND,
                label = "Emulated touch, five points",
                since = 13,
                searchTerms = listOf("faketouch multitouch jazzhand", "jazzhand"),
            ),
            FeatureSpec(
                group = "input",
                constant = "FEATURE_SCREEN_LANDSCAPE",
                name = PackageManager.FEATURE_SCREEN_LANDSCAPE,
                label = "Landscape orientation",
                since = 13,
                searchTerms = listOf("screen landscape", "landscape"),
            ),
            FeatureSpec(
                group = "input",
                constant = "FEATURE_SCREEN_PORTRAIT",
                name = PackageManager.FEATURE_SCREEN_PORTRAIT,
                label = "Portrait orientation",
                since = 13,
                searchTerms = listOf("screen portrait", "portrait"),
            ),
            FeatureSpec(
                group = "input",
                constant = "FEATURE_INPUT_METHODS",
                name = PackageManager.FEATURE_INPUT_METHODS,
                label = "Third-party input methods",
                since = 18,
                searchTerms = listOf("input methods"),
            ),
            FeatureSpec(
                group = "input",
                constant = "FEATURE_GAMEPAD",
                name = PackageManager.FEATURE_GAMEPAD,
                label = "Gamepad support",
                since = 21,
                searchTerms = listOf("gamepad"),
            ),
            FeatureSpec(
                group = "input",
                constant = "FEATURE_FREEFORM_WINDOW_MANAGEMENT",
                name = PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT,
                label = "Freeform multi-window",
                since = 24,
                searchTerms = listOf("freeform window management"),
            ),
            FeatureSpec(
                group = "input",
                constant = "FEATURE_PICTURE_IN_PICTURE",
                name = PackageManager.FEATURE_PICTURE_IN_PICTURE,
                label = "Picture-in-picture",
                since = 24,
                searchTerms = listOf("picture in picture"),
            ),
            FeatureSpec(
                group = "input",
                constant = "FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS",
                name = PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS,
                label = "Activities on secondary displays",
                since = 26,
                searchTerms = listOf("activities on secondary displays"),
            ),
            FeatureSpec(
                group = "input",
                constant = "FEATURE_EXPANDED_PICTURE_IN_PICTURE",
                name = PackageManager.FEATURE_EXPANDED_PICTURE_IN_PICTURE,
                label = "Expanded picture-in-picture",
                since = 33,
                searchTerms = listOf("expanded picture in picture"),
            ),
            FeatureSpec(
                group = "input",
                constant = "FEATURE_WINDOW_MAGNIFICATION",
                name = PackageManager.FEATURE_WINDOW_MAGNIFICATION,
                label = "Window magnification",
                since = 33,
                searchTerms = listOf("window magnification"),
            ),
            // USB
            FeatureSpec(
                group = "usb",
                constant = "FEATURE_USB_ACCESSORY",
                name = PackageManager.FEATURE_USB_ACCESSORY,
                label = "USB accessory mode",
                since = 12,
                searchTerms = listOf("usb accessory", "accessory"),
            ),
            FeatureSpec(
                group = "usb",
                constant = "FEATURE_USB_HOST",
                name = PackageManager.FEATURE_USB_HOST,
                label = "USB host mode",
                since = 12,
                searchTerms = listOf("usb host", "host"),
            ),
            // Virtual reality
            FeatureSpec(
                group = "vr",
                constant = "FEATURE_VR_MODE",
                name = PackageManager.FEATURE_VR_MODE,
                label = "VR mode",
                since = 24,
                searchTerms = listOf("vr mode", "mode"),
            ),
            FeatureSpec(
                group = "vr",
                constant = "FEATURE_VR_MODE_HIGH_PERFORMANCE",
                name = PackageManager.FEATURE_VR_MODE_HIGH_PERFORMANCE,
                label = "High-performance VR mode",
                since = 24,
                searchTerms = listOf("vr mode high performance", "high performance"),
            ),
            FeatureSpec(
                group = "vr",
                constant = "FEATURE_VR_HEADTRACKING",
                name = PackageManager.FEATURE_VR_HEADTRACKING,
                label = "6DOF VR head tracking",
                since = 26,
                searchTerms = listOf("vr headtracking", "headtracking"),
            ),
            // Form factor
            FeatureSpec(
                group = "form",
                constant = "FEATURE_TELEVISION",
                name = PackageManager.FEATURE_TELEVISION,
                label = "Television (deprecated in favour of Leanback)",
                since = 16,
                searchTerms = listOf("television"),
            ),
            FeatureSpec(
                group = "form",
                constant = "FEATURE_WATCH",
                name = PackageManager.FEATURE_WATCH,
                label = "Wear OS device",
                since = 20,
                searchTerms = listOf("watch"),
            ),
            FeatureSpec(
                group = "form",
                constant = "FEATURE_LEANBACK",
                name = PackageManager.FEATURE_LEANBACK,
                label = "Leanback (TV) UI",
                since = 21,
                searchTerms = listOf("leanback"),
            ),
            FeatureSpec(
                group = "form",
                constant = "FEATURE_LIVE_TV",
                name = PackageManager.FEATURE_LIVE_TV,
                label = "Live TV tuner",
                since = 21,
                searchTerms = listOf("live tv"),
            ),
            FeatureSpec(
                group = "form",
                constant = "FEATURE_AUTOMOTIVE",
                name = PackageManager.FEATURE_AUTOMOTIVE,
                label = "Android Automotive",
                since = 23,
                searchTerms = listOf("automotive"),
            ),
            FeatureSpec(
                group = "form",
                constant = "FEATURE_EMBEDDED",
                name = PackageManager.FEATURE_EMBEDDED,
                label = "Android Things / embedded",
                since = 26,
                searchTerms = listOf("embedded"),
            ),
            FeatureSpec(
                group = "form",
                constant = "FEATURE_LEANBACK_ONLY",
                name = PackageManager.FEATURE_LEANBACK_ONLY,
                label = "Leanback-only device",
                since = 26,
                searchTerms = listOf("leanback only"),
            ),
            FeatureSpec(
                group = "form",
                constant = "FEATURE_PC",
                name = PackageManager.FEATURE_PC,
                label = "Desktop-class device",
                since = 27,
                searchTerms = listOf("pc"),
            ),
            // Memory class
            FeatureSpec(
                group = "memory",
                constant = "FEATURE_RAM_LOW",
                name = PackageManager.FEATURE_RAM_LOW,
                label = "Configured as a low-RAM device",
                since = 27,
                searchTerms = listOf("ram low", "low"),
            ),
            FeatureSpec(
                group = "memory",
                constant = "FEATURE_RAM_NORMAL",
                name = PackageManager.FEATURE_RAM_NORMAL,
                label = "Configured as a normal-RAM device",
                since = 27,
                searchTerms = listOf("ram normal", "normal"),
            ),
            // Platform and system
            FeatureSpec(
                group = "system",
                constant = "FEATURE_LIVE_WALLPAPER",
                name = PackageManager.FEATURE_LIVE_WALLPAPER,
                label = "Live wallpapers",
                since = 7,
                searchTerms = listOf("live wallpaper"),
            ),
            FeatureSpec(
                group = "system",
                constant = "FEATURE_APP_WIDGETS",
                name = PackageManager.FEATURE_APP_WIDGETS,
                label = "App widgets",
                since = 18,
                searchTerms = listOf("app widgets"),
            ),
            FeatureSpec(
                group = "system",
                constant = "FEATURE_HOME_SCREEN",
                name = PackageManager.FEATURE_HOME_SCREEN,
                label = "Third-party launchers",
                since = 18,
                searchTerms = listOf("home screen"),
            ),
            FeatureSpec(
                group = "system",
                constant = "FEATURE_BACKUP",
                name = PackageManager.FEATURE_BACKUP,
                label = "Backup and restore",
                since = 20,
                searchTerms = listOf("backup"),
            ),
            FeatureSpec(
                group = "system",
                constant = "FEATURE_PRINTING",
                name = PackageManager.FEATURE_PRINTING,
                label = "Printing framework",
                since = 20,
                searchTerms = listOf("printing", "print"),
            ),
            FeatureSpec(
                group = "system",
                constant = "FEATURE_WEBVIEW",
                name = PackageManager.FEATURE_WEBVIEW,
                label = "WebView",
                since = 20,
                searchTerms = listOf("webview"),
            ),
            FeatureSpec(
                group = "system",
                constant = "FEATURE_AUTOFILL",
                name = PackageManager.FEATURE_AUTOFILL,
                label = "Autofill framework",
                since = 26,
                searchTerms = listOf("autofill"),
            ),
            FeatureSpec(
                group = "system",
                constant = "FEATURE_COMPANION_DEVICE_SETUP",
                name = PackageManager.FEATURE_COMPANION_DEVICE_SETUP,
                label = "Companion device pairing",
                since = 26,
                searchTerms = listOf("companion device setup"),
            ),
            FeatureSpec(
                group = "system",
                constant = "FEATURE_CANT_SAVE_STATE",
                name = PackageManager.FEATURE_CANT_SAVE_STATE,
                label = "Cannot-save-state apps",
                since = 28,
                searchTerms = listOf("cant save state"),
            ),
            FeatureSpec(
                group = "system",
                constant = "FEATURE_CONTROLS",
                name = PackageManager.FEATURE_CONTROLS,
                label = "Device controls",
                since = 30,
                searchTerms = listOf("controls"),
            ),
        )
    }
}
