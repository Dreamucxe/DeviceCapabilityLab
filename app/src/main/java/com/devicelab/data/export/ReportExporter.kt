package com.devicelab.data.export

import com.devicelab.core.common.Json
import com.devicelab.core.common.Timestamps
import com.devicelab.core.model.Availability
import com.devicelab.core.model.CapabilityProfile
import com.devicelab.core.model.DeviceIdentity
import com.devicelab.core.model.DomainStatus
import com.devicelab.core.model.Fact
import com.devicelab.core.model.Lab
import com.devicelab.core.model.LabReport
import com.devicelab.core.model.Section
import com.devicelab.core.model.Support

/** The three formats Section 20 requires. */
enum class ExportFormat(
    val id: String,
    val label: String,
    val extension: String,
    val mimeType: String,
) {
    JSON("json", "JSON", "json", "application/json"),
    TEXT("text", "Plain text", "txt", "text/plain"),
    HTML("html", "HTML", "html", "text/html"),
}

/** A rendered export, ready to write or share. */
data class ExportDocument(
    val format: ExportFormat,
    val filename: String,
    val content: String,
) {
    val sizeBytes: Int get() = content.toByteArray(Charsets.UTF_8).size
}

/**
 * Renders a scan as JSON, plain text or HTML.
 *
 * What is *not* here matters as much as what is. Section 20 forbids private user
 * information, and a device report is a natural place for it to leak in: the build
 * fingerprint identifies a specific OS image, `Build.SERIAL` and the DRM device unique
 * ID identify the individual handset, and installed-package lists describe the person
 * rather than the hardware. None of those are read anywhere in this app -- the
 * detectors record a [com.devicelab.core.model.Provenance.NotRead] row instead -- so
 * there is nothing here to strip. The fingerprint is the one borderline case, and it is
 * included because it names the OS build, which is a capability-determining fact; it is
 * also the value an engineer needs to reproduce a report.
 *
 * All three formats are produced from the same [CapabilityProfile] tree by the same
 * traversal, so they cannot disagree about what the device said.
 */
class ReportExporter {

    fun render(
        profile: CapabilityProfile,
        identity: DeviceIdentity,
        format: ExportFormat,
        appVersion: String,
    ): ExportDocument = ExportDocument(
        format = format,
        filename = filename(identity, profile.capturedAtMillis, format),
        content = when (format) {
            ExportFormat.JSON -> json(profile, identity, appVersion)
            ExportFormat.TEXT -> text(profile, identity, appVersion)
            ExportFormat.HTML -> html(profile, identity, appVersion)
        },
    )

    /**
     * A filename built from the model and the capture time.
     *
     * Model strings are vendor-supplied and arbitrary. Everything outside
     * `[A-Za-z0-9_-]` is collapsed to a single dash -- including the dot, so that the
     * only dot in the result is the one before the extension. That rule costs nothing
     * (a model name's punctuation is not information) and it removes the whole question
     * of what a name like `../..` would mean to whatever writes the file.
     */
    fun filename(identity: DeviceIdentity, millis: Long, format: ExportFormat): String {
        val model = identity.model
            .replace(Regex("[^A-Za-z0-9_-]+"), "-")
            .trim('-')
            .ifBlank { "device" }
        return "capability-$model-${Timestamps.forFilename(millis)}.${format.extension}"
    }

    // ---------------------------------------------------------------- JSON

    private fun json(
        profile: CapabilityProfile,
        identity: DeviceIdentity,
        appVersion: String,
    ): String = Json.obj(
        "schema" to Json.Str(JSON_SCHEMA),
        "generator" to Json.obj(
            "app" to Json.Str(APP_NAME),
            "version" to Json.Str(appVersion),
        ),
        "capturedAt" to Json.Str(Timestamps.iso(profile.capturedAtMillis)),
        "capturedAtMillis" to Json.Num(profile.capturedAtMillis),
        "device" to Json.obj(
            "manufacturer" to Json.of(identity.manufacturer),
            "model" to Json.of(identity.model),
            "device" to Json.of(identity.device),
            "androidRelease" to Json.of(identity.androidRelease),
            "apiLevel" to Json.of(identity.apiLevel),
            "buildFingerprint" to Json.of(identity.fingerprint),
        ),
        "scorecard" to Json.arr(profile.scorecard.map(::jsonDomain)),
        "labs" to Json.arr(profile.reports.map(::jsonLab)),
    ).render()

    private fun jsonDomain(status: DomainStatus): Json = Json.obj(
        "domain" to Json.Str(status.domain.title),
        "status" to Json.Str(status.support.label),
        "statusGlyph" to Json.Str(status.support.glyph),
        "summary" to Json.Str(status.summary),
        "supported" to Json.Num(status.supported.toLong()),
        "checks" to Json.Num(status.total.toLong()),
        "notExposed" to Json.Num(status.notExposed.toLong()),
        "unknown" to Json.Num(status.unknown.toLong()),
        "unsupported" to Json.Num(status.unsupported.toLong()),
        "measurements" to Json.Num(status.measurements.toLong()),
    )

    private fun jsonLab(report: LabReport): Json = Json.obj(
        "id" to Json.Str(report.lab.id),
        "title" to Json.Str(report.lab.title),
        "notes" to if (report.notes.isEmpty()) null else Json.strings(report.notes),
        "sections" to Json.arr(report.sections.map(::jsonSection)),
    )

    private fun jsonSection(section: Section): Json = Json.obj(
        "id" to Json.Str(section.id),
        "title" to Json.Str(section.title),
        "subtitle" to Json.of(section.subtitle),
        "facts" to if (section.facts.isEmpty()) null else Json.arr(section.facts.map(::jsonFact)),
        "sections" to if (section.children.isEmpty()) {
            null
        } else {
            Json.arr(section.children.map(::jsonSection))
        },
    )

    /**
     * One fact.
     *
     * `availability` is emitted alongside `status` because they answer different
     * questions and a consumer needs both: `status` is what the device can do,
     * `availability` is whether this Android version could even be asked. A script
     * that collapsed the two would draw exactly the wrong conclusion from an older
     * device.
     */
    private fun jsonFact(fact: Fact): Json = Json.obj(
        "label" to Json.Str(fact.label),
        "value" to Json.Str(fact.value),
        "status" to Json.Str(fact.support.label),
        "statusToken" to Json.Str(fact.support.name.lowercase()),
        "availability" to Json.Str(Availability.of(fact.provenance).name.lowercase()),
        "provenance" to Json.obj(
            "kind" to Json.Str(fact.provenance.kind),
            "api" to Json.Str(fact.provenance.api),
            "explanation" to Json.Str(fact.provenance.explanation),
        ),
        "domain" to fact.domain?.let { Json.Str(it.title) },
        "detail" to Json.of(fact.detail),
    )

    // ---------------------------------------------------------------- text

    private fun text(
        profile: CapabilityProfile,
        identity: DeviceIdentity,
        appVersion: String,
    ): String = buildString {
        appendLine(APP_NAME.uppercase() + " — CAPABILITY REPORT")
        appendLine("=".repeat(58))
        appendLine()
        appendLine("Device       ${identity.manufacturer} ${identity.model}".trimEnd())
        appendLine("Codename     ${identity.device}")
        appendLine("Platform     Android ${identity.androidRelease} (API ${identity.apiLevel})")
        appendLine("Build        ${identity.fingerprint}")
        appendLine("Captured     ${Timestamps.iso(profile.capturedAtMillis)}")
        appendLine("Generated by $APP_NAME $appVersion")
        appendLine()
        appendLine("CAPABILITY SUMMARY")
        appendLine("-".repeat(58))
        profile.scorecard.forEach { status ->
            appendLine(
                "  ${status.support.glyph}  ${status.domain.title.padEnd(14)}" +
                    "${status.support.label.padEnd(20)}${status.summary}"
            )
        }
        appendLine()
        appendLine(LEGEND_TEXT)
        appendLine()

        profile.reports.forEach { report ->
            appendLine()
            appendLine(report.lab.title.uppercase())
            appendLine("=".repeat(58))
            if (report.notes.isNotEmpty()) {
                report.notes.forEach { appendLine("  note: $it") }
                appendLine()
            }
            report.sections.forEach { textSection(it, 0) }
        }
        appendLine()
        appendLine("-".repeat(58))
        appendLine(FOOTER_TEXT)
    }

    private fun StringBuilder.textSection(section: Section, depth: Int) {
        val pad = "  ".repeat(depth)
        appendLine()
        appendLine("$pad${section.title}")
        section.subtitle?.let { appendLine("$pad  ($it)") }
        appendLine("$pad${"-".repeat((54 - depth * 2).coerceAtLeast(12))}")
        section.facts.forEach { fact ->
            appendLine("$pad  ${fact.support.glyph} ${fact.label}: ${fact.value}")
            appendLine("$pad      ${fact.provenance.explanation}")
        }
        section.children.forEach { textSection(it, depth + 1) }
    }

    // ---------------------------------------------------------------- HTML

    /**
     * A single self-contained HTML file.
     *
     * No external stylesheet, font or script: Section 26 requires the app to work
     * completely offline, and an export that fetched a font from a CDN when opened
     * would both break that promise and tell a third party which device was scanned.
     */
    private fun html(
        profile: CapabilityProfile,
        identity: DeviceIdentity,
        appVersion: String,
    ): String = buildString {
        val title = "${identity.manufacturer} ${identity.model}".trim()
        appendLine("<!DOCTYPE html>")
        appendLine("<html lang=\"en\">")
        appendLine("<head>")
        appendLine("<meta charset=\"utf-8\">")
        appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        appendLine("<title>${esc(title)} — Capability Report</title>")
        appendLine("<style>$CSS</style>")
        appendLine("</head>")
        appendLine("<body>")
        appendLine("<header>")
        appendLine("<h1>${esc(title)}</h1>")
        appendLine("<dl class=\"identity\">")
        htmlIdentity("Codename", identity.device)
        htmlIdentity("Platform", "Android ${identity.androidRelease} (API ${identity.apiLevel})")
        htmlIdentity("Build", identity.fingerprint)
        htmlIdentity("Captured", Timestamps.iso(profile.capturedAtMillis))
        htmlIdentity("Report", "$APP_NAME $appVersion")
        appendLine("</dl>")
        appendLine("</header>")

        appendLine("<section class=\"scorecard\">")
        appendLine("<h2>Capability summary</h2>")
        appendLine("<div class=\"cards\">")
        profile.scorecard.forEach { status ->
            appendLine("<div class=\"card ${cssClass(status.support)}\">")
            appendLine("<span class=\"glyph\">${esc(status.support.glyph)}</span>")
            appendLine("<span class=\"domain\">${esc(status.domain.title)}</span>")
            appendLine("<span class=\"state\">${esc(status.support.label)}</span>")
            appendLine("<span class=\"detail\">${esc(status.summary)}</span>")
            appendLine("</div>")
        }
        appendLine("</div>")
        appendLine("<p class=\"legend\">${esc(LEGEND_TEXT)}</p>")
        appendLine("</section>")

        appendLine("<nav class=\"toc\"><h2>Contents</h2><ul>")
        profile.reports.forEach { report ->
            appendLine(
                "<li><a href=\"#${esc(report.lab.id)}\">${esc(report.lab.title)}</a></li>"
            )
        }
        appendLine("</ul></nav>")

        profile.reports.forEach { report -> htmlLab(report) }

        appendLine("<footer><p>${esc(FOOTER_TEXT)}</p></footer>")
        appendLine("</body>")
        appendLine("</html>")
    }

    private fun StringBuilder.htmlIdentity(label: String, value: String) {
        appendLine("<dt>${esc(label)}</dt><dd>${esc(value)}</dd>")
    }

    private fun StringBuilder.htmlLab(report: LabReport) {
        appendLine("<section class=\"lab\" id=\"${esc(report.lab.id)}\">")
        appendLine("<h2>${esc(report.lab.title)}</h2>")
        appendLine("<p class=\"blurb\">${esc(report.lab.blurb)}</p>")
        report.notes.forEach { appendLine("<p class=\"note\">${esc(it)}</p>") }
        report.sections.forEach { htmlSection(it, 3) }
        appendLine("</section>")
    }

    private fun StringBuilder.htmlSection(section: Section, level: Int) {
        val h = "h${level.coerceAtMost(6)}"
        appendLine("<div class=\"block\">")
        appendLine("<$h>${esc(section.title)}</$h>")
        section.subtitle?.let { appendLine("<p class=\"subtitle\">${esc(it)}</p>") }
        if (section.facts.isNotEmpty()) {
            appendLine("<table>")
            appendLine(
                "<thead><tr><th scope=\"col\">Capability</th>" +
                    "<th scope=\"col\">Status</th><th scope=\"col\">Details</th></tr></thead>"
            )
            appendLine("<tbody>")
            section.facts.forEach { fact ->
                appendLine("<tr class=\"${cssClass(fact.support)}\">")
                appendLine("<th scope=\"row\">${esc(fact.label)}</th>")
                appendLine(
                    "<td class=\"value\"><span class=\"glyph\">${esc(fact.support.glyph)}</span> " +
                        "${esc(fact.value)}</td>"
                )
                append("<td class=\"prov\">${esc(fact.provenance.explanation)}")
                fact.detail?.let { append("<span class=\"note\">${esc(it)}</span>") }
                appendLine("</td>")
                appendLine("</tr>")
            }
            appendLine("</tbody></table>")
        }
        section.children.forEach { htmlSection(it, level + 1) }
        appendLine("</div>")
    }

    private fun cssClass(support: Support): String = "s-" + support.name.lowercase()

    /**
     * HTML escaping.
     *
     * Applied to every interpolated value without exception. Device strings are not
     * trusted input in the security sense, but they are arbitrary: GL renderer strings
     * and OEM feature names contain angle brackets and ampersands often enough that an
     * unescaped report would simply render wrong, and a value containing `<script` in
     * a file the user opens in a browser is not a risk worth taking either way.
     */
    private fun esc(value: String): String {
        val out = StringBuilder(value.length + 16)
        value.forEach { c ->
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&#39;")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    companion object {
        const val APP_NAME = "Device Capability Lab"
        const val JSON_SCHEMA = "device-capability-lab/report/1"

        private const val LEGEND_TEXT =
            "✓ fully supported · ◐ partially supported · — not exposed by this " +
                "Android version or by Android at all · ✕ queried and unsupported · " +
                "? no determinate answer"

        private const val FOOTER_TEXT =
            "Every value in this report was read from an Android API on the device " +
                "named above. Nothing is inferred, estimated or benchmarked. Where a " +
                "value is absent, the row states which API was called and why it did " +
                "not answer. No user or account information is collected or included."

        private val CSS = """
            :root {
              color-scheme: dark light;
              --bg: #0b0d10; --surface: #14171c; --surface2: #1b1f26;
              --text: #e7ebf0; --muted: #9aa4b2; --line: #262b33;
              --ok: #4ade80; --partial: #fbbf24; --no: #f87171; --none: #6b7280;
            }
            * { box-sizing: border-box; }
            body {
              margin: 0; padding: 24px; background: var(--bg); color: var(--text);
              font: 15px/1.55 -apple-system, "Segoe UI", Roboto, "Helvetica Neue",
                    system-ui, sans-serif;
              max-width: 1080px; margin-inline: auto;
            }
            h1 { font-size: 30px; margin: 0 0 4px; letter-spacing: -0.4px; }
            h2 { font-size: 21px; margin: 32px 0 8px; letter-spacing: -0.2px; }
            h3, h4, h5, h6 { font-size: 16px; margin: 20px 0 6px; color: var(--text); }
            header { border-bottom: 1px solid var(--line); padding-bottom: 20px; }
            dl.identity {
              display: grid; grid-template-columns: max-content 1fr;
              gap: 2px 16px; margin: 12px 0 0; font-size: 13px;
            }
            dl.identity dt { color: var(--muted); }
            dl.identity dd { margin: 0; word-break: break-all; font-family: ui-monospace,
                             "SF Mono", Menlo, Consolas, monospace; }
            .cards { display: grid; gap: 10px;
                     grid-template-columns: repeat(auto-fill, minmax(210px, 1fr)); }
            .card { background: var(--surface); border: 1px solid var(--line);
                    border-radius: 14px; padding: 14px; display: grid; gap: 2px; }
            .card .glyph { font-size: 20px; line-height: 1; }
            .card .domain { font-weight: 600; }
            .card .state { font-size: 13px; }
            .card .detail { font-size: 12px; color: var(--muted); }
            .s-supported .glyph, .s-supported .state { color: var(--ok); }
            .s-partial .glyph, .s-partial .state { color: var(--partial); }
            .s-unsupported .glyph, .s-unsupported .state { color: var(--no); }
            .s-not_exposed .glyph, .s-not_exposed .state,
            .s-unknown .glyph, .s-unknown .state { color: var(--none); }
            .legend, .blurb, .subtitle { color: var(--muted); font-size: 13px; }
            .toc ul { columns: 3; column-gap: 24px; list-style: none; padding: 0;
                      font-size: 14px; }
            .toc a { color: var(--text); text-decoration: none;
                     border-bottom: 1px solid var(--line); }
            section.lab { border-top: 1px solid var(--line); margin-top: 28px;
                          padding-top: 4px; }
            .block { margin: 14px 0 0; }
            table { width: 100%; border-collapse: collapse; font-size: 13.5px;
                    margin: 8px 0 0; }
            th, td { text-align: left; vertical-align: top; padding: 7px 10px;
                     border-bottom: 1px solid var(--line); }
            thead th { font-size: 11px; text-transform: uppercase;
                       letter-spacing: 0.08em; color: var(--muted);
                       border-bottom: 1px solid var(--line); }
            tbody th { font-weight: 500; width: 30%; }
            td.value { width: 30%; font-family: ui-monospace, "SF Mono", Menlo,
                       Consolas, monospace; }
            td.prov { color: var(--muted); font-size: 12px; }
            td.prov .note { display: block; margin-top: 3px; color: var(--muted);
                            opacity: 0.85; }
            p.note { background: var(--surface2); border-left: 3px solid var(--line);
                     border-radius: 0 8px 8px 0; padding: 8px 12px; font-size: 13px;
                     color: var(--muted); }
            footer { border-top: 1px solid var(--line); margin-top: 36px;
                     padding-top: 14px; color: var(--muted); font-size: 12.5px; }
            @media (prefers-color-scheme: light) {
              :root { --bg: #ffffff; --surface: #f6f7f9; --surface2: #eef0f4;
                      --text: #14171c; --muted: #5b6472; --line: #dfe3e9;
                      --ok: #15803d; --partial: #a16207; --no: #b91c1c;
                      --none: #6b7280; }
            }
            @media print {
              body { max-width: none; padding: 0; }
              section.lab { break-inside: auto; }
              .block { break-inside: avoid; }
              .toc { display: none; }
            }
        """.trimIndent()
    }
}
