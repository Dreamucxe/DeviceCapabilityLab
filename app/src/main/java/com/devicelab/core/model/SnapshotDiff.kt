package com.devicelab.core.model

/** How one fact differs between two snapshots. */
enum class ChangeKind(val label: String, val glyph: String) {
    ADDED("Added", "+"),
    REMOVED("Removed", "−"),
    CHANGED("Changed", "≠"),
    UNCHANGED("Unchanged", "="),
}

/**
 * One row of a comparison.
 *
 * Both sides are kept, not just the difference, because the useful question is
 * usually "what did it say before, and what does it say now" -- a diff that only
 * showed the new value would make an upgrade indistinguishable from a regression.
 */
data class FactDelta(
    val kind: ChangeKind,
    val key: String,
    val labId: String,
    val sectionTitle: String,
    val label: String,
    val left: FactRow?,
    val right: FactRow?,
) {
    val lab: Lab? get() = Lab.fromId(labId)

    /** What changed, in words: value, verdict, or the ability to ask at all. */
    val summary: String
        get() = when (kind) {
            ChangeKind.ADDED -> right?.value ?: ""
            ChangeKind.REMOVED -> left?.value ?: ""
            ChangeKind.UNCHANGED -> right?.value ?: left?.value ?: ""
            ChangeKind.CHANGED -> {
                val l = left ?: return ""
                val r = right ?: return ""
                if (l.value != r.value) {
                    "${l.value} → ${r.value}"
                } else if (l.support != r.support) {
                    "${l.support.label} → ${r.support.label}"
                } else {
                    "${l.provenanceKind} → ${r.provenanceKind}"
                }
            }
        }

    /**
     * Why the two rows count as changed, for the expanded view.
     *
     * A provenance-only change deserves the explanation most: the value on screen is
     * identical, so without this the row would look like a bug.
     */
    val reason: String?
        get() {
            if (kind != ChangeKind.CHANGED) return null
            val l = left ?: return null
            val r = right ?: return null
            val parts = buildList {
                if (l.value != r.value) add("value changed")
                if (l.support != r.support) {
                    add("status changed from ${l.support.label} to ${r.support.label}")
                }
                if (l.provenanceKind != r.provenanceKind) {
                    add("how it was obtained changed from ${l.provenanceKind} to ${r.provenanceKind}")
                }
            }
            return parts.joinToString("; ").ifEmpty { null }
        }

    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        return label.lowercase().contains(q) ||
            sectionTitle.lowercase().contains(q) ||
            summary.lowercase().contains(q)
    }
}

/**
 * The result of comparing two snapshots.
 *
 * @param left the older snapshot, the "before"
 * @param right the newer snapshot, the "after"
 */
data class SnapshotDiff(
    val left: Snapshot,
    val right: Snapshot,
    val deltas: List<FactDelta>,
) {
    val added: Int get() = deltas.count { it.kind == ChangeKind.ADDED }
    val removed: Int get() = deltas.count { it.kind == ChangeKind.REMOVED }
    val changed: Int get() = deltas.count { it.kind == ChangeKind.CHANGED }
    val unchanged: Int get() = deltas.count { it.kind == ChangeKind.UNCHANGED }

    val hasDifferences: Boolean get() = added + removed + changed > 0

    /** Whether the two snapshots came from the same physical device. */
    val sameDevice: Boolean
        get() = left.model == right.model &&
            left.manufacturer == right.manufacturer &&
            left.device == right.device

    fun of(kind: ChangeKind): List<FactDelta> = deltas.filter { it.kind == kind }

    /** Deltas grouped by lab, in [Lab] declaration order, skipping empty labs. */
    fun byLab(kinds: Set<ChangeKind>): List<Pair<Lab, List<FactDelta>>> {
        val wanted = deltas.filter { it.kind in kinds }
        return Lab.entries.mapNotNull { lab ->
            wanted.filter { it.labId == lab.id }.takeIf { it.isNotEmpty() }?.let { lab to it }
        }
    }

    /**
     * One line for the top of the comparison screen.
     *
     * When the API level differs this says so, because on the same hardware an OS
     * upgrade is by far the most common reason a capability appears or disappears,
     * and a reader who does not notice that will read the diff as a hardware change.
     */
    val headline: String
        get() = when {
            !sameDevice -> "Different devices: ${left.deviceLabel} vs ${right.deviceLabel}"
            left.apiLevel != right.apiLevel ->
                "Same device, API ${left.apiLevel} → API ${right.apiLevel}"
            !hasDifferences -> "No differences"
            else -> "$changed changed, $added added, $removed removed"
        }
}

/**
 * Compares two snapshots fact by fact.
 *
 * The comparison is keyed, not positional: rows are matched by
 * `labId | sectionPath | label`, so a new section appearing in a later app version
 * shifts nothing and produces only genuine ADDED rows.
 *
 * Section 19 requires all four outcomes, including UNCHANGED. Keeping the unchanged
 * rows is what makes the comparison trustworthy -- a screen that showed only
 * differences could not distinguish "these devices are nearly identical" from "the
 * comparison only looked at a handful of things".
 */
object SnapshotComparer {

    fun compare(left: Snapshot, right: Snapshot): SnapshotDiff {
        val leftRows = left.rows.associateBy { it.key }
        val rightRows = right.rows.associateBy { it.key }

        // Presentation order follows the newer snapshot, then whatever only the older
        // one had. A user comparing an old scan against today's device reads the
        // current device's structure, with the disappearances appended.
        val keys = LinkedHashSet<String>(leftRows.size + rightRows.size)
        keys += right.rows.map { it.key }
        keys += left.rows.map { it.key }

        val deltas = keys.map { key ->
            val l = leftRows[key]
            val r = rightRows[key]
            val kind = when {
                l == null -> ChangeKind.ADDED
                r == null -> ChangeKind.REMOVED
                l.sameAs(r) -> ChangeKind.UNCHANGED
                else -> ChangeKind.CHANGED
            }
            val reference = r ?: l!!
            FactDelta(
                kind = kind,
                key = key,
                labId = reference.labId,
                sectionTitle = reference.sectionTitle,
                label = reference.label,
                left = l,
                right = r,
            )
        }
        return SnapshotDiff(left, right, deltas)
    }
}
