package com.devicelab.data.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `getprop` output parser.
 *
 * Several genuinely useful platform facts -- Treble, A/B updates, dynamic partitions,
 * VNDK version -- have no public API of any kind, so the property store is the only place
 * to read them. When the reflective path is blocked by hidden-API enforcement, the
 * fallback is one `getprop` invocation, and its output has to be parsed.
 *
 * The format is `[name]: [value]`, and the reason this needs its own test is that values
 * routinely contain the characters a naive split would use as delimiters: a build
 * fingerprint contains colons and slashes, a kernel string contains brackets and spaces,
 * and a date contains colons.
 */
class SystemPropertiesTest {

    @Test
    fun `a simple property parses`() {
        val parsed = SystemProperties.parseGetprop("[ro.build.version.sdk]: [34]")
        assertEquals(mapOf("ro.build.version.sdk" to "34"), parsed)
    }

    @Test
    fun `several properties parse in file order`() {
        val parsed = SystemProperties.parseGetprop(
            """
            [ro.product.manufacturer]: [Google]
            [ro.product.model]: [Pixel 8]
            [ro.build.version.sdk]: [34]
            """.trimIndent()
        )
        assertEquals(3, parsed.size)
        assertEquals(
            listOf("ro.product.manufacturer", "ro.product.model", "ro.build.version.sdk"),
            parsed.keys.toList(),
        )
        assertEquals("Pixel 8", parsed["ro.product.model"])
    }

    /** A fingerprint is the value most likely to break a careless parser. */
    @Test
    fun `a value containing colons and slashes survives intact`() {
        val fingerprint = "google/shiba/shiba:14/AP1A.240505.004/11583682:user/release-keys"
        val parsed = SystemProperties.parseGetprop("[ro.build.fingerprint]: [$fingerprint]")
        assertEquals(fingerprint, parsed["ro.build.fingerprint"])
    }

    @Test
    fun `a value containing spaces survives intact`() {
        val parsed = SystemProperties.parseGetprop(
            "[ro.build.description]: [shiba-user 14 AP1A.240505.004 11583682 release-keys]"
        )
        assertEquals(
            "shiba-user 14 AP1A.240505.004 11583682 release-keys",
            parsed["ro.build.description"],
        )
    }

    /** Splitting on the last `]` is what lets a value contain brackets of its own. */
    @Test
    fun `a value containing brackets survives intact`() {
        val parsed = SystemProperties.parseGetprop("[ro.kernel.version]: [6.1.57-android14 [smp]]")
        assertEquals("6.1.57-android14 [smp]", parsed["ro.kernel.version"])
    }

    @Test
    fun `a value containing the separator itself survives intact`() {
        val parsed = SystemProperties.parseGetprop("[some.prop]: [a]: [b]")
        assertEquals("a]: [b", parsed["some.prop"])
    }

    @Test
    fun `an empty value is dropped rather than stored as an empty string`() {
        val parsed = SystemProperties.parseGetprop(
            """
            [ro.set.prop]: [value]
            [ro.unset.prop]: []
            """.trimIndent()
        )
        assertEquals(1, parsed.size)
        assertTrue(parsed.containsKey("ro.set.prop"))
        assertFalse("an unset property must not read as present", parsed.containsKey("ro.unset.prop"))
    }

    @Test
    fun `malformed lines are skipped rather than failing the whole parse`() {
        val parsed = SystemProperties.parseGetprop(
            """
            [ro.good.one]: [yes]

            not a property line at all
            [ro.no.separator] [nope]
            []: [no key]
            [ro.good.two]: [also yes]
            """.trimIndent()
        )
        assertEquals(setOf("ro.good.one", "ro.good.two"), parsed.keys)
    }

    @Test
    fun `empty output yields an empty map`() {
        assertEquals(emptyMap<String, String>(), SystemProperties.parseGetprop(""))
        assertEquals(emptyMap<String, String>(), SystemProperties.parseGetprop("\n\n\n"))
    }

    @Test
    fun `a later duplicate wins because that is what getprop prints last`() {
        val parsed = SystemProperties.parseGetprop(
            """
            [ro.prop]: [first]
            [ro.prop]: [second]
            """.trimIndent()
        )
        assertEquals("second", parsed["ro.prop"])
        assertEquals(1, parsed.size)
    }

    /** Real output is thousands of lines; the whole store is parsed in one pass. */
    @Test
    fun `a realistic block parses every property`() {
        val parsed = SystemProperties.parseGetprop(GETPROP_EXCERPT)
        assertEquals("1", parsed["ro.treble.enabled"])
        assertEquals("true", parsed["ro.build.ab_update"])
        assertEquals("34", parsed["ro.vndk.version"])
        assertEquals("green", parsed["ro.boot.verifiedbootstate"])
        assertEquals("2", parsed["ro.boot.flash.locked"])
        assertEquals(5, parsed.size)
    }

    private companion object {
        val GETPROP_EXCERPT = """
            [ro.boot.flash.locked]: [2]
            [ro.boot.verifiedbootstate]: [green]
            [ro.build.ab_update]: [true]
            [ro.treble.enabled]: [1]
            [ro.vndk.version]: [34]
        """.trimIndent()
    }
}
