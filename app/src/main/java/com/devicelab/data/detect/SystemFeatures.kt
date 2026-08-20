package com.devicelab.data.detect

import android.content.pm.FeatureInfo
import android.content.pm.PackageManager

/**
 * The platform's own list of system features, read once.
 *
 * Three labs need this list -- Graphics for the Vulkan version fields, Security for
 * the keystore version, and the Hardware Features lab for all of it -- and they must
 * not disagree with each other. Reading it in one place is what guarantees they
 * cannot: a device that reports KeyMint 3 here reports KeyMint 3 everywhere.
 *
 * [PackageManager.getSystemAvailableFeatures] is preferred over walking
 * `hasSystemFeature(name, version)` upwards because the array carries the exact
 * version number in [FeatureInfo.version], where the walk can only establish "at
 * least". `version` has existed since API 24 and minSdk here is 26, so the field is
 * always present and needs no gate.
 *
 * The result is cached for the process lifetime. System features are fixed when the
 * system server starts and cannot change without a reboot, which ends the process, so
 * a cached list can never go stale -- and Section 28 of the brief asks for exactly
 * this kind of static capability caching.
 */
object SystemFeatures {

    @Volatile
    private var cached: Array<FeatureInfo>? = null

    /** Every entry the platform reports, including the unnamed OpenGL ES one. */
    fun all(pm: PackageManager): Array<FeatureInfo> {
        cached?.let { return it }
        val read = try {
            pm.systemAvailableFeatures ?: emptyArray()
        } catch (t: Throwable) {
            emptyArray()
        }
        cached = read
        return read
    }

    /**
     * The named entries, keyed by feature name.
     *
     * The platform includes one entry whose [FeatureInfo.name] is null, carrying the
     * OpenGL ES version in [FeatureInfo.reqGlEsVersion] instead. It is excluded here
     * and available from [glEsVersion].
     */
    fun byName(pm: PackageManager): Map<String, FeatureInfo> {
        val out = LinkedHashMap<String, FeatureInfo>()
        all(pm).forEach { info ->
            val name = info.name
            if (name != null) out[name] = info
        }
        return out
    }

    /** The exact declared version of [name], or null when the feature is absent. */
    fun version(pm: PackageManager, name: String): Int? = byName(pm)[name]?.version

    /**
     * The OpenGL ES version string, from the unnamed entry.
     *
     * [FeatureInfo.getGlEsVersion] decodes the packed integer the platform stores, so
     * the major/minor split comes from the framework rather than from arithmetic here.
     */
    fun glEsVersion(pm: PackageManager): String? {
        val entry = all(pm).firstOrNull {
            it.name == null && it.reqGlEsVersion != FeatureInfo.GL_ES_VERSION_UNDEFINED
        } ?: return null
        return try {
            entry.glEsVersion?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * What a keystore feature's version number means.
     *
     * KeyMint replaced Keymaster and restarted the numbering at 100 for the AIDL
     * interface, while devices on the older HIDL interface report 4.1 as 41 and
     * below. Both are shown as the platform reported them, alongside the name that
     * number stands for. A number this mapping does not recognise is printed as-is
     * rather than rounded to the nearest known release.
     */
    fun keystoreVersionName(version: Int): String {
        val name = when {
            version >= 300 -> "KeyMint 3"
            version >= 200 -> "KeyMint 2"
            version >= 100 -> "KeyMint 1"
            version >= 41 -> "Keymaster 4.1"
            version >= 40 -> "Keymaster 4"
            version >= 30 -> "Keymaster 3"
            version >= 20 -> "Keymaster 2"
            else -> null
        }
        return if (name != null) "$name (version $version)" else "Version $version"
    }

    /** Test seam: drops the cached list so a fake [PackageManager] can be swapped in. */
    internal fun resetForTest() {
        cached = null
    }
}
