package com.devicelab.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capability matrix and the availability classification behind it.
 *
 * Section 18 requires the matrix to distinguish "not exposed on this API level" from
 * "not exposed on this hardware". [Availability] is where that distinction is made, and
 * the mapping is exhaustive over [Provenance] on purpose: a new provenance case that
 * fell through to a default would silently classify a platform limit as a hardware one.
 */
class CapabilityMatrixTest {

    @Test
    fun `every provenance maps to its own availability`() {
        assertEquals(Availability.AVAILABLE, Availability.of(Provenance.Queried("x")))
        assertEquals(Availability.API_LEVEL, Availability.of(Provenance.RequiresApi("x", 31, 28)))
        assertEquals(Availability.HARDWARE, Availability.of(Provenance.HardwareAbsent("x")))
        assertEquals(Availability.RESTRICTED, Availability.of(Provenance.Restricted("x", "y")))
        assertEquals(Availability.FAILED, Availability.of(Provenance.Failed("x", "y")))
        assertEquals(
            Availability.PLATFORM,
            Availability.of(Provenance.NotExposedByAndroid("x", "y")),
        )
        assertEquals(Availability.BY_DESIGN, Availability.of(Provenance.NotRead("x", "y")))
    }

    /**
     * The stored token path must agree with the live one, or a snapshot would classify
     * differently from the scan it came from.
     */
    @Test
    fun `classifying by stored kind token agrees with classifying by provenance`() {
        val cases = listOf(
            Provenance.Queried("x"),
            Provenance.RequiresApi("x", 31, 28),
            Provenance.HardwareAbsent("x"),
            Provenance.Restricted("x", "y"),
            Provenance.Failed("x", "y"),
            Provenance.NotExposedByAndroid("x", "y"),
            Provenance.NotRead("x", "y"),
        )
        cases.forEach { provenance ->
            assertEquals(
                "kind '${provenance.kind}' must classify the same way",
                Availability.of(provenance),
                Availability.ofKind(provenance.kind),
            )
        }
    }

    @Test
    fun `an unrecognised kind token is reported as a failure rather than as available`() {
        assertEquals(Availability.FAILED, Availability.ofKind("something-from-a-later-version"))
    }

    @Test
    fun `every provenance kind token is unique`() {
        val kinds = listOf(
            Provenance.Queried("x"),
            Provenance.RequiresApi("x", 1, 1),
            Provenance.HardwareAbsent("x"),
            Provenance.Restricted("x", "y"),
            Provenance.Failed("x", "y"),
            Provenance.NotExposedByAndroid("x", "y"),
            Provenance.NotRead("x", "y"),
        ).map { it.kind }
        assertEquals(kinds.size, kinds.toSet().size)
    }

    /** The words Section 18 asks for, verbatim, with both levels named. */
    @Test
    fun `an api gated row explains which level is needed and which is running`() {
        val explanation = Provenance.RequiresApi(
            "MediaDrm.getSupportedCryptoSchemes()",
            31,
            28,
        ).explanation
        assertTrue(explanation.contains("Requires API 31+"))
        assertTrue(explanation.contains("this device is running API 28"))
        assertTrue(explanation.contains("MediaDrm.getSupportedCryptoSchemes()"))
    }

    @Test
    fun `a hardware absent row says the question was asked`() {
        val explanation = Provenance.HardwareAbsent("PackageManager.hasSystemFeature()").explanation
        assertTrue(explanation.contains("Queried"))
        assertTrue(explanation.contains("not supported by this hardware"))
    }

    @Test
    fun `every explanation names the api it refers to`() {
        listOf(
            Provenance.Queried("Api.one()"),
            Provenance.RequiresApi("Api.two()", 31, 28),
            Provenance.HardwareAbsent("Api.three()"),
            Provenance.Restricted("Api.four()", "no permission"),
            Provenance.Failed("Api.five()", "NullPointerException"),
        ).forEach {
            assertTrue("${it.kind} must name its API", it.explanation.contains(it.api))
        }
    }

    /**
     * A refresh rate has no place in a table headed "Status". Measurements belong on
     * their lab's screen; forcing them into a support matrix would give them a yes/no
     * shape they do not have.
     */
    @Test
    fun `measurements are excluded from the matrix`() {
        val matrix = CapabilityMatrix.of(profile())
        assertEquals(3, matrix.total)
        assertTrue(matrix.rows.none { it.support == Support.INFORMATIONAL })
        assertTrue(matrix.rows.none { it.capability == "Refresh rate" })
    }

    @Test
    fun `a child section contributes its own title to the group`() {
        val matrix = CapabilityMatrix.of(profile())
        val row = matrix.rows.first { it.capability == "RAW capture" }
        assertEquals("Cameras · Camera 0", row.group)
        assertEquals(Lab.CAMERA, row.lab)
    }

    @Test
    fun `counts by availability and by support both work`() {
        val matrix = CapabilityMatrix.of(profile())
        assertEquals(1, matrix.count(Availability.AVAILABLE))
        assertEquals(1, matrix.count(Availability.API_LEVEL))
        assertEquals(1, matrix.count(Availability.HARDWARE))
        assertEquals(1, matrix.count(Support.SUPPORTED))
        assertEquals(1, matrix.count(Support.NOT_EXPOSED))
        assertEquals(1, matrix.count(Support.UNSUPPORTED))
    }

    @Test
    fun `rows group by lab in declaration order skipping empty labs`() {
        val grouped = CapabilityMatrix.of(profile()).byLab()
        assertEquals(listOf(Lab.DISPLAY, Lab.CAMERA), grouped.map { it.first })
    }

    @Test
    fun `filtering searches the capability the value the group and the provenance`() {
        val matrix = CapabilityMatrix.of(profile())
        assertEquals(1, matrix.filtered("RAW").total)
        // A group name matches every row in the group, which is the point of searching it:
        // both of the fixture's Camera 0 facts sit under "Cameras · Camera 0".
        assertEquals(2, matrix.filtered("camera 0").total)
        assertEquals(1, matrix.filtered("Requires API 33").total)
        assertEquals(matrix.total, matrix.filtered("").total)
        assertEquals(0, matrix.filtered("no such thing").total)
    }

    @Test
    fun `a matrix row search covers its expanded detail too`() {
        val row = MatrixRow(
            lab = Lab.DRM,
            group = "Widevine",
            capability = "Security level",
            support = Support.SUPPORTED,
            value = "L1",
            availability = Availability.AVAILABLE,
            details = "Queried · MediaDrm.getPropertyString()",
            detail = "Hardware-backed content decryption",
        )
        assertTrue(row.matches("hardware-backed"))
        assertTrue(row.matches("widevine"))
        assertTrue(row.matches("drm"))
        assertFalse(row.matches("bluetooth"))
    }

    private fun profile() = CapabilityProfile(
        capturedAtMillis = 0L,
        reports = listOf(
            LabReport(
                lab = Lab.DISPLAY,
                sections = listOf(
                    Section(
                        id = "panel",
                        title = "Panel",
                        facts = listOf(
                            Fact("Refresh rate", "120 Hz", Provenance.Queried("Display.getMode()")),
                            Fact(
                                label = "HDR10+",
                                value = "Supported",
                                provenance = Provenance.Queried("Display.getHdrCapabilities()"),
                                support = Support.SUPPORTED,
                            ),
                        ),
                    )
                ),
            ),
            LabReport(
                lab = Lab.CAMERA,
                sections = listOf(
                    Section(
                        id = "cameras",
                        title = "Cameras",
                        children = listOf(
                            Section(
                                id = "camera0",
                                title = "Camera 0",
                                facts = listOf(
                                    Fact(
                                        label = "RAW capture",
                                        value = "Not supported",
                                        provenance = Provenance.HardwareAbsent("CameraCharacteristics"),
                                        support = Support.UNSUPPORTED,
                                    ),
                                    Fact(
                                        label = "Stream use case",
                                        value = Absent.NOT_EXPOSED,
                                        provenance = Provenance.RequiresApi(
                                            "SCALER_AVAILABLE_STREAM_USE_CASES",
                                            33,
                                            31,
                                        ),
                                        support = Support.NOT_EXPOSED,
                                    ),
                                ),
                            )
                        ),
                    )
                ),
            ),
        ),
    )
}
