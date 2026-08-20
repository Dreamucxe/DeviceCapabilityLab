package com.devicelab.core.model

/**
 * How a value was obtained -- or why it could not be.
 *
 * Every [Fact] carries one of these, and the UI renders it verbatim. It is what
 * lets the app say "Requires API 31+, this device is running API 28" instead of a
 * dead-end dash, which is the distinction Section 18 of the brief asks for.
 *
 * [api] is always the actual Android symbol that was called, so a reader can go
 * and check the claim against the platform documentation themselves.
 */
sealed interface Provenance {

    /** The name of the platform API this provenance refers to. */
    val api: String

    /** The API exists on this device, was called, and returned the value shown. */
    data class Queried(override val api: String) : Provenance

    /**
     * The querying API itself was introduced after this device's API level, so the
     * question cannot be asked here at all. The hardware may well support the
     * feature; Android on this version simply provides no way to find out.
     */
    data class RequiresApi(override val api: String, val requiredApi: Int, val deviceApi: Int) : Provenance

    /** The API was called successfully and the hardware does not report the feature. */
    data class HardwareAbsent(override val api: String) : Provenance

    /** The API exists but declined to answer: SecurityException, missing permission, appop. */
    data class Restricted(override val api: String, val reason: String) : Provenance

    /** The API threw something unexpected -- vendor bug, unimplemented stub, emulator. */
    data class Failed(override val api: String, val reason: String) : Provenance

    /** Android has no API for this on any version; only vendors know the answer. */
    data class NotExposedByAndroid(override val api: String, val note: String) : Provenance

    /**
     * The value is obtainable and this app chooses not to read it.
     *
     * Distinct from every other case here, because the limit is a decision rather
     * than a restriction. A DRM device unique ID or a hardware serial is a stable
     * identifier for the user's device, not a statement about what it can do, so
     * the row records the refusal instead of quietly omitting it.
     */
    data class NotRead(override val api: String, val reason: String) : Provenance

    /** A one-line explanation suitable for display directly beneath a value. */
    val explanation: String
        get() = when (this) {
            is Queried -> "Queried · $api"
            is RequiresApi ->
                "Requires API $requiredApi+ — this device is running API $deviceApi · $api"
            is HardwareAbsent -> "Queried — not supported by this hardware · $api"
            is Restricted -> "Restricted — $reason · $api"
            is Failed -> "Query failed — $reason · $api"
            is NotExposedByAndroid -> "Not exposed by Android — $note"
            is NotRead -> "Available but deliberately not read — $reason"
        }

    /** A stable token for exports and tests. */
    val kind: String
        get() = when (this) {
            is Queried -> "queried"
            is RequiresApi -> "requires-api"
            is HardwareAbsent -> "hardware-absent"
            is Restricted -> "restricted"
            is Failed -> "failed"
            is NotExposedByAndroid -> "not-exposed-by-android"
            is NotRead -> "not-read-by-design"
        }
}
