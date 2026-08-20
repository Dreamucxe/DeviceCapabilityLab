package com.devicelab.domain

import com.devicelab.core.model.CapabilityProfile
import com.devicelab.core.model.Fact
import com.devicelab.core.model.Lab
import com.devicelab.core.model.LabReport
import com.devicelab.core.model.Provenance
import com.devicelab.core.model.Section
import com.devicelab.core.model.Support
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Global search.
 *
 * Section 21 names the queries this has to answer: "Vulkan", "AV1", "120Hz", "RAW",
 * "Wi-Fi 6", "Gyroscope", "HDR". Three of those are not the words the platform uses --
 * the Wi-Fi standard constant is `WIFI_STANDARD_11AX`, a codec is `video/av01`, and
 * "120Hz" has no space where the formatted value does. Each of those is a test below,
 * because a search box that silently returns nothing for the query in the brief is worse
 * than no search box: the user concludes the device lacks the feature.
 */
class SearchUseCaseTest {

    private val search = SearchUseCase()

    @Test
    fun `an empty query returns nothing rather than everything`() {
        assertEquals(SearchResults.NONE, search(profile(), ""))
        assertEquals(SearchResults.NONE, search(profile(), "   "))
        assertTrue(search(profile(), "").isEmpty)
    }

    @Test
    fun `a label match is found`() {
        val results = search(profile(), "Gyroscope")
        assertEquals(1, results.total)
        assertEquals("Gyroscope", results.groups.single().second.single().label)
        assertEquals(Lab.SENSORS, results.groups.single().first)
    }

    @Test
    fun `matching is case insensitive`() {
        assertEquals(1, search(profile(), "gyroscope").total)
        assertEquals(1, search(profile(), "GYROSCOPE").total)
        assertEquals(1, search(profile(), "GyRoScOpE").total)
    }

    @Test
    fun `surrounding whitespace in the query is ignored`() {
        assertEquals(1, search(profile(), "  Gyroscope  ").total)
        assertEquals("Gyroscope", search(profile(), "  Gyroscope  ").query)
    }

    @Test
    fun `a value match is found`() {
        val results = search(profile(), "Adreno")
        assertEquals(1, results.total)
        assertEquals("Renderer", results.groups.single().second.single().label)
    }

    @Test
    fun `vulkan is found by name`() {
        assertEquals(1, search(profile(), "Vulkan").total)
    }

    /** The codec's real identifier is `video/av01`; nobody types that. */
    @Test
    fun `av1 is found although the platform calls it av01`() {
        val results = search(profile(), "AV1")
        assertEquals(1, results.total)
        assertEquals("video/av01", results.groups.single().second.single().value)
    }

    /** The value is formatted "120 Hz"; the query in the brief has no space. */
    @Test
    fun `120Hz is found although the value is formatted with a space`() {
        val results = search(profile(), "120Hz")
        assertEquals(1, results.total)
        assertEquals("120 Hz", results.groups.single().second.single().value)
    }

    @Test
    fun `the spacing insensitive pass works in both directions`() {
        // Query spaced, value not.
        assertEquals(1, search(profile(), "802.11 ax").total)
        // Query unspaced, value spaced.
        assertEquals(1, search(profile(), "120hz").total)
    }

    /** `WIFI_STANDARD_11AX` contains neither "Wi-Fi" nor "6"; the synonym carries it. */
    @Test
    fun `wi-fi 6 is found through the synonym a detector attached`() {
        val results = search(profile(), "Wi-Fi 6")
        assertEquals(1, results.total)
        assertEquals("802.11ax", results.groups.single().second.single().value)
    }

    @Test
    fun `raw is found`() {
        assertEquals(1, search(profile(), "RAW").total)
    }

    @Test
    fun `hdr matches every row that mentions it`() {
        val results = search(profile(), "HDR")
        // The HDR10+ display row and the HDR-capable codec row.
        assertEquals(2, results.total)
    }

    @Test
    fun `the api name is searchable so a row can be found by the call behind it`() {
        val results = search(profile(), "getHdrCapabilities")
        assertEquals(1, results.total)
        assertEquals("HDR10+", results.groups.single().second.single().label)
    }

    @Test
    fun `expanded detail text is searchable`() {
        val results = search(profile(), "hardware-backed")
        assertEquals(1, results.total)
        assertEquals("Security level", results.groups.single().second.single().label)
    }

    @Test
    fun `a query that matches nothing returns an empty result carrying the query`() {
        val results = search(profile(), "no such capability")
        assertTrue(results.isEmpty)
        assertEquals(0, results.total)
        assertEquals("no such capability", results.query)
        assertEquals(emptyList<Any>(), results.groups)
    }

    /**
     * The direct pass runs first and the whitespace-squashing pass only if it found
     * nothing. Without that ordering, "120 Hz" would also match a row whose value was
     * "1 20 Hz", and ordinary multi-word queries would lose their word boundaries.
     */
    @Test
    fun `the loose pass is a fallback and does not dilute a query that already matched`() {
        val results = search(profile(), "Renderer")
        assertEquals(1, results.total)
        // "renderer" squashed still matches only the one row, but the point is that the
        // direct pass returned before the fallback could widen anything.
        assertEquals("Renderer", results.groups.single().second.single().label)
    }

    @Test
    fun `results are grouped by lab in lab declaration order`() {
        // "Supported" appears in the display, graphics, camera and security labs.
        val results = search(profile(), "Supported")
        assertEquals(
            listOf(Lab.DISPLAY, Lab.GRAPHICS, Lab.CAMERA, Lab.SECURITY),
            results.groups.map { it.first },
        )
        assertEquals(results.total, results.groups.sumOf { it.second.size })
    }

    @Test
    fun `a hit carries where it came from and how it was obtained`() {
        val hit = search(profile(), "Gyroscope").groups.single().second.single()
        assertEquals(Lab.SENSORS, hit.lab)
        assertEquals("Sensors", hit.sectionTitle)
        assertEquals(Support.SUPPORTED, hit.support)
        assertTrue(hit.provenance.contains("getSensorList()"))
    }

    /** A nested section shows its whole path, or two cameras' rows read identically. */
    @Test
    fun `a hit inside a child section names the path to it`() {
        val hit = search(profile(), "RAW").groups.single().second.single()
        assertEquals("Cameras · Camera 0", hit.sectionTitle)
    }

    @Test
    fun `facts in nested sections are searched at every depth`() {
        val deep = CapabilityProfile(
            capturedAtMillis = 0L,
            reports = listOf(
                LabReport(
                    lab = Lab.CODEC,
                    sections = listOf(
                        Section(
                            id = "video",
                            title = "Video",
                            children = listOf(
                                Section(
                                    id = "av01",
                                    title = "AV1",
                                    children = listOf(
                                        Section(
                                            id = "profiles",
                                            title = "Profiles",
                                            facts = listOf(
                                                fact("Main 10", "Level 5.1"),
                                            ),
                                        )
                                    ),
                                )
                            ),
                        )
                    ),
                )
            ),
        )
        val hit = search(deep, "Main 10").groups.single().second.single()
        assertEquals("Video · AV1 · Profiles", hit.sectionTitle)
    }

    @Test
    fun `searching an empty profile is safe`() {
        val empty = CapabilityProfile(capturedAtMillis = 0L, reports = emptyList())
        assertTrue(search(empty, "Vulkan").isEmpty)
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
                            fact("Refresh rate", "120 Hz"),
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
                lab = Lab.GRAPHICS,
                sections = listOf(
                    Section(
                        id = "gpu",
                        title = "GPU",
                        facts = listOf(
                            fact("Renderer", "Adreno 740"),
                            Fact(
                                label = "Vulkan",
                                value = "Supported",
                                provenance = Provenance.Queried("hasSystemFeature()"),
                                support = Support.SUPPORTED,
                            ),
                        ),
                    )
                ),
            ),
            LabReport(
                lab = Lab.CODEC,
                sections = listOf(
                    Section(
                        id = "video",
                        title = "Video decoders",
                        facts = listOf(
                            Fact(
                                label = "AV1 decoder",
                                value = "video/av01",
                                provenance = Provenance.Queried("MediaCodecList"),
                                searchTerms = listOf("AV1", "AOMedia Video 1"),
                            ),
                            Fact(
                                label = "HLG transfer",
                                value = "video/hevc",
                                provenance = Provenance.Queried("MediaCodecList"),
                                searchTerms = listOf("HDR", "HLG"),
                            ),
                        ),
                    )
                ),
            ),
            LabReport(
                lab = Lab.SENSORS,
                sections = listOf(
                    Section(
                        id = "sensors",
                        title = "Sensors",
                        facts = listOf(
                            Fact(
                                label = "Gyroscope",
                                value = "LSM6DSO",
                                provenance = Provenance.Queried("SensorManager.getSensorList()"),
                                support = Support.SUPPORTED,
                            ),
                        ),
                    )
                ),
            ),
            LabReport(
                lab = Lab.CONNECTIVITY,
                sections = listOf(
                    Section(
                        id = "wifi",
                        title = "Wi-Fi",
                        facts = listOf(
                            Fact(
                                label = "Standard",
                                value = "802.11ax",
                                provenance = Provenance.Queried("WifiInfo.getWifiStandard()"),
                                searchTerms = listOf("Wi-Fi 6", "WIFI_STANDARD_11AX"),
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
                                        value = "Supported",
                                        provenance = Provenance.Queried("CameraCharacteristics"),
                                        support = Support.SUPPORTED,
                                    ),
                                ),
                            )
                        ),
                    )
                ),
            ),
            LabReport(
                lab = Lab.SECURITY,
                sections = listOf(
                    Section(
                        id = "keystore",
                        title = "Keystore",
                        facts = listOf(
                            Fact(
                                label = "Security level",
                                value = "Supported",
                                provenance = Provenance.Queried("KeyInfo.getSecurityLevel()"),
                                support = Support.SUPPORTED,
                                detail = "Keys are hardware-backed in the TEE",
                            ),
                        ),
                    )
                ),
            ),
        ),
    )

    private fun fact(label: String, value: String) =
        Fact(label, value, Provenance.Queried("Api.test()"))
}
