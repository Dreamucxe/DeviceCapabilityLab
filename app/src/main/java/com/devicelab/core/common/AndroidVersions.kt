package com.devicelab.core.common

import android.os.Build

/**
 * Android version names, so the platform lab can print "Android 14 (API 34)".
 *
 * [Build.VERSION.RELEASE] gives the number but not the codename, and there is no
 * platform API that maps a level to a marketing name -- this table is the map.
 * Levels beyond what this build knows about are reported as the bare API level
 * rather than guessed at.
 */
object AndroidVersions {

    private val names = mapOf(
        21 to "5.0 Lollipop", 22 to "5.1 Lollipop",
        23 to "6.0 Marshmallow",
        24 to "7.0 Nougat", 25 to "7.1 Nougat",
        26 to "8.0 Oreo", 27 to "8.1 Oreo",
        28 to "9 Pie",
        29 to "10", 30 to "11", 31 to "12", 32 to "12L", 33 to "13", 34 to "14",
        35 to "15", 36 to "16",
    )

    fun name(apiLevel: Int): String = names[apiLevel]?.let { "Android $it" } ?: "API $apiLevel"

    fun describe(release: String, apiLevel: Int): String {
        val known = names[apiLevel]
        return if (known != null) "Android $known (API $apiLevel)" else "Android $release (API $apiLevel)"
    }

    /** The API level a feature needs, formatted for a provenance note. */
    fun requirement(level: Int): String = "API $level+ (${name(level)})"
}
