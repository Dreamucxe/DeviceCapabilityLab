package com.devicelab.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dashboard roll-up.
 *
 * Section 3 of the brief forbids an invented performance score and requires each of the
 * eight domains to show one of four states derived from actual detection. Every rule
 * that derivation depends on is pinned here, because each one is a way the dashboard
 * could quietly lie: counting measurements as failures, treating "could not ask" as
 * "does not have", or reporting a domain nothing was detected for as unsupported.
 */
class ScorecardTest {

    @Test
    fun `all supported gives fully supported`() {
        val status = Scorecard.status(Domain.DISPLAY, listOf(yes(), yes(), yes()))
        assertEquals(Support.SUPPORTED, status.support)
        assertEquals(3, status.supported)
        assertEquals(3, status.total)
        assertEquals("All 3 checks supported", status.summary)
    }

    @Test
    fun `one unsupported among supported gives partial`() {
        val status = Scorecard.status(Domain.CAMERA, listOf(yes(), yes(), no()))
        assertEquals(Support.PARTIAL, status.support)
        assertEquals(2, status.supported)
        assertEquals(3, status.total)
        assertEquals(1, status.unsupported)
        assertEquals("2 of 3 checks supported", status.summary)
    }

    /** A partial check is affirmative, so a domain of only partials is partial. */
    @Test
    fun `all partial gives partial not supported`() {
        val status = Scorecard.status(Domain.AUDIO, listOf(partial(), partial()))
        assertEquals(Support.PARTIAL, status.support)
        assertEquals(2, status.supported)
    }

    @Test
    fun `everything unsupported gives unsupported`() {
        val status = Scorecard.status(Domain.SENSORS, listOf(no(), no()))
        assertEquals(Support.UNSUPPORTED, status.support)
        assertEquals(0, status.supported)
        assertEquals("None of the 2 checks reported support", status.summary)
    }

    /**
     * The distinction the whole app turns on. A domain nothing could be asked about is
     * NOT_EXPOSED -- a statement about Android -- and never UNSUPPORTED, which would be
     * a claim about hardware that no evidence supports.
     */
    @Test
    fun `everything not exposed gives not exposed never unsupported`() {
        val status = Scorecard.status(Domain.SECURITY, listOf(notExposed(), notExposed()))
        assertEquals(Support.NOT_EXPOSED, status.support)
        assertEquals("None of the 2 checks could be asked here", status.summary)
    }

    @Test
    fun `everything indeterminate gives unknown never unsupported`() {
        val status = Scorecard.status(Domain.GRAPHICS, listOf(unknown(), unknown()))
        assertEquals(Support.UNKNOWN, status.support)
        assertEquals("No determinate answer from any of the 2 checks", status.summary)
    }

    @Test
    fun `a mix of unknown and not exposed gives unknown`() {
        val status = Scorecard.status(Domain.MEDIA, listOf(unknown(), notExposed()))
        assertEquals(Support.UNKNOWN, status.support)
    }

    /**
     * A resolution is not a verdict. Counting measurements would drag a fully
     * supported domain down to partial merely because it also reported numbers.
     */
    @Test
    fun `measurements never affect the verdict`() {
        val withMeasurements = Scorecard.status(
            Domain.DISPLAY,
            listOf(yes(), yes(), measurement(), measurement(), measurement()),
        )
        assertEquals(Support.SUPPORTED, withMeasurements.support)
        assertEquals(2, withMeasurements.total)
        assertEquals(3, withMeasurements.measurements)
    }

    @Test
    fun `a domain of only measurements says so instead of guessing`() {
        val status = Scorecard.status(Domain.DISPLAY, listOf(measurement(), measurement()))
        assertEquals(Support.UNKNOWN, status.support)
        assertEquals(0, status.total)
        assertEquals("2 values reported, no yes/no capability to evaluate", status.summary)
    }

    @Test
    fun `a domain with nothing detected is unknown and says so`() {
        val status = Scorecard.status(Domain.SENSORS, emptyList())
        assertEquals(Support.UNKNOWN, status.support)
        assertEquals("Nothing in this domain could be queried", status.summary)
    }

    @Test
    fun `of returns every domain in declaration order even with no facts`() {
        val statuses = Scorecard.of(emptyList())
        assertEquals(Domain.entries.size, statuses.size)
        assertEquals(Domain.entries.map { it.title }, statuses.map { it.domain.title })
        assertTrue(statuses.all { it.support == Support.UNKNOWN })
    }

    @Test
    fun `of groups facts by their tagged domain and ignores untagged ones`() {
        val statuses = Scorecard.of(
            listOf(
                yes().copy(domain = Domain.DISPLAY),
                no().copy(domain = Domain.CAMERA),
                yes().copy(domain = null),
            )
        )
        val byDomain = statuses.associateBy { it.domain }
        assertEquals(Support.SUPPORTED, byDomain[Domain.DISPLAY]?.support)
        assertEquals(Support.UNSUPPORTED, byDomain[Domain.CAMERA]?.support)
        assertEquals(1, byDomain[Domain.DISPLAY]?.total)
    }

    /** The counts must add up to the number of verdicts, or the summary is wrong. */
    @Test
    fun `counts partition the verdicts exactly`() {
        val status = Scorecard.status(
            Domain.CONNECTIVITY,
            listOf(yes(), partial(), no(), notExposed(), unknown(), measurement()),
        )
        assertEquals(5, status.total)
        assertEquals(2, status.supported)
        assertEquals(1, status.unsupported)
        assertEquals(1, status.notExposed)
        assertEquals(1, status.unknown)
        assertEquals(1, status.measurements)
    }

    private fun yes() = fact(Support.SUPPORTED)
    private fun partial() = fact(Support.PARTIAL)
    private fun no() = fact(Support.UNSUPPORTED)
    private fun notExposed() = fact(Support.NOT_EXPOSED)
    private fun unknown() = fact(Support.UNKNOWN)
    private fun measurement() = fact(Support.INFORMATIONAL)

    private fun fact(support: Support) = Fact(
        label = "check",
        value = "value",
        provenance = Provenance.Queried("test"),
        support = support,
    )
}
