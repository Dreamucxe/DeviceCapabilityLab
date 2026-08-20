package com.devicelab.core.common

import java.util.Locale

/**
 * A minimal JSON value tree and serializer.
 *
 * Hand-written rather than pulled from a library because the app has exactly one JSON
 * requirement -- writing the export in Section 20 -- and it never parses any. Adding
 * kotlinx.serialization for a one-way traversal would mean a compiler plugin, a second
 * annotation processor and a larger APK to do less than the code below.
 *
 * Correctness here is not cosmetic. Device data is full of characters that break naive
 * string concatenation: GL renderer strings contain quotes, kernel version strings
 * contain backslashes, and OEM feature descriptions contain newlines and the occasional
 * stray control character. Every one of those is escaped per RFC 8259, so a value is
 * either valid JSON or it does not get written.
 *
 * Snapshots are not stored through this type. They live in Room as normalised rows,
 * which is why nothing here needs to read JSON back.
 */
sealed interface Json {

    /** A string. Escaped when rendered, never stored escaped. */
    data class Str(val value: String) : Json

    /** An integral number. */
    data class Num(val value: Long) : Json

    /**
     * A fractional number.
     *
     * NaN and the infinities have no JSON representation, so they render as null. The
     * alternative -- emitting the bare token `NaN` -- produces a document no parser will
     * accept, which would silently break an export the user believes succeeded.
     */
    data class Dec(val value: Double) : Json

    data class Bool(val value: Boolean) : Json

    /** JSON `null`. Named [Null] rather than `Nothing` so it cannot shadow the type. */
    data object Null : Json

    data class Arr(val items: List<Json>) : Json

    /**
     * An object.
     *
     * A list of pairs rather than a map, so insertion order survives to the output. An
     * export whose keys reordered between runs would make two scans of one unchanged
     * device look different to any external diff tool.
     */
    data class Obj(val entries: List<Pair<String, Json>>) : Json

    /** This value as JSON text. An [indent] of null produces one compact line. */
    fun render(indent: String? = "  "): String =
        StringBuilder().also { writeJson(it, this, indent, 0) }.toString()

    companion object {

        /**
         * A quoted, escaped JSON string.
         *
         * Both mandatory escapes and the five named control escapes are emitted by name;
         * everything else below U+0020 becomes a `\uXXXX` sequence. U+2028 and U+2029 are
         * escaped as well: both are legal in JSON and both terminate a line in
         * JavaScript, so an unescaped one turns a valid document into a syntax error the
         * moment anything evaluates it.
         */
        fun escape(value: String): String {
            val out = StringBuilder(value.length + 2)
            out.append('"')
            value.forEach { c ->
                when {
                    c == '"' -> out.append("\\\"")
                    c == '\\' -> out.append("\\\\")
                    c == '\n' -> out.append("\\n")
                    c == '\r' -> out.append("\\r")
                    c == '\t' -> out.append("\\t")
                    c == '\b' -> out.append("\\b")
                    c == FORM_FEED -> out.append("\\f")
                    c < ' ' || c == LINE_SEPARATOR || c == PARAGRAPH_SEPARATOR ->
                        out.append(String.format(Locale.US, "\\u%04x", c.code))
                    else -> out.append(c)
                }
            }
            out.append('"')
            return out.toString()
        }

        fun of(value: String?): Json = if (value == null) Null else Str(value)

        fun of(value: Int?): Json = if (value == null) Null else Num(value.toLong())

        fun of(value: Long?): Json = if (value == null) Null else Num(value)

        fun of(value: Boolean?): Json = if (value == null) Null else Bool(value)

        /** An object from pairs, dropping any entry whose value is null. */
        fun obj(vararg entries: Pair<String, Json?>): Obj =
            Obj(entries.mapNotNull { (key, value) -> value?.let { key to it } })

        fun arr(items: List<Json>): Arr = Arr(items)

        fun strings(items: Collection<String>): Arr = Arr(items.map { Str(it) })

        private const val FORM_FEED = '\u000C'
        private const val LINE_SEPARATOR = '\u2028'
        private const val PARAGRAPH_SEPARATOR = '\u2029'
    }
}

private fun writeJson(out: StringBuilder, value: Json, indent: String?, depth: Int) {
    when (value) {
        is Json.Str -> out.append(Json.escape(value.value))
        is Json.Num -> out.append(value.value.toString())
        is Json.Dec ->
            if (value.value.isFinite()) out.append(trimDouble(value.value)) else out.append("null")
        is Json.Bool -> out.append(if (value.value) "true" else "false")
        is Json.Null -> out.append("null")
        is Json.Arr -> {
            if (value.items.isEmpty()) {
                out.append("[]")
                return
            }
            val inner = innerPad(indent, depth)
            out.append('[')
            value.items.forEachIndexed { index, item ->
                if (index > 0) out.append(',')
                out.append(inner)
                writeJson(out, item, indent, depth + 1)
            }
            out.append(outerPad(indent, depth)).append(']')
        }
        is Json.Obj -> {
            if (value.entries.isEmpty()) {
                out.append("{}")
                return
            }
            val inner = innerPad(indent, depth)
            out.append('{')
            value.entries.forEachIndexed { index, (key, item) ->
                if (index > 0) out.append(',')
                out.append(inner)
                out.append(Json.escape(key)).append(':')
                if (indent != null) out.append(' ')
                writeJson(out, item, indent, depth + 1)
            }
            out.append(outerPad(indent, depth)).append('}')
        }
    }
}

private fun innerPad(indent: String?, depth: Int): String =
    if (indent == null) "" else "\n" + indent.repeat(depth + 1)

private fun outerPad(indent: String?, depth: Int): String =
    if (indent == null) "" else "\n" + indent.repeat(depth)

/** Drops a trailing `.0` so a whole number does not read as an approximation. */
private fun trimDouble(value: Double): String {
    val text = value.toString()
    return if (text.endsWith(".0")) text.dropLast(2) else text
}
