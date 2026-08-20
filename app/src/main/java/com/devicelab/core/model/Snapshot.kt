package com.devicelab.core.model

/**
 * One flattened fact, addressed by a stable key.
 *
 * A [Fact] on its own is not enough to compare two scans, because the same label
 * appears in many places -- "Hardware level" exists once per camera, "Sample rate"
 * once per audio device. [key] carries the full path so those stay distinct.
 *
 * The row keeps [support] and [provenanceKind] as well as [value] because all three
 * can change independently and each change is real. A row going from "Unknown" to
 * "Supported" is a change of verdict; a row whose value is unchanged but whose
 * provenance moved from `requires-api` to `queried` means the device was updated to
 * an Android version that can finally answer the question. Comparing values alone
 * would report the first and miss the second.
 *
 * @param key `labId | sectionPath | label`, unique within a snapshot
 * @param provenance the human-readable explanation, stored rather than recomputed so
 *   an old snapshot still explains itself after the app's wording changes
 */
data class FactRow(
    val key: String,
    val labId: String,
    val sectionPath: String,
    val sectionTitle: String,
    val label: String,
    val value: String,
    val support: Support,
    val provenanceKind: String,
    val provenance: String,
    val detail: String? = null,
    val domain: Domain? = null,
) {
    val lab: Lab? get() = Lab.fromId(labId)

    /** True when this row and [other] say exactly the same thing. */
    fun sameAs(other: FactRow): Boolean =
        value == other.value &&
            support == other.support &&
            provenanceKind == other.provenanceKind

    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        return label.lowercase().contains(q) ||
            value.lowercase().contains(q) ||
            sectionTitle.lowercase().contains(q) ||
            detail?.lowercase()?.contains(q) == true
    }

    companion object {
        /** The separator in [key]. Chosen because no Android API name or label uses it. */
        const val SEPARATOR = " | "

        fun keyOf(labId: String, sectionPath: String, label: String): String =
            labId + SEPARATOR + sectionPath + SEPARATOR + label
    }
}

/**
 * A saved scan.
 *
 * The device identity fields are stored on the snapshot rather than read from
 * [android.os.Build] at display time, because the whole point of history is that a
 * snapshot may have been taken on a different device or a different Android version
 * from the one now running the app. Re-reading `Build` would relabel old snapshots
 * with today's device, which is exactly the confusion a comparison feature must not
 * introduce.
 *
 * @param id the database row id; 0 for a snapshot not yet saved
 * @param name user-editable, defaulting to the device model and a timestamp
 * @param manufacturer as reported by the device that produced the scan
 */
data class Snapshot(
    val id: Long,
    val name: String,
    val capturedAtMillis: Long,
    val manufacturer: String,
    val model: String,
    val device: String,
    val androidRelease: String,
    val apiLevel: Int,
    val fingerprint: String,
    val rows: List<FactRow>,
) {
    val deviceLabel: String
        get() = if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model".trim()
        }

    val platformLabel: String get() = "Android $androidRelease (API $apiLevel)"

    fun rowsFor(lab: Lab): List<FactRow> = rows.filter { it.labId == lab.id }

    /** The scorecard for a stored scan, by the same rule a live scan uses. */
    val scorecard: List<DomainStatus>
        get() = Scorecard.of(
            rows.map {
                Fact(
                    label = it.label,
                    value = it.value,
                    provenance = Provenance.Queried(it.provenanceKind),
                    support = it.support,
                    domain = it.domain,
                )
            }
        )

    companion object {
        const val UNSAVED_ID = 0L
    }
}

/**
 * A history-list entry.
 *
 * Read as its own query so the list can show ten snapshots without loading tens of
 * thousands of fact rows into memory -- a full scan of a modern device produces well
 * over a thousand rows, and the list only needs a name and a count.
 */
data class SnapshotSummary(
    val id: Long,
    val name: String,
    val capturedAtMillis: Long,
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val apiLevel: Int,
    val factCount: Int,
) {
    val deviceLabel: String
        get() = if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model".trim()
        }

    val platformLabel: String get() = "Android $androidRelease (API $apiLevel)"
}
