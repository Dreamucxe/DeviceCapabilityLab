package com.devicelab.core.detect

import android.os.Build
import com.devicelab.core.model.Absent
import com.devicelab.core.model.Domain
import com.devicelab.core.model.Fact
import com.devicelab.core.model.Provenance
import com.devicelab.core.model.Support

/**
 * The safe-call layer between detectors and the platform.
 *
 * Hardware inspection on Android is a minefield of vendor stubs that throw where
 * the documentation promises a value, APIs that appear mid-version, and services
 * that refuse without a permission. Section 29 of the brief requires that none of
 * that ever reaches the user as a crash. So detectors never call the platform
 * directly: they hand a lambda to one of these builders, which converts any
 * outcome -- including [Throwable] -- into an honest [Fact].
 *
 * The [deviceApi] field is injectable rather than read from [Build.VERSION] at the
 * call site so the API-gating logic itself is unit-testable on the JVM.
 */
class Probe(val deviceApi: Int = Build.VERSION.SDK_INT) {

    /**
     * A measured value. [read] returns null when the platform has the API but no
     * answer to give, which becomes [Absent.UNKNOWN] rather than a fabrication.
     */
    fun value(
        label: String,
        api: String,
        minApi: Int = 1,
        domain: Domain? = null,
        detail: String? = null,
        searchTerms: List<String> = emptyList(),
        absentText: String = Absent.UNKNOWN,
        read: () -> String?,
    ): Fact {
        if (deviceApi < minApi) {
            return Fact(
                label = label,
                value = Absent.NOT_EXPOSED,
                provenance = Provenance.RequiresApi(api, minApi, deviceApi),
                support = Support.NOT_EXPOSED,
                domain = domain,
                detail = detail,
                searchTerms = searchTerms,
            )
        }
        return try {
            val raw = read()
            if (raw.isNullOrBlank()) {
                Fact(
                    label = label,
                    value = absentText,
                    provenance = Provenance.HardwareAbsent(api),
                    support = Support.NOT_EXPOSED,
                    domain = domain,
                    detail = detail,
                    searchTerms = searchTerms,
                )
            } else {
                Fact(
                    label = label,
                    value = raw,
                    provenance = Provenance.Queried(api),
                    support = Support.INFORMATIONAL,
                    domain = domain,
                    detail = detail,
                    searchTerms = searchTerms,
                )
            }
        } catch (t: Throwable) {
            failure(label, api, t, domain, detail, searchTerms)
        }
    }

    /**
     * A yes/no capability. [read] returns null when the answer is genuinely
     * indeterminate, which is reported as [Support.UNKNOWN] and not as a "no".
     */
    fun flag(
        label: String,
        api: String,
        minApi: Int = 1,
        domain: Domain? = null,
        detail: String? = null,
        searchTerms: List<String> = emptyList(),
        supportedText: String = "Supported",
        unsupportedText: String = "Not supported",
        read: () -> Boolean?,
    ): Fact {
        if (deviceApi < minApi) {
            return Fact(
                label = label,
                value = Absent.NOT_EXPOSED,
                provenance = Provenance.RequiresApi(api, minApi, deviceApi),
                support = Support.NOT_EXPOSED,
                domain = domain,
                detail = detail,
                searchTerms = searchTerms,
            )
        }
        return try {
            when (read()) {
                true -> Fact(
                    label, supportedText, Provenance.Queried(api),
                    Support.SUPPORTED, domain, detail, searchTerms,
                )
                false -> Fact(
                    label, unsupportedText, Provenance.HardwareAbsent(api),
                    Support.UNSUPPORTED, domain, detail, searchTerms,
                )
                null -> Fact(
                    label, Absent.UNKNOWN, Provenance.Failed(api, NO_ANSWER),
                    Support.UNKNOWN, domain, detail, searchTerms,
                )
            }
        } catch (t: Throwable) {
            failure(label, api, t, domain, detail, searchTerms)
        }
    }

    /** A capability whose verdict the detector decides for itself. */
    fun verdict(
        label: String,
        api: String,
        minApi: Int = 1,
        domain: Domain? = null,
        detail: String? = null,
        searchTerms: List<String> = emptyList(),
        read: () -> Verdict?,
    ): Fact {
        if (deviceApi < minApi) {
            return Fact(
                label = label,
                value = Absent.NOT_EXPOSED,
                provenance = Provenance.RequiresApi(api, minApi, deviceApi),
                support = Support.NOT_EXPOSED,
                domain = domain,
                detail = detail,
                searchTerms = searchTerms,
            )
        }
        return try {
            val v = read()
            if (v == null) {
                Fact(
                    label, Absent.UNKNOWN, Provenance.Failed(api, NO_ANSWER),
                    Support.UNKNOWN, domain, detail, searchTerms,
                )
            } else {
                Fact(
                    label = label,
                    value = v.text,
                    // The provenance has to follow the verdict the detector actually
                    // reached, not merely "affirmative or not". A detector that says
                    // UNKNOWN -- an indeterminate keystore level, an HDCP level the
                    // platform reports as HDCP_LEVEL_UNKNOWN -- has not established that
                    // the hardware lacks anything, so labelling it "not supported by this
                    // hardware" would state as fact the one thing it could not determine.
                    provenance = when (v.support) {
                        Support.SUPPORTED,
                        Support.PARTIAL,
                        Support.INFORMATIONAL,
                        -> Provenance.Queried(api)

                        Support.UNSUPPORTED -> Provenance.HardwareAbsent(api)

                        // Both mean the call returned without yielding a usable answer,
                        // which is the same outcome as the null branch above and is
                        // worded identically so the two cannot read differently.
                        Support.UNKNOWN,
                        Support.NOT_EXPOSED,
                        -> Provenance.Failed(api, NO_ANSWER)
                    },
                    support = v.support,
                    domain = domain,
                    detail = v.detail ?: detail,
                    searchTerms = searchTerms,
                )
            }
        } catch (t: Throwable) {
            failure(label, api, t, domain, detail, searchTerms)
        }
    }

    /**
     * A fact recording that Android has no API for something at all, on any
     * version -- bit depth of the panel, exact CPU clock ceilings, Bluetooth
     * hardware version. Stating this outright is more useful than omitting the row,
     * because the reader learns the limit is the platform's, not the app's.
     */
    fun notExposedByAndroid(
        label: String,
        note: String,
        domain: Domain? = null,
        searchTerms: List<String> = emptyList(),
    ): Fact = Fact(
        label = label,
        value = Absent.NOT_EXPOSED,
        provenance = Provenance.NotExposedByAndroid("—", note),
        support = Support.NOT_EXPOSED,
        domain = domain,
        searchTerms = searchTerms,
    )

    /**
     * A fact recording that this app could read something and chose not to.
     *
     * Used for stable device identifiers -- a DRM device unique ID, a hardware
     * serial -- which are obtainable but identify the user's device rather than
     * describe its capability. Stating the refusal is better than omitting the row,
     * because a gap invites the reader to assume the app simply failed to look.
     */
    fun notRead(
        label: String,
        api: String,
        reason: String,
        searchTerms: List<String> = emptyList(),
    ): Fact = Fact(
        label = label,
        value = "Not read by design",
        provenance = Provenance.NotRead(api, reason),
        support = Support.NOT_EXPOSED,
        searchTerms = searchTerms,
    )

    /** Wraps a whole block of detection so one vendor bug cannot lose a section. */
    fun <T> attempt(fallback: T, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        fallback
    }

    private fun failure(
        label: String,
        api: String,
        t: Throwable,
        domain: Domain?,
        detail: String?,
        searchTerms: List<String>,
    ): Fact {
        val reason = t.javaClass.simpleName + (t.message?.let { ": ${it.take(120)}" } ?: "")
        val restricted = t is SecurityException
        return Fact(
            label = label,
            value = if (restricted) Absent.UNAVAILABLE else Absent.UNKNOWN,
            provenance = if (restricted) {
                Provenance.Restricted(api, "permission or appop not held")
            } else {
                Provenance.Failed(api, reason)
            },
            support = Support.NOT_EXPOSED,
            domain = domain,
            detail = detail,
            searchTerms = searchTerms,
        )
    }

    /** A detector-decided verdict: the text to show and the status it implies. */
    data class Verdict(val support: Support, val text: String, val detail: String? = null) {
        companion object {
            fun yes(text: String = "Supported", detail: String? = null) =
                Verdict(Support.SUPPORTED, text, detail)

            fun partial(text: String, detail: String? = null) =
                Verdict(Support.PARTIAL, text, detail)

            fun no(text: String = "Not supported", detail: String? = null) =
                Verdict(Support.UNSUPPORTED, text, detail)

            fun unknown(text: String = Absent.UNKNOWN, detail: String? = null) =
                Verdict(Support.UNKNOWN, text, detail)
        }
    }

    private companion object {
        /**
         * One wording for "the call returned, and what came back settles nothing", used
         * wherever that happens so a reader cannot tell two shapes of the same outcome
         * apart when there is no difference to tell.
         */
        const val NO_ANSWER = "no determinate answer"
    }
}
