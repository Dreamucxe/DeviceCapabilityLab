package com.devicelab.data.detect

import com.devicelab.core.detect.Probe
import com.devicelab.core.model.Absent
import com.devicelab.core.model.Fact
import com.devicelab.core.model.Provenance
import com.devicelab.core.model.Support

/**
 * Read-only access to Android's system-property store.
 *
 * Needed because several genuinely useful platform facts -- Treble, A/B updates,
 * dynamic partitions, VNDK version, verified-boot state -- have no public API of
 * any kind. These are the same world-readable properties `adb shell getprop`
 * prints; no permission, root or Shizuku is involved, and nothing is written.
 *
 * Two paths, tried in order:
 *
 *  1. Reflection on `android.os.SystemProperties.get`. Fast, and the property store
 *     is read directly. This class is hidden API on the "unsupported" list, so the
 *     call may log a warning and, on some builds, be blocked outright.
 *  2. Parsing one `getprop` invocation. `/system/bin/getprop` with no arguments
 *     prints the whole store, so a single process gives every property at once
 *     rather than one exec per lookup.
 *
 * When both fail the answer is [Absent.UNKNOWN]. A property that is simply not set
 * on this build is also unknown -- not "false", which would be an invention.
 */
object SystemProperties {

    private val cache = HashMap<String, String?>()

    @Volatile
    private var bulk: Map<String, String>? = null

    @Volatile
    private var reflectionUsable = true

    /** The property's value, or null when unset or unreadable. */
    @Synchronized
    fun get(name: String): String? {
        cache[name]?.let { return it }
        if (cache.containsKey(name)) return null

        var value: String? = null
        if (reflectionUsable) {
            value = viaReflection(name)
        }
        if (value.isNullOrBlank()) {
            value = viaGetprop(name)
        }
        val normalised = value?.takeIf { it.isNotBlank() }
        cache[name] = normalised
        return normalised
    }

    /**
     * A [Fact] for a text-valued property.
     *
     * Distinct from [Probe.value] because the two absences mean different things. A
     * platform API returning null is the hardware declining to answer; a property
     * that is not set is Android having no API for the question *and* the vendor not
     * having volunteered it. The provenance says so rather than implying the
     * hardware was asked and said no.
     */
    fun value(
        probe: Probe,
        label: String,
        property: String,
        detail: String? = null,
        searchTerms: List<String> = emptyList(),
        transform: (String) -> String = { it },
    ): Fact {
        val raw = get(property)
            ?: return Fact(
                label = label,
                value = Absent.UNKNOWN,
                provenance = Provenance.NotExposedByAndroid(
                    property,
                    "no public API, and $property is not set on this build",
                ),
                support = Support.UNKNOWN,
                detail = detail,
                searchTerms = searchTerms,
            )
        return probe.value(label, property, detail = detail, searchTerms = searchTerms) {
            transform(raw)
        }
    }

    /**
     * A [Fact] for a boolean-valued property, distinguishing "set to false" from
     * "not set at all". The second is genuinely unknown: many OEM images simply
     * omit properties whose feature they do support.
     */
    fun verdict(
        probe: Probe,
        label: String,
        property: String,
        searchTerms: List<String> = emptyList(),
    ): Fact {
        val raw = get(property)
        return when {
            raw == null -> Fact(
                label = label,
                value = Absent.UNKNOWN,
                provenance = Provenance.NotExposedByAndroid(
                    property,
                    "no public API, and $property is not set on this build",
                ),
                support = Support.UNKNOWN,
                detail = "Android provides no API for this. The property that would " +
                    "answer it is absent from this image, so the honest answer is " +
                    "that it cannot be determined here.",
                searchTerms = searchTerms,
            )
            raw.equals("true", ignoreCase = true) || raw == "1" -> Fact(
                label = label,
                value = "Supported",
                provenance = Provenance.Queried(property),
                support = Support.SUPPORTED,
                detail = "$property=$raw",
                searchTerms = searchTerms,
            )
            raw.equals("false", ignoreCase = true) || raw == "0" -> Fact(
                label = label,
                value = "Not supported",
                provenance = Provenance.HardwareAbsent(property),
                support = Support.UNSUPPORTED,
                detail = "$property=$raw",
                searchTerms = searchTerms,
            )
            else -> probe.value(label, property, searchTerms = searchTerms) { raw }
        }
    }

    private fun viaReflection(name: String): String? = try {
        val cls = Class.forName("android.os.SystemProperties")
        val method = cls.getMethod("get", String::class.java)
        method.invoke(null, name) as? String
    } catch (t: Throwable) {
        // NoSuchMethodException here means hidden-API enforcement blocked the lookup;
        // there is no point paying for the reflection on every subsequent property.
        reflectionUsable = false
        null
    }

    private fun viaGetprop(name: String): String? = bulkProperties()[name]

    @Synchronized
    private fun bulkProperties(): Map<String, String> {
        bulk?.let { return it }
        val parsed = try {
            val process = ProcessBuilder("/system/bin/getprop")
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            parseGetprop(text)
        } catch (t: Throwable) {
            emptyMap()
        }
        bulk = parsed
        return parsed
    }

    /**
     * Parses `getprop`'s `[name]: [value]` line format.
     *
     * Split on the first `]: [` rather than on `:` because plenty of values contain
     * colons -- fingerprints, dates and kernel strings all do.
     */
    internal fun parseGetprop(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        text.lineSequence().forEach { line ->
            if (!line.startsWith("[")) return@forEach
            val separator = line.indexOf("]: [")
            if (separator <= 0) return@forEach
            val key = line.substring(1, separator)
            val valueStart = separator + 4
            val valueEnd = line.lastIndexOf(']')
            if (valueEnd <= valueStart - 1) return@forEach
            val value = line.substring(valueStart, valueEnd)
            if (key.isNotEmpty() && value.isNotEmpty()) out[key] = value
        }
        return out
    }
}
