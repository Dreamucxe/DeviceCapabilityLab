package com.devicelab.data.export

import com.devicelab.core.model.Absent
import com.devicelab.core.model.CapabilityProfile
import com.devicelab.core.model.DeviceIdentity
import com.devicelab.core.model.Domain
import com.devicelab.core.model.Fact
import com.devicelab.core.model.Lab
import com.devicelab.core.model.LabReport
import com.devicelab.core.model.Provenance
import com.devicelab.core.model.Section
import com.devicelab.core.model.Support
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The three export formats.
 *
 * An export is the one artefact of this app that outlives the session and travels, so
 * three properties matter more than the layout:
 *
 *  - it must be **well-formed**, because the user finds out otherwise long after the
 *    export appeared to succeed;
 *  - it must be **self-contained**, because Section 26 promises the app works offline and
 *    an HTML file that fetched a webfont when opened would break that promise and tell a
 *    third party which device was scanned;
 *  - it must contain **no private user information** (Section 20), and it must not quietly
 *    drop the provenance that makes an absence honest.
 *
 * All three formats come from one traversal of one profile, so a fourth property is worth
 * pinning too: they cannot disagree about what the device said.
 */
class ReportExporterTest {

    private val exporter = ReportExporter()
    private var previousZone: TimeZone? = null

    @Before
    fun fixTimeZone() {
        // The filename carries a local timestamp, so the assertion on it needs a fixed one.
        previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTimeZone() {
        previousZone?.let { TimeZone.setDefault(it) }
    }

    // -------------------------------------------------------------- filenames

    @Test
    fun `a filename carries the model the timestamp and the right extension`() {
        assertEquals(
            "capability-Pixel-8-20231114-221320.json",
            exporter.filename(identity(), CAPTURED, ExportFormat.JSON),
        )
        assertEquals(
            "capability-Pixel-8-20231114-221320.txt",
            exporter.filename(identity(), CAPTURED, ExportFormat.TEXT),
        )
        assertEquals(
            "capability-Pixel-8-20231114-221320.html",
            exporter.filename(identity(), CAPTURED, ExportFormat.HTML),
        )
    }

    /**
     * Model names are vendor-supplied and some contain slashes and spaces. A filename
     * built straight from one would either fail to create or, worse, resolve somewhere
     * other than intended.
     */
    @Test
    fun `a hostile model name cannot escape the filename`() {
        val name = exporter.filename(
            identity().copy(model = "../../etc/passwd A+B"),
            CAPTURED,
            ExportFormat.JSON,
        )
        assertFalse(name.contains("/"))
        assertFalse(name.contains(".."))
        assertFalse(name.contains(" "))
        assertEquals("capability-etc-passwd-A-B-20231114-221320.json", name)
    }

    @Test
    fun `a model name with nothing usable in it falls back to device`() {
        assertTrue(
            exporter.filename(identity().copy(model = "   "), CAPTURED, ExportFormat.JSON)
                .startsWith("capability-device-")
        )
        assertTrue(
            exporter.filename(identity().copy(model = "///"), CAPTURED, ExportFormat.JSON)
                .startsWith("capability-device-")
        )
    }

    @Test
    fun `render returns the same filename the filename helper would`() {
        val document = exporter.render(profile(), identity(), ExportFormat.JSON, "1.0")
        assertEquals(
            exporter.filename(identity(), CAPTURED, ExportFormat.JSON),
            document.filename,
        )
        assertEquals(ExportFormat.JSON, document.format)
    }

    @Test
    fun `the reported size is the utf8 byte length not the character count`() {
        val document = exporter.render(profile(), identity(), ExportFormat.TEXT, "1.0")
        assertEquals(document.content.toByteArray(Charsets.UTF_8).size, document.sizeBytes)
        // The report contains × and the status glyphs, so bytes must exceed characters.
        assertTrue(document.sizeBytes > document.content.length)
    }

    @Test
    fun `every format has a distinct id extension and mime type`() {
        val formats = ExportFormat.entries
        assertEquals(3, formats.size)
        assertEquals(formats.size, formats.map { it.id }.toSet().size)
        assertEquals(formats.size, formats.map { it.extension }.toSet().size)
        assertEquals(formats.size, formats.map { it.mimeType }.toSet().size)
    }

    // ------------------------------------------------------------------- JSON

    @Test
    fun `the json export is well formed`() {
        assertBalanced(render(ExportFormat.JSON))
    }

    @Test
    fun `the json export declares its schema and generator`() {
        val json = render(ExportFormat.JSON)
        assertTrue(json.contains("\"schema\": \"${ReportExporter.JSON_SCHEMA}\""))
        assertTrue(json.contains("\"app\": \"${ReportExporter.APP_NAME}\""))
        assertTrue(json.contains("\"version\": \"9.9.9\""))
    }

    @Test
    fun `the json export timestamps in utc and in millis`() {
        val json = render(ExportFormat.JSON)
        assertTrue(json.contains("\"capturedAt\": \"2023-11-14T22:13:20Z\""))
        assertTrue(json.contains("\"capturedAtMillis\": $CAPTURED"))
    }

    @Test
    fun `the json export names the device and platform`() {
        val json = render(ExportFormat.JSON)
        assertTrue(json.contains("\"manufacturer\": \"Google\""))
        assertTrue(json.contains("\"model\": \"Pixel 8\""))
        assertTrue(json.contains("\"apiLevel\": 34"))
    }

    /**
     * `status` is what the device can do; `availability` is whether this Android version
     * could even be asked. A consumer that had only the first would read an API-gated row
     * as a hardware limitation, which is the exact confusion Section 18 exists to prevent.
     */
    @Test
    fun `each json fact carries both its status and its availability`() {
        val json = render(ExportFormat.JSON)
        assertTrue(json.contains("\"statusToken\": \"not_exposed\""))
        assertTrue(json.contains("\"availability\": \"api_level\""))
        assertTrue(json.contains("\"availability\": \"available\""))
        assertTrue(json.contains("\"availability\": \"hardware\""))
    }

    @Test
    fun `each json fact carries the api that answered and the explanation`() {
        val json = render(ExportFormat.JSON)
        assertTrue(json.contains("\"kind\": \"requires-api\""))
        assertTrue(json.contains("\"api\": \"SCALER_AVAILABLE_STREAM_USE_CASES\""))
        assertTrue(json.contains("Requires API 36+ — this device is running API 34"))
    }

    @Test
    fun `the json export includes the scorecard with its counts`() {
        val json = render(ExportFormat.JSON)
        assertTrue(json.contains("\"scorecard\""))
        assertTrue(json.contains("\"domain\": \"Display\""))
        assertTrue(json.contains("\"statusGlyph\""))
        assertTrue(json.contains("\"checks\""))
    }

    @Test
    fun `the json export lists every lab that was scanned`() {
        val json = render(ExportFormat.JSON)
        assertTrue(json.contains("\"id\": \"display\""))
        assertTrue(json.contains("\"id\": \"camera\""))
        assertTrue(json.contains("\"title\": \"Cameras\""))
    }

    @Test
    fun `a nested section survives into the json`() {
        val json = render(ExportFormat.JSON)
        assertTrue(json.contains("\"title\": \"Camera 0\""))
    }

    @Test
    fun `a lab note is exported when present and omitted when not`() {
        assertTrue(render(ExportFormat.JSON).contains("Vendor camera service restarted"))
        val noNotes = exporter.render(
            CapabilityProfile(CAPTURED, listOf(LabReport(Lab.DISPLAY, emptyList()))),
            identity(),
            ExportFormat.JSON,
            "1.0",
        ).content
        assertFalse(noNotes.contains("\"notes\""))
    }

    /** A GL renderer string with a quote in it must not break the document. */
    @Test
    fun `a hostile device value is escaped in the json`() {
        val hostile = CapabilityProfile(
            capturedAtMillis = CAPTURED,
            reports = listOf(
                LabReport(
                    Lab.GRAPHICS,
                    listOf(
                        Section(
                            "gpu", "GPU",
                            facts = listOf(
                                Fact(
                                    "Renderer",
                                    "Mali-G\"78\" \\ MP14\n\t<script>",
                                    Provenance.Queried("glGetString()"),
                                ),
                            ),
                        )
                    ),
                )
            ),
        )
        val json = exporter.render(hostile, identity(), ExportFormat.JSON, "1.0").content
        assertBalanced(json)
        assertTrue(json.contains("\\\""))
        assertTrue(json.contains("\\\\"))
        assertTrue(json.contains("\\n"))
        assertTrue(json.none { it < ' ' && it != '\n' })
    }

    // ------------------------------------------------------------------- text

    @Test
    fun `the text export leads with the device and the platform`() {
        val text = render(ExportFormat.TEXT)
        assertTrue(text.startsWith("DEVICE CAPABILITY LAB — CAPABILITY REPORT"))
        assertTrue(text.contains("Device       Google Pixel 8"))
        assertTrue(text.contains("Platform     Android 14 (API 34)"))
        assertTrue(text.contains("Captured     2023-11-14T22:13:20Z"))
    }

    @Test
    fun `the text export has a summary a legend and a footer`() {
        val text = render(ExportFormat.TEXT)
        assertTrue(text.contains("CAPABILITY SUMMARY"))
        assertTrue(text.contains("✓ fully supported"))
        assertTrue(text.contains("Nothing is inferred, estimated or benchmarked"))
    }

    @Test
    fun `the text export shows every fact with its glyph and its provenance`() {
        val text = render(ExportFormat.TEXT)
        assertTrue(text.contains("✓ HDR10+: Supported"))
        assertTrue(text.contains("Queried · Display.getHdrCapabilities()"))
        assertTrue(text.contains("· Refresh rate: 120 Hz"))
    }

    /** An absence is only honest if the reason travels with it. */
    @Test
    fun `an api gated row explains itself in the text export`() {
        val text = render(ExportFormat.TEXT)
        assertTrue(text.contains("— Stream use cases: ${Absent.NOT_EXPOSED}"))
        assertTrue(text.contains("Requires API 36+ — this device is running API 34"))
    }

    @Test
    fun `a nested section is indented under its parent in the text export`() {
        val text = render(ExportFormat.TEXT)
        val cameras = text.lines().indexOfFirst { it.trim() == "Cameras" }
        val camera0 = text.lines().indexOfFirst { it.trim() == "Camera 0" }
        assertTrue("both sections must appear", cameras >= 0 && camera0 > cameras)
        // The child is indented further than its parent.
        assertTrue(text.lines()[camera0].takeWhile { it == ' ' }.length > 0)
    }

    @Test
    fun `a lab note appears in the text export`() {
        assertTrue(render(ExportFormat.TEXT).contains("note: Vendor camera service restarted"))
    }

    // ------------------------------------------------------------------- HTML

    @Test
    fun `the html export is a complete document`() {
        val html = render(ExportFormat.HTML)
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("<html lang=\"en\">"))
        assertTrue(html.contains("<meta charset=\"utf-8\">"))
        assertTrue(html.trimEnd().endsWith("</html>"))
    }

    @Test
    fun `every html tag that opens is closed`() {
        val html = render(ExportFormat.HTML)
        listOf("html", "head", "body", "header", "table", "thead", "tbody", "style", "footer")
            .forEach { tag ->
                assertEquals(
                    "<$tag> must be balanced",
                    Regex("<$tag[ >]").findAll(html).count(),
                    Regex("</$tag>").findAll(html).count(),
                )
            }
    }

    /**
     * Section 26: completely offline. An export that reached out to a CDN when opened
     * would break that and would disclose the scan to whoever served the asset.
     */
    @Test
    fun `the html export fetches nothing from the network`() {
        val html = render(ExportFormat.HTML)
        assertFalse(html.contains("http://"))
        assertFalse(html.contains("https://"))
        assertFalse(html.contains("<script"))
        assertFalse(html.contains("<link"))
        assertFalse(html.contains("@import"))
        assertFalse(html.contains("url("))
        // The styling is inline, so it renders with no companion files.
        assertTrue(html.contains("<style>"))
    }

    @Test
    fun `the html export carries the identity the scorecard and a contents list`() {
        val html = render(ExportFormat.HTML)
        assertTrue(html.contains("<h1>Google Pixel 8</h1>"))
        assertTrue(html.contains("Android 14 (API 34)"))
        assertTrue(html.contains("Capability summary"))
        assertTrue(html.contains("<nav class=\"toc\">"))
        assertTrue(html.contains("href=\"#display\""))
        assertTrue(html.contains("id=\"display\""))
    }

    @Test
    fun `the html tables are marked up for a screen reader`() {
        val html = render(ExportFormat.HTML)
        assertTrue(html.contains("<th scope=\"col\">Capability</th>"))
        assertTrue(html.contains("<th scope=\"row\">HDR10+</th>"))
    }

    @Test
    fun `each html row carries a status class so the styling follows the verdict`() {
        val html = render(ExportFormat.HTML)
        assertTrue(html.contains("class=\"s-supported\""))
        assertTrue(html.contains("class=\"s-not_exposed\""))
        assertTrue(html.contains("class=\"s-unsupported\""))
    }

    @Test
    fun `nested sections descend heading levels rather than repeating one`() {
        val html = render(ExportFormat.HTML)
        assertTrue(html.contains("<h3>Cameras</h3>"))
        assertTrue(html.contains("<h4>Camera 0</h4>"))
    }

    /** Angle brackets in a vendor string are common; unescaped they render as markup. */
    @Test
    fun `html special characters in device values are escaped`() {
        val hostile = CapabilityProfile(
            capturedAtMillis = CAPTURED,
            reports = listOf(
                LabReport(
                    Lab.GRAPHICS,
                    listOf(
                        Section(
                            "gpu", "GPU",
                            facts = listOf(
                                Fact(
                                    "Renderer",
                                    "<script>alert('x')</script> & \"more\"",
                                    Provenance.Queried("glGetString()"),
                                ),
                            ),
                        )
                    ),
                )
            ),
        )
        val html = exporter.render(hostile, identity(), ExportFormat.HTML, "1.0").content
        assertFalse(html.contains("<script>alert"))
        assertTrue(html.contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;"))
        assertTrue(html.contains("&amp; &quot;more&quot;"))
    }

    @Test
    fun `an escaped ampersand is not double escaped`() {
        val subject = CapabilityProfile(
            capturedAtMillis = CAPTURED,
            reports = listOf(
                LabReport(
                    Lab.GRAPHICS,
                    listOf(
                        Section(
                            "gpu", "GPU",
                            facts = listOf(Fact("A & B", "x", Provenance.Queried("Api.x()"))),
                        )
                    ),
                )
            ),
        )
        val html = exporter.render(subject, identity(), ExportFormat.HTML, "1.0").content
        assertTrue(html.contains("A &amp; B"))
        assertFalse(html.contains("&amp;amp;"))
    }

    // ------------------------------------------------------- across the formats

    /**
     * Section 20 forbids private user information, and the guard has to be structural
     * rather than a keyword scan: a real scan legitimately contains the words "IMEI" and
     * "serial number", in the security lab's rows stating that those are deliberately not
     * read. Searching for the words would fail on an honest report and pass on a
     * dishonest one that used different words.
     *
     * What can be pinned is the surface. The device block has exactly six keys, all of
     * them supplied by the caller, so a future detector cannot smuggle an identifier in
     * beside them without this failing.
     */
    @Test
    fun `the json device block has exactly the six documented fields`() {
        val json = render(ExportFormat.JSON)
        val block = json.substringAfter("\"device\": {").substringBefore("\n  }")
        val keys = Regex("\"([A-Za-z]+)\":").findAll(block).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf(
                "manufacturer", "model", "device",
                "androidRelease", "apiLevel", "buildFingerprint",
            ),
            keys,
        )
    }

    /**
     * The exporter reads no device state of its own; everything comes from its arguments.
     * If a future edit reached for `Build.MODEL` directly, this would fail -- under a JVM
     * test those fields are stubbed, so the stub would appear here instead of "Unknown".
     */
    @Test
    fun `nothing in the output comes from the platform behind the caller's back`() {
        val document = exporter.render(profile(), DeviceIdentity.UNKNOWN, ExportFormat.TEXT, "1.0")
        assertTrue(document.content.contains("Device       Unknown Unknown"))
        assertTrue(document.content.contains("Platform     Android Unknown (API 0)"))
        assertFalse(document.content.contains("null"))
    }

    @Test
    fun `rendering is a pure function of its arguments`() {
        assertEquals(render(ExportFormat.JSON), render(ExportFormat.JSON))
        assertEquals(render(ExportFormat.HTML), render(ExportFormat.HTML))
        val other = exporter.render(
            profile(),
            identity().copy(model = "Pixel 9"),
            ExportFormat.JSON,
            "9.9.9",
        ).content
        assertFalse(other == render(ExportFormat.JSON))
        assertTrue(other.contains("Pixel 9"))
    }

    /** One traversal, three renderers: they cannot disagree about what was read. */
    @Test
    fun `all three formats report the same values`() {
        val json = render(ExportFormat.JSON)
        val text = render(ExportFormat.TEXT)
        val html = render(ExportFormat.HTML)
        listOf("Pixel 8", "120 Hz", "HDR10+", "Camera 0", "RAW capture").forEach { value ->
            assertTrue("JSON is missing '$value'", json.contains(value))
            assertTrue("text is missing '$value'", text.contains(value))
            assertTrue("HTML is missing '$value'", html.contains(value))
        }
    }

    /** The reader-facing formats carry the statement; JSON carries the schema instead. */
    @Test
    fun `the human readable formats state that nothing was inferred`() {
        assertTrue(render(ExportFormat.TEXT).contains("read from an Android API"))
        assertTrue(render(ExportFormat.HTML).contains("read from an Android API"))
    }

    @Test
    fun `an empty profile exports without failing in any format`() {
        val empty = CapabilityProfile(capturedAtMillis = CAPTURED, reports = emptyList())
        ExportFormat.entries.forEach { format ->
            val document = exporter.render(empty, DeviceIdentity.UNKNOWN, format, "1.0")
            assertTrue("$format produced nothing", document.content.isNotBlank())
        }
        assertBalanced(exporter.render(empty, DeviceIdentity.UNKNOWN, ExportFormat.JSON, "1.0").content)
    }

    /** A scan on a device where nothing could be read must still export honestly. */
    @Test
    fun `a profile of nothing but absences still exports`() {
        val absences = CapabilityProfile(
            capturedAtMillis = CAPTURED,
            reports = Lab.entries.map { lab ->
                LabReport(
                    lab,
                    listOf(
                        Section(
                            lab.id, lab.title,
                            facts = listOf(
                                Fact(
                                    "Anything",
                                    Absent.NOT_EXPOSED,
                                    Provenance.NotExposedByAndroid("—", "no API on any version"),
                                    Support.NOT_EXPOSED,
                                ),
                            ),
                        )
                    ),
                )
            },
        )
        ExportFormat.entries.forEach { format ->
            val content = exporter.render(absences, identity(), format, "1.0").content
            assertTrue(content.contains(Absent.NOT_EXPOSED) || content.contains("not_exposed"))
        }
    }

    // -------------------------------------------------------------- fixtures

    private fun render(format: ExportFormat): String =
        exporter.render(profile(), identity(), format, "9.9.9").content

    /**
     * A brace and bracket balance check.
     *
     * Counting is done outside string literals only, so a device value containing `{`
     * cannot make an unbalanced document look balanced or the reverse.
     */
    private fun assertBalanced(json: String) {
        var depth = 0
        var brackets = 0
        var inString = false
        var escaped = false
        json.forEach { c ->
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> depth--
                c == '[' -> brackets++
                c == ']' -> brackets--
            }
            assertTrue("braces went negative", depth >= 0)
            assertTrue("brackets went negative", brackets >= 0)
        }
        assertEquals("unbalanced braces", 0, depth)
        assertEquals("unbalanced brackets", 0, brackets)
        assertFalse("unterminated string", inString)
    }

    private fun identity() = DeviceIdentity(
        manufacturer = "Google",
        model = "Pixel 8",
        device = "shiba",
        androidRelease = "14",
        apiLevel = 34,
        fingerprint = "google/shiba/shiba:14/AP1A.240505.004/1:user/release-keys",
    )

    private fun profile() = CapabilityProfile(
        capturedAtMillis = CAPTURED,
        reports = listOf(
            LabReport(
                lab = Lab.DISPLAY,
                sections = listOf(
                    Section(
                        id = "panel",
                        title = "Panel",
                        subtitle = "Built-in display",
                        facts = listOf(
                            Fact("Refresh rate", "120 Hz", Provenance.Queried("Display.getMode()")),
                            Fact(
                                label = "HDR10+",
                                value = "Supported",
                                provenance = Provenance.Queried("Display.getHdrCapabilities()"),
                                support = Support.SUPPORTED,
                                domain = Domain.DISPLAY,
                                detail = "HDR_TYPE_HDR10_PLUS is present",
                            ),
                        ),
                    )
                ),
            ),
            LabReport(
                lab = Lab.CAMERA,
                notes = listOf("Vendor camera service restarted during the scan"),
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
                                        domain = Domain.CAMERA,
                                    ),
                                    Fact(
                                        label = "Stream use cases",
                                        value = Absent.NOT_EXPOSED,
                                        provenance = Provenance.RequiresApi(
                                            "SCALER_AVAILABLE_STREAM_USE_CASES",
                                            36,
                                            34,
                                        ),
                                        support = Support.NOT_EXPOSED,
                                        domain = Domain.CAMERA,
                                    ),
                                ),
                            )
                        ),
                    )
                ),
            ),
        ),
    )

    private companion object {
        /** 2023-11-14T22:13:20Z. */
        const val CAPTURED = 1_700_000_000_000L
    }
}
