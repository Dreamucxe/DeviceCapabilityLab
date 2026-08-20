package com.devicelab.core.model

/**
 * The dashboard roll-up, in one place.
 *
 * Section 3 of the brief is explicit that there is to be no invented performance
 * score, and that each of the eight domains shows one of four states derived from
 * actual detection. This is where that derivation lives, and it lives here rather than
 * on [CapabilityProfile] because a saved snapshot has to roll up by exactly the same
 * rule as a live scan. If the two disagreed, the History tab would contradict the
 * Dashboard for the same device.
 *
 * Two things this deliberately does not do.
 *
 * It does not count [Support.INFORMATIONAL] facts. Those are measurements -- a
 * resolution, a sample rate, a Vulkan API version -- and a measurement is not a yes or
 * a no. Counting them as "not affirmative" would drag a domain with every capability
 * present down to partial merely because it also reported some numbers, which is the
 * kind of quietly wrong summary this app exists to avoid.
 *
 * And it does not treat [Support.UNKNOWN] as a no. A domain whose every check came back
 * indeterminate is unknown, not unsupported; the latter would be a claim about the
 * hardware that nothing in the scan supports.
 */
object Scorecard {

    /** Every domain's status, in [Domain] declaration order. */
    fun of(facts: List<Fact>): List<DomainStatus> {
        val byDomain = facts.filter { it.domain != null }.groupBy { it.domain!! }
        return Domain.entries.map { domain -> status(domain, byDomain[domain].orEmpty()) }
    }

    /**
     * One domain's status from the facts tagged with it.
     *
     * The order of the branches is the order of the questions. Was anything asked? Did
     * everything answer yes? Did anything answer yes? Was every answer a refusal to
     * answer? Only when all of those are exhausted is "unsupported" the conclusion.
     */
    fun status(domain: Domain, facts: List<Fact>): DomainStatus {
        val verdicts = facts.filter { it.support != Support.INFORMATIONAL }
        val measurements = facts.size - verdicts.size

        val affirmative = verdicts.count { it.support.isAffirmative }
        val full = verdicts.count { it.support == Support.SUPPORTED }
        val notExposed = verdicts.count { it.support == Support.NOT_EXPOSED }
        val unknown = verdicts.count { it.support == Support.UNKNOWN }
        val unsupported = verdicts.count { it.support == Support.UNSUPPORTED }

        val support = when {
            verdicts.isEmpty() -> Support.UNKNOWN
            full == verdicts.size -> Support.SUPPORTED
            affirmative > 0 -> Support.PARTIAL
            notExposed == verdicts.size -> Support.NOT_EXPOSED
            unknown + notExposed == verdicts.size -> Support.UNKNOWN
            else -> Support.UNSUPPORTED
        }

        return DomainStatus(
            domain = domain,
            support = support,
            summary = summarise(support, affirmative, verdicts.size, measurements),
            supported = affirmative,
            total = verdicts.size,
            notExposed = notExposed,
            unknown = unknown,
            unsupported = unsupported,
            measurements = measurements,
        )
    }

    private fun summarise(
        support: Support,
        affirmative: Int,
        total: Int,
        measurements: Int,
    ): String = when {
        total == 0 && measurements > 0 ->
            "$measurements values reported, no yes/no capability to evaluate"
        total == 0 -> "Nothing in this domain could be queried"
        support == Support.SUPPORTED -> "All $total checks supported"
        support == Support.NOT_EXPOSED -> "None of the $total checks could be asked here"
        support == Support.UNKNOWN -> "No determinate answer from any of the $total checks"
        support == Support.UNSUPPORTED -> "None of the $total checks reported support"
        else -> "$affirmative of $total checks supported"
    }
}
