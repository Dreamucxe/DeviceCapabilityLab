package com.devicelab.domain

import com.devicelab.core.model.CapabilityProfile
import com.devicelab.core.model.Fact
import com.devicelab.core.model.Lab
import com.devicelab.core.model.Section
import com.devicelab.core.model.Support
import javax.inject.Inject

/** One search hit: the fact, and enough context to say where it came from. */
data class SearchHit(
    val lab: Lab,
    val sectionTitle: String,
    val label: String,
    val value: String,
    val support: Support,
    val provenance: String,
    val detail: String?,
)

/** Search results grouped by lab, plus the total, for the results header. */
data class SearchResults(
    val query: String,
    val groups: List<Pair<Lab, List<SearchHit>>>,
    val total: Int,
) {
    val isEmpty: Boolean get() = total == 0

    companion object {
        val NONE = SearchResults("", emptyList(), 0)
    }
}

/**
 * Global instant search across every fact in a scan.
 *
 * Section 21 names the queries it has to answer -- "Vulkan", "AV1", "120Hz", "RAW",
 * "Wi-Fi 6", "Gyroscope", "HDR" -- and several of those are not the words the platform
 * uses. The Wi-Fi standard is `WIFI_STANDARD_11AX`, HDR types are `HDR_TYPE_HLG` and
 * friends, and a codec is `video/av01`. Detectors therefore attach synonyms to their
 * facts via `Fact.searchTerms`, and this searches those alongside the label and value.
 * That is why "Wi-Fi 6" finds an 802.11ax row.
 *
 * [normalise] is what makes "120Hz", "120 hz" and "120 Hz" the same query. Whitespace
 * is dropped from both sides of the comparison for a spacing-insensitive pass, run only
 * when the direct match found nothing, so ordinary queries keep their word boundaries.
 */
class SearchUseCase @Inject constructor() {

    operator fun invoke(profile: CapabilityProfile, query: String): SearchResults {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return SearchResults.NONE

        val direct = collect(profile) { it.matches(trimmed) }
        val hits = if (direct.isNotEmpty()) {
            direct
        } else {
            val squashed = normalise(trimmed)
            if (squashed.isEmpty()) {
                emptyList()
            } else {
                collect(profile) { fact ->
                    normalise(fact.label).contains(squashed) ||
                        normalise(fact.value).contains(squashed) ||
                        fact.searchTerms.any { normalise(it).contains(squashed) }
                }
            }
        }

        val groups = Lab.entries.mapNotNull { lab ->
            hits.filter { it.lab == lab }.takeIf { it.isNotEmpty() }?.let { lab to it }
        }
        return SearchResults(trimmed, groups, hits.size)
    }

    private fun collect(
        profile: CapabilityProfile,
        predicate: (Fact) -> Boolean,
    ): List<SearchHit> {
        val out = ArrayList<SearchHit>(64)
        profile.reports.forEach { report ->
            report.sections.forEach { section ->
                walk(report.lab, section, section.title, predicate, out)
            }
        }
        return out
    }

    private fun walk(
        lab: Lab,
        section: Section,
        title: String,
        predicate: (Fact) -> Boolean,
        out: MutableList<SearchHit>,
    ) {
        section.facts.forEach { fact ->
            if (predicate(fact)) {
                out += SearchHit(
                    lab = lab,
                    sectionTitle = title,
                    label = fact.label,
                    value = fact.value,
                    support = fact.support,
                    provenance = fact.provenance.explanation,
                    detail = fact.detail,
                )
            }
        }
        section.children.forEach { walk(lab, it, "$title · ${it.title}", predicate, out) }
    }

    /** Lowercased with all whitespace removed, so spacing cannot hide a match. */
    private fun normalise(value: String): String =
        value.lowercase().filterNot { it.isWhitespace() }
}
