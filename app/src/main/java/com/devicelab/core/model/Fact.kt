package com.devicelab.core.model

/** Text shown in place of a value that the platform would not give up. */
object Absent {
    const val NOT_EXPOSED = "Not exposed by Android"
    const val UNKNOWN = "Unknown"
    const val UNAVAILABLE = "Unavailable"
    const val NONE = "None"
}

/**
 * One label/value row: the atom of the whole app.
 *
 * Every screen, the capability matrix, all three export formats, global search and
 * the snapshot diff are built from these, so a detector that produces good facts
 * gets all of those behaviours without writing any more code.
 *
 * @param label what was asked, e.g. "Refresh rate"
 * @param value the platform's answer, already formatted for reading, or one of the
 *   [Absent] strings. Never a guess and never a placeholder.
 * @param support the verdict; [Support.INFORMATIONAL] for measurements
 * @param provenance which API answered, or why none could
 * @param domain set when this fact is one of the defining capabilities of a
 *   scorecard domain, which is what makes the dashboard roll-up real
 * @param detail optional extra technical text, shown when a card is expanded
 * @param searchTerms extra words that should match this fact in global search --
 *   for "Wi-Fi 6" finding an 802.11ax row, and similar synonyms
 */
data class Fact(
    val label: String,
    val value: String,
    val provenance: Provenance,
    val support: Support = Support.INFORMATIONAL,
    val domain: Domain? = null,
    val detail: String? = null,
    val searchTerms: List<String> = emptyList(),
) {
    /** True when this fact carries a real measurement rather than an absence. */
    val hasValue: Boolean
        get() = value != Absent.NOT_EXPOSED &&
            value != Absent.UNKNOWN &&
            value != Absent.UNAVAILABLE

    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        if (label.lowercase().contains(q)) return true
        if (value.lowercase().contains(q)) return true
        if (detail?.lowercase()?.contains(q) == true) return true
        if (provenance.api.lowercase().contains(q)) return true
        return searchTerms.any { it.lowercase().contains(q) }
    }
}

/**
 * A tree of facts.
 *
 * Sections nest so that repeating structures -- one child per camera, per codec,
 * per sensor, per storage volume -- come out of a detector with no special cases,
 * and so that a fact's full path stays unique for diffing even when two cameras
 * both report a label called "Hardware level".
 */
data class Section(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val facts: List<Fact> = emptyList(),
    val children: List<Section> = emptyList(),
) {
    /** Every fact in this subtree, paired with its unique dotted path. */
    fun flatten(prefix: String = ""): List<Pair<String, Fact>> {
        val here = if (prefix.isEmpty()) id else "$prefix.$id"
        val own = facts.map { "$here.${it.label}" to it }
        return own + children.flatMap { it.flatten(here) }
    }

    fun allFacts(): List<Fact> = facts + children.flatMap { it.allFacts() }

    /**
     * The fact with this exact label, searching children too.
     *
     * Used by the comparison views, which need a named row from each of several
     * sibling sections -- one per camera, say. The labels they ask for are declared
     * as constants next to the detector that produces them, so the two sides cannot
     * drift apart silently.
     */
    fun fact(label: String): Fact? =
        facts.firstOrNull { it.label == label }
            ?: children.firstNotNullOfOrNull { it.fact(label) }

    /** This section with only the facts (and non-empty children) that match [query]. */
    fun filtered(query: String): Section? {
        if (query.isBlank()) return this
        val keptFacts = facts.filter { it.matches(query) }
        val keptChildren = children.mapNotNull { it.filtered(query) }
        val titleHit = title.lowercase().contains(query.trim().lowercase())
        return when {
            titleHit -> this
            keptFacts.isEmpty() && keptChildren.isEmpty() -> null
            else -> copy(facts = keptFacts, children = keptChildren)
        }
    }
}
