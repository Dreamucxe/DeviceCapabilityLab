package com.devicelab.data.detect

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorDirectChannel
import android.hardware.SensorManager
import android.os.Build
import com.devicelab.core.common.Format
import com.devicelab.core.detect.Probe
import com.devicelab.core.model.Absent
import com.devicelab.core.model.Domain
import com.devicelab.core.model.Fact
import com.devicelab.core.model.Lab
import com.devicelab.core.model.LabReport
import com.devicelab.core.model.Section
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject

/**
 * Sensors, listed from the hardware rather than from a table of what phones usually have.
 *
 * Every sensor the platform reports is enumerated with its own vendor string, range,
 * resolution, power draw, FIFO depth and reporting mode. Nothing is registered and no
 * reading is taken -- this describes the sensor, it does not sample it, so there is no
 * battery cost and no need for the body-sensors or activity-recognition permissions.
 *
 * Two honest details other tools get wrong. A sensor above [Sensor.TYPE_DEVICE_PRIVATE_BASE]
 * is a vendor sensor with no public meaning, so it is reported by its own name and type
 * number instead of being forced into a known category. And the absence of, say, a
 * barometer is reported as "Queried -- not present on this hardware", never as a value of
 * zero.
 */
class SensorDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val probe: Probe,
) : CapabilityDetector {

    override val lab = Lab.SENSORS

    override suspend fun detect(): LabReport {
        val sm = context.getSystemService(SensorManager::class.java)
        val pm = context.packageManager
        if (sm == null) {
            return LabReport(
                lab,
                listOf(
                    Section(
                        "sensors-unavailable",
                        "Sensors",
                        facts = listOf(
                            probe.value("Sensor service", "getSystemService(SensorManager)") {
                                null
                            },
                        ),
                    ),
                ),
                listOf("This device does not provide a SensorManager service."),
            )
        }

        val all = probe.attempt(emptyList<Sensor>()) { sm.getSensorList(Sensor.TYPE_ALL) }
        val dynamic = if (Build.VERSION.SDK_INT >= 24) {
            probe.attempt(emptyList<Sensor>()) { sm.getDynamicSensorList(Sensor.TYPE_ALL) }
        } else {
            emptyList()
        }
        val byType = all.groupBy { it.type }

        return LabReport(
            lab = lab,
            sections = listOf(
                overview(all, dynamic, sm),
                declaredFeatures(pm),
                motion(byType),
                position(byType),
                environment(byType),
                bodyAndActivity(byType),
                vendorSensors(all),
                inventory(all),
            ),
            notes = listOf(
                "Sensors are described, not sampled: nothing is registered with the " +
                    "SensorManager, so this scan draws no additional power and needs no " +
                    "sensor permission.",
            ),
        )
    }

    private fun overview(all: List<Sensor>, dynamic: List<Sensor>, sm: SensorManager) = Section(
        id = "overview",
        title = "Overview",
        subtitle = "SensorManager.getSensorList(TYPE_ALL)",
        facts = listOf(
            probe.value(
                "Sensors reported",
                "SensorManager.getSensorList(TYPE_ALL)",
                domain = Domain.SENSORS,
                searchTerms = listOf("sensor count", "sensors"),
            ) { all.size.takeIf { it > 0 }?.toString() },
            probe.value("Distinct types", "Sensor.getType()") {
                all.map { it.type }.distinct().size.takeIf { it > 0 }?.toString()
            },
            probe.value(
                "Wake-up sensors",
                "Sensor.isWakeUpSensor()",
                minApi = 21,
                searchTerms = listOf("wake up", "wakeup"),
                detail = "Sensors that can wake the application processor from suspend, " +
                    "which is what allows always-on gesture and step detection.",
            ) { all.count { it.isWakeUpSensor }.toString() },
            probe.value(
                "Vendors",
                "Sensor.getVendor()",
                detail = "The sensor hub and silicon suppliers this device names.",
            ) {
                all.mapNotNull { it.vendor?.trim()?.takeIf(String::isNotEmpty) }
                    .distinct()
                    .sorted()
                    .joinToString(", ")
                    .ifBlank { null }
            },
            probe.flag(
                "Dynamic sensor discovery",
                "SensorManager.isDynamicSensorDiscoverySupported()",
                minApi = 24,
                searchTerms = listOf("dynamic sensor", "attachable"),
                detail = "Whether sensors can be attached at runtime, as on a wearable " +
                    "with a detachable module.",
            ) { sm.isDynamicSensorDiscoverySupported },
            probe.value(
                "Dynamic sensors attached",
                "SensorManager.getDynamicSensorList(TYPE_ALL)",
                minApi = 24,
                absentText = Absent.NONE,
            ) { dynamic.size.takeIf { it > 0 }?.toString() },
            probe.value(
                "Sensors with batching",
                "Sensor.getFifoMaxEventCount()",
                minApi = 19,
                searchTerms = listOf("fifo", "batching", "sensor hub"),
                detail = "A non-zero hardware FIFO lets the sensor hub buffer events while " +
                    "the CPU sleeps. This is the mark of a real low-power sensor hub.",
            ) { all.count { it.fifoMaxEventCount > 0 }.takeIf { it > 0 }?.toString() },
            probe.value(
                "Largest hardware FIFO",
                "Sensor.getFifoMaxEventCount()",
                minApi = 19,
            ) {
                all.maxOfOrNull { it.fifoMaxEventCount }
                    ?.takeIf { it > 0 }
                    ?.let { "$it events" }
            },
            probe.flag(
                "Direct report channels",
                "Sensor.isDirectChannelTypeSupported()",
                minApi = 26,
                searchTerms = listOf("direct channel", "direct report", "low latency sensor"),
                detail = "A path that writes sensor events straight into shared memory, " +
                    "bypassing the framework. Used by VR and AR runtimes.",
            ) {
                all.any {
                    it.isDirectChannelTypeSupported(SensorDirectChannel.TYPE_MEMORY_FILE) ||
                        it.isDirectChannelTypeSupported(SensorDirectChannel.TYPE_HARDWARE_BUFFER)
                }
            },
            probe.notExposedByAndroid(
                "Sensor part numbers",
                "Android reports each sensor's vendor and its own name string, which OEMs " +
                    "sometimes set to the part number and sometimes to a generic label. " +
                    "There is no field that reliably carries the component's part number.",
                searchTerms = listOf("part number", "sensor model"),
            ),
        ),
    )

    /**
     * What the manifest-visible feature flags claim.
     *
     * These are separate from enumeration on purpose: a feature flag is the OEM's
     * declaration, while `getSensorList` is what the HAL actually publishes. They
     * disagree often enough to be worth showing side by side.
     */
    private fun declaredFeatures(pm: PackageManager) = Section(
        id = "declared",
        title = "Declared sensor features",
        subtitle = "PackageManager — the OEM's declaration, shown next to what the HAL reports",
        facts = SENSOR_FEATURES.map { (label, spec) ->
            val (feature, minApi) = spec
            probe.flag(
                label,
                "PackageManager.hasSystemFeature($feature)",
                minApi = minApi,
                searchTerms = listOf(label.lowercase()),
            ) { pm.hasSystemFeature(feature) }
        },
    )

    private fun motion(byType: Map<Int, List<Sensor>>) = Section(
        id = "motion",
        title = "Motion & rotation",
        subtitle = "Accelerometer, gyroscope and the fusion sensors built on them",
        facts = MOTION_SENSORS.map { presence(it, byType) },
        children = MOTION_SENSORS.mapNotNull { spec ->
            byType[spec.type]?.firstOrNull()?.let { sensorSection(spec.label, it) }
        },
    )

    private fun position(byType: Map<Int, List<Sensor>>) = Section(
        id = "position",
        title = "Position & orientation",
        facts = POSITION_SENSORS.map { presence(it, byType) },
        children = POSITION_SENSORS.mapNotNull { spec ->
            byType[spec.type]?.firstOrNull()?.let { sensorSection(spec.label, it) }
        },
    )

    private fun environment(byType: Map<Int, List<Sensor>>) = Section(
        id = "environment",
        title = "Environment",
        subtitle = "Light, pressure, temperature and humidity",
        facts = ENVIRONMENT_SENSORS.map { presence(it, byType) },
        children = ENVIRONMENT_SENSORS.mapNotNull { spec ->
            byType[spec.type]?.firstOrNull()?.let { sensorSection(spec.label, it) }
        },
    )

    private fun bodyAndActivity(byType: Map<Int, List<Sensor>>) = Section(
        id = "body-activity",
        title = "Body & activity",
        subtitle = "Present here means the hardware exists — reading it needs a permission",
        facts = BODY_SENSORS.map { presence(it, byType) } + listOf(
            probe.notExposedByAndroid(
                "Heart-rate readings",
                "This lab reports that a heart-rate sensor exists and what its range and " +
                    "resolution are. Taking a reading requires the BODY_SENSORS permission " +
                    "and is health data, so it is out of scope for a capability inspector.",
                searchTerms = listOf("heart rate", "bpm", "ppg"),
            ),
        ),
        children = BODY_SENSORS.mapNotNull { spec ->
            byType[spec.type]?.firstOrNull()?.let { sensorSection(spec.label, it) }
        },
    )

    /**
     * Vendor sensors, reported honestly as unknown.
     *
     * Anything at or above [Sensor.TYPE_DEVICE_PRIVATE_BASE] has no defined meaning in
     * the platform. Guessing from the name would be exactly the kind of invention this
     * app avoids, so the type number and the vendor's own string are shown as-is.
     */
    private fun vendorSensors(all: List<Sensor>): Section {
        val vendor = all.filter { it.type >= Sensor.TYPE_DEVICE_PRIVATE_BASE }
        val unknownPublic = all.filter {
            it.type < Sensor.TYPE_DEVICE_PRIVATE_BASE && KNOWN_TYPES[it.type] == null
        }
        return Section(
            id = "vendor",
            title = "Vendor sensors",
            subtitle = "Types outside the public range — reported, not interpreted",
            facts = listOf(
                probe.value(
                    "Vendor-private sensors",
                    "Sensor.getType() >= TYPE_DEVICE_PRIVATE_BASE",
                    minApi = 24,
                    absentText = Absent.NONE,
                    searchTerms = listOf("vendor sensor", "private sensor", "oem sensor"),
                ) { vendor.size.takeIf { it > 0 }?.toString() },
                probe.value(
                    "Public types this build does not name",
                    "Sensor.getType()",
                    absentText = Absent.NONE,
                    detail = "Sensor types below the vendor range that this app's table " +
                        "does not have a label for, most likely added after API 34.",
                ) { unknownPublic.size.takeIf { it > 0 }?.toString() },
            ),
            children = (vendor + unknownPublic).map { sensor ->
                sensorSection(
                    probe.attempt("Sensor ${sensor.type}") {
                        sensor.name?.takeIf { it.isNotBlank() } ?: "Sensor type ${sensor.type}"
                    },
                    sensor,
                )
            },
        )
    }

    /** Every sensor the device reports, in type order, so nothing is hidden by category. */
    private fun inventory(all: List<Sensor>) = Section(
        id = "inventory",
        title = "Full inventory",
        subtitle = "All ${all.size} sensors the platform reports",
        facts = all.sortedBy { it.type }.map { sensor ->
            probe.value(
                probe.attempt("Sensor") { sensor.name ?: "Unnamed sensor" },
                "Sensor.getName() / getType()",
                searchTerms = probe.attempt(emptyList()) {
                    listOfNotNull(
                        sensor.name?.lowercase(Locale.US),
                        sensor.vendor?.lowercase(Locale.US),
                        typeLabel(sensor.type).lowercase(Locale.US),
                    )
                },
            ) {
                buildString {
                    append(typeLabel(sensor.type))
                    val vendor = probe.attempt<String?>(null) {
                        sensor.vendor?.trim()?.takeIf(String::isNotEmpty)
                    }
                    if (vendor != null) {
                        append(" · ")
                        append(vendor)
                    }
                }
            }
        },
        children = all.sortedBy { it.type }.mapIndexed { index, sensor ->
            sensorSection(
                probe.attempt("Sensor $index") { sensor.name ?: "Sensor $index" },
                sensor,
                idSuffix = index.toString(),
            )
        },
    )

    /** One presence row per known sensor type, worded so absence is a real answer. */
    private fun presence(spec: SensorSpec, byType: Map<Int, List<Sensor>>): Fact {
        val instances = byType[spec.type].orEmpty()
        return probe.verdict(
            spec.label,
            "SensorManager.getSensorList(${spec.constant})",
            minApi = spec.minApi,
            domain = Domain.SENSORS,
            searchTerms = spec.searchTerms,
        ) {
            val sensor = instances.firstOrNull()
                ?: return@verdict Probe.Verdict.no("Queried — not present on this hardware")
            val extra = buildString {
                val vendor = probe.attempt<String?>(null) {
                    sensor.vendor?.trim()?.takeIf(String::isNotEmpty)
                }
                if (vendor != null) append(vendor)
                if (instances.size > 1) {
                    if (isNotEmpty()) append(" · ")
                    append("${instances.size} instances")
                }
            }
            Probe.Verdict.yes(
                if (extra.isEmpty()) "Present" else "Present — $extra",
            )
        }
    }

    /** The full detail card for one sensor. */
    private fun sensorSection(title: String, sensor: Sensor, idSuffix: String? = null) = Section(
        id = "sensor-${sensor.type}${idSuffix?.let { "-$it" } ?: ""}",
        title = title,
        subtitle = probe.attempt(null) {
            sensor.vendor?.trim()?.takeIf(String::isNotEmpty)
        },
        facts = listOf(
            probe.value("Name", "Sensor.getName()") { sensor.name },
            probe.value("Vendor", "Sensor.getVendor()") { sensor.vendor },
            probe.value("Type", "Sensor.getType()") {
                "${typeLabel(sensor.type)} (${sensor.type})"
            },
            probe.value("Type string", "Sensor.getStringType()", minApi = 20) {
                sensor.stringType
            },
            probe.value("Version", "Sensor.getVersion()") { sensor.version.toString() },
            probe.value(
                "Maximum range",
                "Sensor.getMaximumRange()",
                searchTerms = listOf("range"),
                detail = "In the sensor's own unit: m/s² for accelerometers, rad/s for " +
                    "gyroscopes, µT for magnetometers, lx for light, hPa for pressure.",
            ) { "${Format.decimal(sensor.maximumRange, 3)} ${unitFor(sensor.type)}" },
            probe.value(
                "Resolution",
                "Sensor.getResolution()",
                searchTerms = listOf("resolution", "precision"),
            ) { "${Format.decimal(sensor.resolution, 6)} ${unitFor(sensor.type)}" },
            probe.value(
                "Power",
                "Sensor.getPower()",
                searchTerms = listOf("power", "battery", "ma"),
            ) { Format.milliamps(sensor.power) },
            probe.value(
                "Minimum delay",
                "Sensor.getMinDelay()",
                minApi = 9,
                searchTerms = listOf("rate", "hz", "sampling rate"),
                detail = "The shortest interval between events. Zero means the sensor only " +
                    "reports on change rather than continuously.",
            ) {
                val min = sensor.minDelay
                when {
                    min > 0 -> "$min µs (${Format.hertz(1_000_000f / min)} maximum)"
                    min == 0 -> "0 — reports on change, not at a fixed rate"
                    else -> null
                }
            },
            probe.value("Maximum delay", "Sensor.getMaxDelay()", minApi = 21) {
                sensor.maxDelay.takeIf { it > 0 }?.let { "$it µs" }
            },
            probe.value(
                "Reporting mode",
                "Sensor.getReportingMode()",
                minApi = 21,
                searchTerms = listOf("continuous", "on change", "one shot", "trigger"),
            ) { reportingModeName(sensor.reportingMode) },
            probe.value(
                "Hardware FIFO",
                "Sensor.getFifoMaxEventCount()",
                minApi = 19,
                absentText = Absent.NONE,
                searchTerms = listOf("fifo", "batching"),
            ) {
                sensor.fifoMaxEventCount.takeIf { it > 0 }?.let { max ->
                    val reserved = sensor.fifoReservedEventCount
                    if (reserved > 0) {
                        "$max events ($reserved reserved for this sensor)"
                    } else {
                        "$max events"
                    }
                }
            },
            probe.flag(
                "Wake-up sensor",
                "Sensor.isWakeUpSensor()",
                minApi = 21,
                searchTerms = listOf("wake up"),
                supportedText = "Yes — can wake the CPU",
                unsupportedText = "No",
            ) { sensor.isWakeUpSensor },
            probe.flag(
                "Dynamic",
                "Sensor.isDynamicSensor()",
                minApi = 24,
                supportedText = "Yes — attached at runtime",
                unsupportedText = "No — built in",
            ) { sensor.isDynamicSensor },
            probe.flag(
                "Additional info frames",
                "Sensor.isAdditionalInfoSupported()",
                minApi = 24,
                detail = "Whether the sensor emits calibration and placement metadata " +
                    "alongside its readings.",
            ) { sensor.isAdditionalInfoSupported },
            probe.value("Sensor ID", "Sensor.getId()", minApi = 24) {
                sensor.id.takeIf { it != 0 }?.toString()
            },
            probe.value(
                "Direct report",
                "Sensor.getHighestDirectReportRateLevel()",
                minApi = 26,
                absentText = Absent.NONE,
                searchTerms = listOf("direct channel", "direct report"),
            ) {
                val rate = directRateName(sensor.highestDirectReportRateLevel)
                    ?: return@value null
                val channels = buildList {
                    if (sensor.isDirectChannelTypeSupported(
                            SensorDirectChannel.TYPE_MEMORY_FILE,
                        )
                    ) {
                        add("memory file")
                    }
                    if (sensor.isDirectChannelTypeSupported(
                            SensorDirectChannel.TYPE_HARDWARE_BUFFER,
                        )
                    ) {
                        add("hardware buffer")
                    }
                }
                if (channels.isEmpty()) rate else "$rate via ${channels.joinToString(", ")}"
            },
        ),
    )

    // ---- naming ------------------------------------------------------------

    private fun typeLabel(type: Int): String = KNOWN_TYPES[type]
        ?: if (type >= Sensor.TYPE_DEVICE_PRIVATE_BASE) {
            "Vendor-private sensor"
        } else {
            "Unrecognised type"
        }

    private fun unitFor(type: Int): String = UNITS[type] ?: ""

    private fun reportingModeName(mode: Int): String = when (mode) {
        Sensor.REPORTING_MODE_CONTINUOUS -> "Continuous"
        Sensor.REPORTING_MODE_ON_CHANGE -> "On change"
        Sensor.REPORTING_MODE_ONE_SHOT -> "One shot"
        Sensor.REPORTING_MODE_SPECIAL_TRIGGER -> "Special trigger"
        else -> "Mode $mode"
    }

    private fun directRateName(level: Int): String? = when (level) {
        SensorDirectChannel.RATE_STOP -> null
        SensorDirectChannel.RATE_NORMAL -> "up to ~50 Hz"
        SensorDirectChannel.RATE_FAST -> "up to ~200 Hz"
        SensorDirectChannel.RATE_VERY_FAST -> "up to ~800 Hz"
        else -> "rate level $level"
    }

    /** A public sensor type worth a presence row of its own. */
    private data class SensorSpec(
        val label: String,
        val type: Int,
        val constant: String,
        val minApi: Int = 1,
        val searchTerms: List<String> = emptyList(),
    )

    private companion object {

        val MOTION_SENSORS = listOf(
            SensorSpec(
                "Accelerometer",
                Sensor.TYPE_ACCELEROMETER,
                "TYPE_ACCELEROMETER",
                searchTerms = listOf("accelerometer", "motion", "g-sensor"),
            ),
            SensorSpec(
                "Accelerometer (uncalibrated)",
                Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
                "TYPE_ACCELEROMETER_UNCALIBRATED",
                minApi = 26,
                searchTerms = listOf("accelerometer uncalibrated"),
            ),
            SensorSpec(
                "Accelerometer (limited axes)",
                Sensor.TYPE_ACCELEROMETER_LIMITED_AXES,
                "TYPE_ACCELEROMETER_LIMITED_AXES",
                minApi = 33,
                searchTerms = listOf("limited axes"),
            ),
            SensorSpec(
                "Gyroscope",
                Sensor.TYPE_GYROSCOPE,
                "TYPE_GYROSCOPE",
                searchTerms = listOf("gyroscope", "gyro", "rotation rate"),
            ),
            SensorSpec(
                "Gyroscope (uncalibrated)",
                Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
                "TYPE_GYROSCOPE_UNCALIBRATED",
                minApi = 18,
                searchTerms = listOf("gyroscope uncalibrated"),
            ),
            SensorSpec(
                "Gyroscope (limited axes)",
                Sensor.TYPE_GYROSCOPE_LIMITED_AXES,
                "TYPE_GYROSCOPE_LIMITED_AXES",
                minApi = 33,
            ),
            SensorSpec(
                "Gravity",
                Sensor.TYPE_GRAVITY,
                "TYPE_GRAVITY",
                minApi = 9,
                searchTerms = listOf("gravity"),
            ),
            SensorSpec(
                "Linear acceleration",
                Sensor.TYPE_LINEAR_ACCELERATION,
                "TYPE_LINEAR_ACCELERATION",
                minApi = 9,
                searchTerms = listOf("linear acceleration"),
            ),
            SensorSpec(
                "Significant motion",
                Sensor.TYPE_SIGNIFICANT_MOTION,
                "TYPE_SIGNIFICANT_MOTION",
                minApi = 18,
                searchTerms = listOf("significant motion"),
            ),
            SensorSpec(
                "Motion detect",
                Sensor.TYPE_MOTION_DETECT,
                "TYPE_MOTION_DETECT",
                minApi = 24,
            ),
            SensorSpec(
                "Stationary detect",
                Sensor.TYPE_STATIONARY_DETECT,
                "TYPE_STATIONARY_DETECT",
                minApi = 24,
            ),
            SensorSpec(
                "Step detector",
                Sensor.TYPE_STEP_DETECTOR,
                "TYPE_STEP_DETECTOR",
                minApi = 19,
                searchTerms = listOf("step detector", "pedometer"),
            ),
            SensorSpec(
                "Step counter",
                Sensor.TYPE_STEP_COUNTER,
                "TYPE_STEP_COUNTER",
                minApi = 19,
                searchTerms = listOf("step counter", "pedometer"),
            ),
        )

        val POSITION_SENSORS = listOf(
            SensorSpec(
                "Magnetometer",
                Sensor.TYPE_MAGNETIC_FIELD,
                "TYPE_MAGNETIC_FIELD",
                searchTerms = listOf("magnetometer", "compass", "magnetic field"),
            ),
            SensorSpec(
                "Magnetometer (uncalibrated)",
                Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED,
                "TYPE_MAGNETIC_FIELD_UNCALIBRATED",
                minApi = 18,
            ),
            SensorSpec(
                "Rotation vector",
                Sensor.TYPE_ROTATION_VECTOR,
                "TYPE_ROTATION_VECTOR",
                minApi = 9,
                searchTerms = listOf("rotation vector", "orientation", "sensor fusion"),
            ),
            SensorSpec(
                "Game rotation vector",
                Sensor.TYPE_GAME_ROTATION_VECTOR,
                "TYPE_GAME_ROTATION_VECTOR",
                minApi = 18,
                searchTerms = listOf("game rotation"),
            ),
            SensorSpec(
                "Geomagnetic rotation vector",
                Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
                "TYPE_GEOMAGNETIC_ROTATION_VECTOR",
                minApi = 19,
            ),
            SensorSpec(
                "Proximity",
                Sensor.TYPE_PROXIMITY,
                "TYPE_PROXIMITY",
                searchTerms = listOf("proximity", "in call"),
            ),
            SensorSpec(
                "Hinge angle",
                Sensor.TYPE_HINGE_ANGLE,
                "TYPE_HINGE_ANGLE",
                minApi = 30,
                searchTerms = listOf("hinge", "foldable", "fold angle"),
            ),
            SensorSpec(
                "6DoF pose",
                Sensor.TYPE_POSE_6DOF,
                "TYPE_POSE_6DOF",
                minApi = 24,
                searchTerms = listOf("6dof", "pose", "ar", "vr"),
            ),
            SensorSpec(
                "Head tracker",
                Sensor.TYPE_HEAD_TRACKER,
                "TYPE_HEAD_TRACKER",
                minApi = 33,
                searchTerms = listOf("head tracker", "spatial audio"),
            ),
            SensorSpec(
                "Heading",
                Sensor.TYPE_HEADING,
                "TYPE_HEADING",
                minApi = 33,
                searchTerms = listOf("heading", "true north"),
            ),
        )

        val ENVIRONMENT_SENSORS = listOf(
            SensorSpec(
                "Ambient light",
                Sensor.TYPE_LIGHT,
                "TYPE_LIGHT",
                minApi = 3,
                searchTerms = listOf("light sensor", "ambient light", "lux", "auto brightness"),
            ),
            SensorSpec(
                "Barometer",
                Sensor.TYPE_PRESSURE,
                "TYPE_PRESSURE",
                minApi = 3,
                searchTerms = listOf("barometer", "pressure", "altitude"),
            ),
            SensorSpec(
                "Ambient temperature",
                Sensor.TYPE_AMBIENT_TEMPERATURE,
                "TYPE_AMBIENT_TEMPERATURE",
                minApi = 14,
                searchTerms = listOf("temperature", "thermometer"),
            ),
            SensorSpec(
                "Relative humidity",
                Sensor.TYPE_RELATIVE_HUMIDITY,
                "TYPE_RELATIVE_HUMIDITY",
                minApi = 14,
                searchTerms = listOf("humidity", "hygrometer"),
            ),
        )

        val BODY_SENSORS = listOf(
            SensorSpec(
                "Heart rate",
                Sensor.TYPE_HEART_RATE,
                "TYPE_HEART_RATE",
                minApi = 20,
                searchTerms = listOf("heart rate", "bpm"),
            ),
            SensorSpec(
                "Heart beat",
                Sensor.TYPE_HEART_BEAT,
                "TYPE_HEART_BEAT",
                minApi = 24,
            ),
            SensorSpec(
                "Off-body detect",
                Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT,
                "TYPE_LOW_LATENCY_OFFBODY_DETECT",
                minApi = 26,
                searchTerms = listOf("off body", "wearable"),
            ),
        )

        /** Feature label → (feature string, API level the constant was added). */
        val SENSOR_FEATURES = listOf(
            "Accelerometer" to (PackageManager.FEATURE_SENSOR_ACCELEROMETER to 8),
            "Gyroscope" to (PackageManager.FEATURE_SENSOR_GYROSCOPE to 9),
            "Compass" to (PackageManager.FEATURE_SENSOR_COMPASS to 8),
            "Barometer" to (PackageManager.FEATURE_SENSOR_BAROMETER to 9),
            "Light" to (PackageManager.FEATURE_SENSOR_LIGHT to 7),
            "Proximity" to (PackageManager.FEATURE_SENSOR_PROXIMITY to 7),
            "Step counter" to (PackageManager.FEATURE_SENSOR_STEP_COUNTER to 19),
            "Step detector" to (PackageManager.FEATURE_SENSOR_STEP_DETECTOR to 19),
            "Heart rate" to (PackageManager.FEATURE_SENSOR_HEART_RATE to 20),
            "Heart rate (ECG)" to (PackageManager.FEATURE_SENSOR_HEART_RATE_ECG to 21),
            "Ambient temperature" to
                (PackageManager.FEATURE_SENSOR_AMBIENT_TEMPERATURE to 21),
            "Relative humidity" to (PackageManager.FEATURE_SENSOR_RELATIVE_HUMIDITY to 21),
            "Hinge angle" to (PackageManager.FEATURE_SENSOR_HINGE_ANGLE to 30),
            "High-fidelity sensors" to (PackageManager.FEATURE_HIFI_SENSORS to 23),
        )

        val KNOWN_TYPES: Map<Int, String> = mapOf(
            Sensor.TYPE_ACCELEROMETER to "Accelerometer",
            Sensor.TYPE_MAGNETIC_FIELD to "Magnetometer",
            Sensor.TYPE_ORIENTATION to "Orientation (deprecated)",
            Sensor.TYPE_GYROSCOPE to "Gyroscope",
            Sensor.TYPE_LIGHT to "Ambient light",
            Sensor.TYPE_PRESSURE to "Barometer",
            Sensor.TYPE_TEMPERATURE to "Temperature (deprecated)",
            Sensor.TYPE_PROXIMITY to "Proximity",
            Sensor.TYPE_GRAVITY to "Gravity",
            Sensor.TYPE_LINEAR_ACCELERATION to "Linear acceleration",
            Sensor.TYPE_ROTATION_VECTOR to "Rotation vector",
            Sensor.TYPE_RELATIVE_HUMIDITY to "Relative humidity",
            Sensor.TYPE_AMBIENT_TEMPERATURE to "Ambient temperature",
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED to "Magnetometer (uncalibrated)",
            Sensor.TYPE_GAME_ROTATION_VECTOR to "Game rotation vector",
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED to "Gyroscope (uncalibrated)",
            Sensor.TYPE_SIGNIFICANT_MOTION to "Significant motion",
            Sensor.TYPE_STEP_DETECTOR to "Step detector",
            Sensor.TYPE_STEP_COUNTER to "Step counter",
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR to "Geomagnetic rotation vector",
            Sensor.TYPE_HEART_RATE to "Heart rate",
            Sensor.TYPE_POSE_6DOF to "6DoF pose",
            Sensor.TYPE_STATIONARY_DETECT to "Stationary detect",
            Sensor.TYPE_MOTION_DETECT to "Motion detect",
            Sensor.TYPE_HEART_BEAT to "Heart beat",
            Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT to "Off-body detect",
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED to "Accelerometer (uncalibrated)",
            Sensor.TYPE_HINGE_ANGLE to "Hinge angle",
            Sensor.TYPE_HEAD_TRACKER to "Head tracker",
            Sensor.TYPE_ACCELEROMETER_LIMITED_AXES to "Accelerometer (limited axes)",
            Sensor.TYPE_GYROSCOPE_LIMITED_AXES to "Gyroscope (limited axes)",
            Sensor.TYPE_ACCELEROMETER_LIMITED_AXES_UNCALIBRATED to
                "Accelerometer (limited axes, uncalibrated)",
            Sensor.TYPE_GYROSCOPE_LIMITED_AXES_UNCALIBRATED to
                "Gyroscope (limited axes, uncalibrated)",
            Sensor.TYPE_HEADING to "Heading",
        )

        /**
         * The unit each sensor type reports in, from the platform's own documentation.
         *
         * Attaching a unit to a bare float is the difference between "8.0" and
         * "8.0 m/s²". Types whose unit is dimensionless or composite are left blank
         * rather than given a made-up one.
         */
        val UNITS: Map<Int, String> = mapOf(
            Sensor.TYPE_ACCELEROMETER to "m/s²",
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED to "m/s²",
            Sensor.TYPE_ACCELEROMETER_LIMITED_AXES to "m/s²",
            Sensor.TYPE_ACCELEROMETER_LIMITED_AXES_UNCALIBRATED to "m/s²",
            Sensor.TYPE_GRAVITY to "m/s²",
            Sensor.TYPE_LINEAR_ACCELERATION to "m/s²",
            Sensor.TYPE_GYROSCOPE to "rad/s",
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED to "rad/s",
            Sensor.TYPE_GYROSCOPE_LIMITED_AXES to "rad/s",
            Sensor.TYPE_GYROSCOPE_LIMITED_AXES_UNCALIBRATED to "rad/s",
            Sensor.TYPE_MAGNETIC_FIELD to "µT",
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED to "µT",
            Sensor.TYPE_LIGHT to "lx",
            Sensor.TYPE_PRESSURE to "hPa",
            Sensor.TYPE_PROXIMITY to "cm",
            Sensor.TYPE_AMBIENT_TEMPERATURE to "°C",
            Sensor.TYPE_TEMPERATURE to "°C",
            Sensor.TYPE_RELATIVE_HUMIDITY to "%",
            Sensor.TYPE_HEART_RATE to "bpm",
            Sensor.TYPE_HINGE_ANGLE to "°",
            Sensor.TYPE_ORIENTATION to "°",
            Sensor.TYPE_HEADING to "°",
            Sensor.TYPE_STEP_COUNTER to "steps",
        )
    }
}
