package com.devicelab.core.model

/**
 * Why a capability is unavailable, when it is.
 *
 * Section 18 of the brief requires the matrix to distinguish two unavailabilities
 * that a single dash would conflate:
 *
 * - [API_LEVEL] -- the question cannot be asked on this Android version. The hardware
 *   may well do it. "Requires API 31+ — this device is running API 28."
 * - [HARDWARE] -- the question was asked and the device said no.
 *
 * Those are not the same claim, and treating them alike is the single most common way
 * a capability tool misleads its reader. The remaining reasons keep the same honesty:
 * a [RESTRICTED] row is a permission boundary, a [FAILED] row is a vendor
 * implementation that threw, a [PLATFORM] row is something Android exposes on no
 * version at all, and [BY_DESIGN] is this app declining to read an identifier.
 */
enum class Availability(val label: String, val note: String) {
    AVAILABLE("Available", "Queried and answered"),
    API_LEVEL("Not on this Android version", "The querying API is newer than this device"),
    HARDWARE("Not on this hardware", "Queried — the device does not report it"),
    RESTRICTED("Restricted", "The platform declined to answer"),
    FAILED("Query failed", "The platform implementation raised an error"),
    PLATFORM("Not exposed by Android", "No API exposes this on any version"),
    BY_DESIGN("Not read", "Readable, but deliberately not read"),
    ;

    companion object {
        fun of(provenance: Provenance): Availability = when (provenance) {
            is Provenance.Queried -> AVAILABLE
            is Provenance.RequiresApi -> API_LEVEL
            is Provenance.HardwareAbsent -> HARDWARE
            is Provenance.Restricted -> RESTRICTED
            is Provenance.Failed -> FAILED
            is Provenance.NotExposedByAndroid -> PLATFORM
            is Provenance.NotRead -> BY_DESIGN
        }

        fun ofKind(kind: String): Availability = when (kind) {
            "queried" -> AVAILABLE
            "requires-api" -> API_LEVEL
            "hardware-absent" -> HARDWARE
            "restricted" -> RESTRICTED
            "failed" -> FAILED
            "not-exposed-by-android" -> PLATFORM
            "not-read-by-design" -> BY_DESIGN
            else -> FAILED
        }
    }
}

/**
 * One row of the capability matrix: Capability | Status | Details.
 *
 * @param details the provenance explanation, which is where the API-level versus
 *   hardware distinction actually reaches the reader in words
 */
data class MatrixRow(
    val lab: Lab,
    val group: String,
    val capability: String,
    val support: Support,
    val value: String,
    val availability: Availability,
    val details: String,
    val detail: String? = null,
) {
    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        return capability.lowercase().contains(q) ||
            value.lowercase().contains(q) ||
            group.lowercase().contains(q) ||
            lab.title.lowercase().contains(q) ||
            details.lowercase().contains(q) ||
            detail?.lowercase()?.contains(q) == true
    }
}

/** The matrix, plus the counts the header needs. */
data class CapabilityMatrix(val rows: List<MatrixRow>) {

    val total: Int get() = rows.size

    fun count(availability: Availability): Int = rows.count { it.availability == availability }

    fun count(support: Support): Int = rows.count { it.support == support }

    /** Rows grouped by lab in [Lab] order, skipping labs with nothing to show. */
    fun byLab(): List<Pair<Lab, List<MatrixRow>>> =
        Lab.entries.mapNotNull { lab ->
            rows.filter { it.lab == lab }.takeIf { it.isNotEmpty() }?.let { lab to it }
        }

    fun filtered(query: String): CapabilityMatrix =
        if (query.isBlank()) this else CapabilityMatrix(rows.filter { it.matches(query) })

    companion object {

        /**
         * The matrix for a profile.
         *
         * Only facts carrying a verdict appear. Measurements -- a resolution, a
         * frequency, a byte count -- belong on their lab's screen, not in a table
         * whose column is headed "Status"; putting a refresh rate in a support matrix
         * would force it into a yes/no shape it does not have.
         */
        fun of(profile: CapabilityProfile): CapabilityMatrix {
            val rows = ArrayList<MatrixRow>(512)
            profile.reports.forEach { report ->
                report.sections.forEach { section ->
                    collect(report.lab, section, section.title, rows)
                }
            }
            return CapabilityMatrix(rows)
        }

        private fun collect(
            lab: Lab,
            section: Section,
            groupTitle: String,
            out: MutableList<MatrixRow>,
        ) {
            section.facts.forEach { fact ->
                if (fact.support == Support.INFORMATIONAL) return@forEach
                out += MatrixRow(
                    lab = lab,
                    group = groupTitle,
                    capability = fact.label,
                    support = fact.support,
                    value = fact.value,
                    availability = Availability.of(fact.provenance),
                    details = fact.provenance.explanation,
                    detail = fact.detail,
                )
            }
            // Child sections carry their own title as the group, so a per-camera or
            // per-codec row says which one it belongs to without repeating it in
            // every label.
            section.children.forEach { child ->
                collect(lab, child, "$groupTitle · ${child.title}", out)
            }
        }
    }
}
