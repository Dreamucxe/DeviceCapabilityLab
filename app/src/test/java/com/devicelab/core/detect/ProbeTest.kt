package com.devicelab.core.detect

import com.devicelab.core.model.Absent
import com.devicelab.core.model.Domain
import com.devicelab.core.model.Provenance
import com.devicelab.core.model.Support
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The safe-call layer.
 *
 * Two guarantees are tested here, and both are load-bearing for the whole app.
 *
 * The first is that [Probe] never lets a platform failure become a crash or, worse, a
 * plausible-looking value. Every outcome -- null, throw, SecurityException -- has to come
 * back as a [com.devicelab.core.model.Fact] that says what happened.
 *
 * The second is stricter and is the reason [Probe.deviceApi] is a constructor parameter
 * at all: when the device's API level is below the level an API needs, the lambda must
 * **not run**. Not run and be ignored -- not run. On a real device the lambda body is
 * where a class that does not exist on this version gets referenced, and merely entering
 * a method containing such a reference can trigger verification. The counter in each
 * gating test below is what proves the short-circuit happens before the call.
 */
class ProbeTest {

    // ------------------------------------------------------------------ value()

    @Test
    fun `a value that reads successfully is queried and informational`() {
        val fact = Probe(deviceApi = 34).value("Renderer", "GLES20.glGetString()") {
            "Adreno 740"
        }
        assertEquals("Renderer", fact.label)
        assertEquals("Adreno 740", fact.value)
        assertEquals(Support.INFORMATIONAL, fact.support)
        assertEquals(Provenance.Queried("GLES20.glGetString()"), fact.provenance)
        assertTrue(fact.hasValue)
    }

    @Test
    fun `a null value becomes unknown rather than an empty string`() {
        val fact = Probe(deviceApi = 34).value("Renderer", "GLES20.glGetString()") { null }
        assertEquals(Absent.UNKNOWN, fact.value)
        assertEquals(Support.NOT_EXPOSED, fact.support)
        assertEquals("hardware-absent", fact.provenance.kind)
        assertFalse(fact.hasValue)
    }

    /** A vendor stub returning "" is the same non-answer as null and must read alike. */
    @Test
    fun `a blank value is treated as no answer at all`() {
        val probe = Probe(deviceApi = 34)
        assertEquals(Absent.UNKNOWN, probe.value("X", "Api.x()") { "" }.value)
        assertEquals(Absent.UNKNOWN, probe.value("X", "Api.x()") { "   " }.value)
        assertEquals(Support.NOT_EXPOSED, probe.value("X", "Api.x()") { "" }.support)
    }

    @Test
    fun `an absent value can name what none means for that field`() {
        val fact = Probe(deviceApi = 34).value("HDR types", "getHdrCapabilities()", absentText = Absent.NONE) {
            null
        }
        assertEquals(Absent.NONE, fact.value)
        // "None" is a real answer, so it counts as a value even though support is not a yes.
        assertTrue(fact.hasValue)
    }

    @Test
    fun `a throwing read becomes a failed fact carrying the exception type`() {
        val fact = Probe(deviceApi = 34).value("Renderer", "Api.x()") {
            throw IllegalStateException("vendor stub")
        }
        assertEquals(Absent.UNKNOWN, fact.value)
        assertEquals(Support.NOT_EXPOSED, fact.support)
        assertEquals("failed", fact.provenance.kind)
        val provenance = fact.provenance as Provenance.Failed
        assertTrue(provenance.reason.contains("IllegalStateException"))
        assertTrue(provenance.reason.contains("vendor stub"))
    }

    /**
     * A refusal is not a failure. The API worked; it declined. Reporting that as a
     * vendor bug would send the reader looking in the wrong place.
     */
    @Test
    fun `a security exception is reported as restricted and not as a failure`() {
        val fact = Probe(deviceApi = 34).value("SSID", "WifiInfo.getSSID()") {
            throw SecurityException("no location permission")
        }
        assertEquals(Absent.UNAVAILABLE, fact.value)
        assertEquals("restricted", fact.provenance.kind)
        assertTrue(fact.provenance.explanation.contains("Restricted"))
    }

    /** An Error is not an Exception; catching only Exception would let it through. */
    @Test
    fun `even a linkage error is caught rather than propagated`() {
        val fact = Probe(deviceApi = 34).value("X", "Api.x()") {
            throw NoSuchMethodError("Api.x")
        }
        assertEquals("failed", fact.provenance.kind)
        assertTrue((fact.provenance as Provenance.Failed).reason.contains("NoSuchMethodError"))
    }

    @Test
    fun `a long exception message is truncated so it cannot flood the row`() {
        val fact = Probe(deviceApi = 34).value("X", "Api.x()") {
            throw IllegalStateException("x".repeat(500))
        }
        val reason = (fact.provenance as Provenance.Failed).reason
        assertTrue("reason was ${reason.length} chars", reason.length < 200)
    }

    // -------------------------------------------------------- value() API gating

    @Test
    fun `value does not invoke the read when the device is below the required level`() {
        var calls = 0
        val fact = Probe(deviceApi = 28).value("Stream use cases", "SCALER_STREAM_USE_CASES", minApi = 33) {
            calls++
            "should never be reached"
        }
        assertEquals("the lambda must not run on an unsupported level", 0, calls)
        assertEquals(Absent.NOT_EXPOSED, fact.value)
        assertEquals(Support.NOT_EXPOSED, fact.support)
        assertEquals(Provenance.RequiresApi("SCALER_STREAM_USE_CASES", 33, 28), fact.provenance)
    }

    @Test
    fun `the gate opens exactly at the required level`() {
        var calls = 0
        val read: () -> String? = { calls++; "value" }

        assertEquals(Absent.NOT_EXPOSED, Probe(deviceApi = 32).value("X", "Api.x()", minApi = 33, read = read).value)
        assertEquals(0, calls)

        assertEquals("value", Probe(deviceApi = 33).value("X", "Api.x()", minApi = 33, read = read).value)
        assertEquals(1, calls)

        assertEquals("value", Probe(deviceApi = 34).value("X", "Api.x()", minApi = 33, read = read).value)
        assertEquals(2, calls)
    }

    @Test
    fun `the gated fact still says which api was needed and which is present`() {
        val explanation = Probe(deviceApi = 28)
            .value("X", "MediaDrm.getSupportedCryptoSchemes()", minApi = 31) { "v" }
            .provenance.explanation
        assertTrue(explanation.contains("Requires API 31+"))
        assertTrue(explanation.contains("this device is running API 28"))
    }

    /** minApi defaults to 1, so an ungated probe runs on every level the app supports. */
    @Test
    fun `an ungated probe runs on the oldest supported level`() {
        var calls = 0
        Probe(deviceApi = 26).value("X", "Api.x()") { calls++; "v" }
        assertEquals(1, calls)
    }

    // ------------------------------------------------------------------- flag()

    @Test
    fun `flag maps true false and null to three different answers`() {
        val probe = Probe(deviceApi = 34)

        val yes = probe.flag("Vulkan", "hasSystemFeature()") { true }
        assertEquals(Support.SUPPORTED, yes.support)
        assertEquals("Supported", yes.value)
        assertEquals("queried", yes.provenance.kind)

        val no = probe.flag("Vulkan", "hasSystemFeature()") { false }
        assertEquals(Support.UNSUPPORTED, no.support)
        assertEquals("Not supported", no.value)
        assertEquals("hardware-absent", no.provenance.kind)

        val dunno = probe.flag("Vulkan", "hasSystemFeature()") { null }
        assertEquals(Support.UNKNOWN, dunno.support)
        assertEquals(Absent.UNKNOWN, dunno.value)
        assertEquals("failed", dunno.provenance.kind)
    }

    /**
     * The single most important distinction in the app: a null answer must not become a
     * "no". "We could not tell" and "the hardware does not have it" are different claims,
     * and only one of them is a statement about the device.
     */
    @Test
    fun `an indeterminate flag is never reported as unsupported`() {
        val fact = Probe(deviceApi = 34).flag("Vulkan", "hasSystemFeature()") { null }
        assertEquals(Support.UNKNOWN, fact.support)
        assertFalse(fact.support == Support.UNSUPPORTED)
    }

    @Test
    fun `flag can name the capability in its own words`() {
        val fact = Probe(deviceApi = 34).flag(
            label = "Widevine",
            api = "MediaDrm.isCryptoSchemeSupported()",
            supportedText = "L1 hardware-backed",
            unsupportedText = "Scheme not present",
        ) { true }
        assertEquals("L1 hardware-backed", fact.value)
    }

    @Test
    fun `flag does not invoke the read when the device is below the required level`() {
        var calls = 0
        val fact = Probe(deviceApi = 30).flag("HDR10+", "HDR_TYPE_HDR10_PLUS", minApi = 31) {
            calls++
            true
        }
        assertEquals(0, calls)
        assertEquals(Support.NOT_EXPOSED, fact.support)
        assertEquals("requires-api", fact.provenance.kind)
    }

    @Test
    fun `a throwing flag is a failure rather than a no`() {
        val fact = Probe(deviceApi = 34).flag("X", "Api.x()") { throw RuntimeException("boom") }
        assertEquals(Support.NOT_EXPOSED, fact.support)
        assertEquals("failed", fact.provenance.kind)
    }

    // ---------------------------------------------------------------- verdict()

    @Test
    fun `an affirmative verdict is recorded as queried`() {
        val probe = Probe(deviceApi = 34)

        val yes = probe.verdict("Security level", "Api.x()") { Probe.Verdict.yes("L1") }
        assertEquals(Support.SUPPORTED, yes.support)
        assertEquals("L1", yes.value)
        assertEquals("queried", yes.provenance.kind)

        val partial = probe.verdict("Security level", "Api.x()") { Probe.Verdict.partial("L3") }
        assertEquals(Support.PARTIAL, partial.support)
        assertEquals("queried", partial.provenance.kind)
    }

    @Test
    fun `a negative verdict is recorded as a hardware absence`() {
        val fact = Probe(deviceApi = 34).verdict("X", "Api.x()") { Probe.Verdict.no() }
        assertEquals(Support.UNSUPPORTED, fact.support)
        assertEquals("hardware-absent", fact.provenance.kind)
        assertTrue(fact.provenance.explanation.contains("not supported by this hardware"))
    }

    /**
     * A detector that could not decide has established nothing about the hardware. Where
     * this once said "not supported by this hardware", it now says the query gave no
     * determinate answer -- which is what actually happened, and is the wording the null
     * branch already used.
     */
    @Test
    fun `an unknown verdict is not labelled a hardware absence`() {
        val fact = Probe(deviceApi = 34).verdict("HDCP level", "MediaDrm.getConnectedHdcpLevel()") {
            Probe.Verdict.unknown("Indeterminate")
        }
        assertEquals(Support.UNKNOWN, fact.support)
        assertEquals("failed", fact.provenance.kind)
        assertFalse(fact.provenance.explanation.contains("not supported by this hardware"))
        assertTrue(fact.provenance.explanation.contains("no determinate answer"))
    }

    /** Both non-answers must read identically; there is no difference to convey. */
    @Test
    fun `a null verdict and an unknown verdict give the same provenance`() {
        val probe = Probe(deviceApi = 34)
        val fromNull = probe.verdict("X", "Api.x()") { null }
        val fromUnknown = probe.verdict("X", "Api.x()") { Probe.Verdict.unknown() }
        assertEquals(fromNull.provenance, fromUnknown.provenance)
        assertEquals(fromNull.support, fromUnknown.support)
    }

    @Test
    fun `a verdict detail overrides the probe level detail`() {
        val probe = Probe(deviceApi = 34)
        assertEquals(
            "from the verdict",
            probe.verdict("X", "Api.x()", detail = "from the probe") {
                Probe.Verdict.yes("v", detail = "from the verdict")
            }.detail,
        )
        assertEquals(
            "from the probe",
            probe.verdict("X", "Api.x()", detail = "from the probe") {
                Probe.Verdict.yes("v")
            }.detail,
        )
    }

    @Test
    fun `verdict does not invoke the read when the device is below the required level`() {
        var calls = 0
        val fact = Probe(deviceApi = 28).verdict("Level", "Api.x()", minApi = 31) {
            calls++
            Probe.Verdict.yes()
        }
        assertEquals(0, calls)
        assertEquals(Support.NOT_EXPOSED, fact.support)
        assertEquals("requires-api", fact.provenance.kind)
    }

    @Test
    fun `a throwing verdict is a failure`() {
        val fact = Probe(deviceApi = 34).verdict("X", "Api.x()") { throw NullPointerException() }
        assertEquals(Support.NOT_EXPOSED, fact.support)
        assertEquals("failed", fact.provenance.kind)
    }

    // ------------------------------------------------- deliberate absence rows

    @Test
    fun `a not exposed by android row names the platform as the limit`() {
        val fact = Probe(deviceApi = 34).notExposedByAndroid(
            label = "Panel bit depth",
            note = "no public API on any version; only the vendor knows",
            domain = Domain.DISPLAY,
        )
        assertEquals(Absent.NOT_EXPOSED, fact.value)
        assertEquals(Support.NOT_EXPOSED, fact.support)
        assertEquals("not-exposed-by-android", fact.provenance.kind)
        assertEquals(Domain.DISPLAY, fact.domain)
        assertTrue(fact.provenance.explanation.startsWith("Not exposed by Android"))
        assertFalse(fact.hasValue)
    }

    /**
     * The refusal row. Section 15 scopes this app to capability information, so a stable
     * per-device identifier is deliberately left unread -- and saying so is better than
     * omitting the row, which would read as "the app did not think to look".
     */
    @Test
    fun `a not read row states that the value was obtainable and declined`() {
        val fact = Probe(deviceApi = 34).notRead(
            label = "Device unique ID",
            api = "MediaDrm.PROPERTY_DEVICE_UNIQUE_ID",
            reason = "a stable device identifier, not a capability",
        )
        assertEquals("Not read by design", fact.value)
        assertEquals(Support.NOT_EXPOSED, fact.support)
        assertEquals("not-read-by-design", fact.provenance.kind)
        assertTrue(fact.provenance.explanation.contains("deliberately not read"))
        assertTrue(fact.provenance.explanation.contains("not a capability"))
    }

    // ------------------------------------------------------------------ attempt()

    @Test
    fun `attempt returns the block result when nothing goes wrong`() {
        assertEquals(listOf("a"), Probe(deviceApi = 34).attempt(emptyList<String>()) { listOf("a") })
    }

    /** One vendor bug must cost a section, never the scan. */
    @Test
    fun `attempt falls back instead of letting a section die`() {
        val probe = Probe(deviceApi = 34)
        assertEquals(emptyList<String>(), probe.attempt(emptyList<String>()) { throw RuntimeException() })
        assertEquals(null, probe.attempt<String?>(null) { throw NoClassDefFoundError("vendor class") })
        assertEquals(-1, probe.attempt(-1) { throw AssertionError() })
    }

    // ------------------------------------------------------------ shared shape

    @Test
    fun `domain detail and search terms are carried through every path`() {
        val probe = Probe(deviceApi = 26)
        val terms = listOf("Wi-Fi 6", "802.11ax")

        val facts = listOf(
            probe.value("A", "Api.a()", domain = Domain.CONNECTIVITY, detail = "d", searchTerms = terms) { "v" },
            probe.value("B", "Api.b()", domain = Domain.CONNECTIVITY, detail = "d", searchTerms = terms) { null },
            probe.value("C", "Api.c()", minApi = 99, domain = Domain.CONNECTIVITY, detail = "d", searchTerms = terms) { "v" },
            probe.value("D", "Api.d()", domain = Domain.CONNECTIVITY, detail = "d", searchTerms = terms) { throw RuntimeException() },
            probe.flag("E", "Api.e()", domain = Domain.CONNECTIVITY, detail = "d", searchTerms = terms) { true },
            probe.flag("F", "Api.f()", domain = Domain.CONNECTIVITY, detail = "d", searchTerms = terms) { null },
            probe.flag("G", "Api.g()", minApi = 99, domain = Domain.CONNECTIVITY, detail = "d", searchTerms = terms) { true },
            probe.verdict("H", "Api.h()", domain = Domain.CONNECTIVITY, detail = "d", searchTerms = terms) { Probe.Verdict.yes() },
            probe.verdict("I", "Api.i()", minApi = 99, domain = Domain.CONNECTIVITY, detail = "d", searchTerms = terms) { Probe.Verdict.yes() },
        )

        facts.forEach { fact ->
            assertEquals("${fact.label} lost its domain", Domain.CONNECTIVITY, fact.domain)
            assertEquals("${fact.label} lost its detail", "d", fact.detail)
            assertEquals("${fact.label} lost its search terms", terms, fact.searchTerms)
            // The synonyms are the whole point of carrying them: search must still hit.
            assertTrue("${fact.label} is not findable by synonym", fact.matches("wi-fi 6"))
        }
    }

    @Test
    fun `the default device api comes from the platform rather than being hardcoded`() {
        // Under a JVM unit test Build.VERSION.SDK_INT is stubbed to 0, which is all this
        // can assert -- but it proves the field is read from Build and not fixed at 34.
        assertEquals(0, Probe().deviceApi)
    }
}
