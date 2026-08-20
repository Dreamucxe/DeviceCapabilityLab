package com.devicelab.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hand-written JSON writer.
 *
 * Escaping is not cosmetic here. GL renderer strings contain quotes, kernel version
 * strings contain backslashes, and OEM feature descriptions contain newlines and the
 * occasional control character. A single missed escape produces a document no parser
 * will accept, and the user would only find out long after the export appeared to
 * succeed.
 */
class JsonTest {

    @Test
    fun `a plain string is quoted and unchanged`() {
        assertEquals("\"Adreno 740\"", Json.escape("Adreno 740"))
    }

    @Test
    fun `quotes and backslashes are escaped`() {
        assertEquals("\"say \\\"hi\\\"\"", Json.escape("say \"hi\""))
        assertEquals("\"C:\\\\path\"", Json.escape("C:\\path"))
    }

    @Test
    fun `the five named control escapes are emitted by name`() {
        assertEquals("\"\\n\"", Json.escape("\n"))
        assertEquals("\"\\r\"", Json.escape("\r"))
        assertEquals("\"\\t\"", Json.escape("\t"))
        assertEquals("\"\\b\"", Json.escape("\b"))
        assertEquals("\"\\f\"", Json.escape("\u000C"))
    }

    @Test
    fun `other control characters become four digit unicode escapes`() {
        assertEquals("\"\\u0000\"", Json.escape("\u0000"))
        assertEquals("\"\\u001f\"", Json.escape("\u001F"))
        assertEquals("\"\\u0001\"", Json.escape("\u0001"))
    }

    /**
     * Both are legal JSON and both terminate a line in JavaScript, so an unescaped one
     * turns a valid document into a syntax error the moment anything evaluates it.
     */
    @Test
    fun `the line and paragraph separators are escaped`() {
        assertEquals("\"\\u2028\"", Json.escape("\u2028"))
        assertEquals("\"\\u2029\"", Json.escape("\u2029"))
    }

    @Test
    fun `printable non ascii is passed through rather than escaped`() {
        assertEquals("\"1080 × 2400\"", Json.escape("1080 × 2400"))
        assertEquals("\"◐\"", Json.escape("◐"))
    }

    @Test
    fun `space is not escaped although it borders the control range`() {
        assertEquals("\" \"", Json.escape(" "))
    }

    @Test
    fun `an empty string is a pair of quotes`() {
        assertEquals("\"\"", Json.escape(""))
    }

    @Test
    fun `keys are escaped as well as values`() {
        val rendered = Json.Obj(listOf("with\"quote" to Json.Str("v"))).render(indent = null)
        assertEquals("{\"with\\\"quote\":\"v\"}", rendered)
    }

    @Test
    fun `scalars render as their json forms`() {
        assertEquals("42", Json.Num(42).render())
        assertEquals("-7", Json.Num(-7).render())
        assertEquals("true", Json.Bool(true).render())
        assertEquals("false", Json.Bool(false).render())
        assertEquals("null", Json.Null.render())
    }

    @Test
    fun `a whole number decimal drops its trailing zero`() {
        assertEquals("60", Json.Dec(60.0).render())
        assertEquals("59.94", Json.Dec(59.94).render())
    }

    /**
     * NaN and the infinities have no JSON representation. Emitting the bare token would
     * produce a document no parser accepts, so they become null.
     */
    @Test
    fun `non finite decimals render as null`() {
        assertEquals("null", Json.Dec(Double.NaN).render())
        assertEquals("null", Json.Dec(Double.POSITIVE_INFINITY).render())
        assertEquals("null", Json.Dec(Double.NEGATIVE_INFINITY).render())
    }

    @Test
    fun `empty containers render compactly at any indent`() {
        assertEquals("[]", Json.Arr(emptyList()).render())
        assertEquals("{}", Json.Obj(emptyList()).render())
        assertEquals("[]", Json.Arr(emptyList()).render(indent = null))
    }

    @Test
    fun `a null indent produces one compact line`() {
        val subject = Json.obj(
            "a" to Json.Num(1),
            "b" to Json.arr(listOf(Json.Str("x"), Json.Str("y"))),
        )
        assertEquals("{\"a\":1,\"b\":[\"x\",\"y\"]}", subject.render(indent = null))
    }

    @Test
    fun `an indent produces nested lines with growing padding`() {
        val rendered = Json.obj("outer" to Json.obj("inner" to Json.Num(1))).render()
        assertEquals(
            """
            {
              "outer": {
                "inner": 1
              }
            }
            """.trimIndent(),
            rendered,
        )
    }

    /** Insertion order must survive, or two scans of one device would look different. */
    @Test
    fun `object key order is the order given`() {
        val rendered = Json.obj(
            "zebra" to Json.Num(1),
            "apple" to Json.Num(2),
            "mango" to Json.Num(3),
        ).render(indent = null)
        assertEquals("{\"zebra\":1,\"apple\":2,\"mango\":3}", rendered)
    }

    @Test
    fun `obj drops entries whose value is null`() {
        val rendered = Json.obj(
            "kept" to Json.Str("v"),
            "dropped" to null,
            "alsoKept" to Json.Num(1),
        ).render(indent = null)
        assertEquals("{\"kept\":\"v\",\"alsoKept\":1}", rendered)
    }

    /** An explicit JSON null is different from an omitted key, and both are needed. */
    @Test
    fun `an explicit json null is kept while a kotlin null is omitted`() {
        val rendered = Json.obj(
            "explicit" to Json.Null,
            "omitted" to null,
        ).render(indent = null)
        assertEquals("{\"explicit\":null}", rendered)
    }

    @Test
    fun `the of helpers map kotlin nulls to json null`() {
        assertEquals(Json.Null, Json.of(null as String?))
        assertEquals(Json.Null, Json.of(null as Int?))
        assertEquals(Json.Null, Json.of(null as Long?))
        assertEquals(Json.Null, Json.of(null as Boolean?))
        assertEquals(Json.Str("v"), Json.of("v"))
        assertEquals(Json.Num(3L), Json.of(3))
        assertEquals(Json.Num(3L), Json.of(3L))
        assertEquals(Json.Bool(true), Json.of(true))
    }

    @Test
    fun `strings builds an array from any collection`() {
        assertEquals("[\"a\",\"b\"]", Json.strings(listOf("a", "b")).render(indent = null))
        assertEquals("[]", Json.strings(emptySet()).render(indent = null))
    }

    /**
     * The end-to-end guard: a value carrying every awkward character must still come
     * out as one balanced, quote-consistent document.
     */
    @Test
    fun `a hostile device string still produces balanced json`() {
        val hostile = "Renderer \"X\"\n\tv1\\2\u0007 \u2028 <&>"
        val rendered = Json.obj("renderer" to Json.Str(hostile)).render(indent = null)

        assertTrue(rendered.startsWith("{\"renderer\":\""))
        assertTrue(rendered.endsWith("\"}"))
        assertTrue(rendered.contains("\\\""))
        assertTrue(rendered.contains("\\n"))
        assertTrue(rendered.contains("\\t"))
        assertTrue(rendered.contains("\\\\"))
        assertTrue(rendered.contains("\\u0007"))
        assertTrue(rendered.contains("\\u2028"))
        // Every raw control character is gone.
        assertTrue(rendered.none { it < ' ' })
        // Once the escaped quotes are removed, only the four delimiters remain: two
        // around the key and two around the value. A bare quote would push this higher.
        assertEquals(4, rendered.replace("\\\"", "").count { it == '"' })
    }
}
