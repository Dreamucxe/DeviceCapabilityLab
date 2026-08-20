package com.devicelab.core.model

/**
 * Who produced a scan.
 *
 * Held apart from [android.os.Build] so that snapshot creation is pure and testable,
 * and so a stored snapshot keeps the identity of the device that made it. See
 * [Snapshot] for why re-reading `Build` at display time would be wrong.
 */
data class DeviceIdentity(
    val manufacturer: String,
    val model: String,
    val device: String,
    val androidRelease: String,
    val apiLevel: Int,
    val fingerprint: String,
) {
    companion object {
        val UNKNOWN = DeviceIdentity("Unknown", "Unknown", "Unknown", "Unknown", 0, "Unknown")
    }
}

/**
 * Flattening a live profile into storable rows, and the reverse view for display.
 *
 * This is the seam between the scan and everything persistent. Snapshots are stored
 * as rows rather than as a serialized profile because the diff, the search and the
 * history counts all want to work per fact, and because a schema of columns cannot
 * silently stop matching a Kotlin data class the way a blob can.
 */
object Snapshots {

    /**
     * Every fact in [profile] as a keyed row.
     *
     * Section paths come from the section tree, so two cameras that both report a
     * "Hardware level" produce two distinct keys. Duplicate keys within one section
     * would collapse a row on the way in, so any collision gets an ordinal suffix
     * rather than overwriting: a lost row would read as REMOVED in the next
     * comparison, which is a false report about the device.
     */
    fun rowsOf(profile: CapabilityProfile): List<FactRow> {
        val out = ArrayList<FactRow>(512)
        val seen = HashSet<String>()
        profile.reports.forEach { report ->
            report.sections.forEach { section ->
                collect(report.lab, section, "", out, seen)
            }
        }
        return out
    }

    private fun collect(
        lab: Lab,
        section: Section,
        parentPath: String,
        out: MutableList<FactRow>,
        seen: MutableSet<String>,
    ) {
        val path = if (parentPath.isEmpty()) section.id else "$parentPath/${section.id}"
        section.facts.forEach { fact ->
            var key = FactRow.keyOf(lab.id, path, fact.label)
            if (!seen.add(key)) {
                var ordinal = 2
                while (!seen.add("$key #$ordinal")) ordinal++
                key = "$key #$ordinal"
            }
            out += FactRow(
                key = key,
                labId = lab.id,
                sectionPath = path,
                sectionTitle = section.title,
                label = fact.label,
                value = fact.value,
                support = fact.support,
                provenanceKind = fact.provenance.kind,
                provenance = fact.provenance.explanation,
                detail = fact.detail,
                domain = fact.domain,
            )
        }
        section.children.forEach { collect(lab, it, path, out, seen) }
    }

    /** A snapshot from a completed scan, not yet given a database id. */
    fun snapshotOf(
        profile: CapabilityProfile,
        identity: DeviceIdentity,
        name: String,
    ): Snapshot = Snapshot(
        id = Snapshot.UNSAVED_ID,
        name = name,
        capturedAtMillis = profile.capturedAtMillis,
        manufacturer = identity.manufacturer,
        model = identity.model,
        device = identity.device,
        androidRelease = identity.androidRelease,
        apiLevel = identity.apiLevel,
        fingerprint = identity.fingerprint,
        rows = rowsOf(profile),
    )
}
