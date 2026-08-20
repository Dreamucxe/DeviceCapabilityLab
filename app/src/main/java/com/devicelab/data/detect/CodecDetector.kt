package com.devicelab.data.detect

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecInfo.VideoCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.devicelab.core.common.Format
import com.devicelab.core.detect.Probe
import com.devicelab.core.model.Absent
import com.devicelab.core.model.Domain
import com.devicelab.core.model.Fact
import com.devicelab.core.model.Lab
import com.devicelab.core.model.LabReport
import com.devicelab.core.model.Section
import javax.inject.Inject

/**
 * Every media codec the platform will admit to having.
 *
 * `MediaCodecList(ALL_CODECS)` is the authoritative source: it is populated from the
 * device's own `media_codecs*.xml` and the Codec2 HAL, so a format appears here only
 * if something on the device can actually handle it. Nothing is inferred from the SoC
 * name or the Android version -- an SoC that supports AV1 in silicon still reports no
 * AV1 codec if the vendor never wired one up, and that is the truth worth showing.
 *
 * Three distinctions the code is careful about, because conflating them is how other
 * tools end up reporting things that are not true:
 *
 *  * **Decode is not encode.** Almost every device decodes HEVC; far fewer encode it.
 *    Each format row states both, separately.
 *  * **Hardware is not software.** `isHardwareAccelerated()` (API 29+) separates a
 *    real hardware block from a software fallback that will drain the battery at 4K.
 *    Below API 29 the platform does not report this, so the row says so rather than
 *    guessing from the codec name.
 *  * **A profile is not a format.** HDR10 support is read from the codec's declared
 *    `CodecProfileLevel` entries, not from the presence of an HEVC decoder.
 */
class CodecDetector @Inject constructor(
    private val probe: Probe,
) : CapabilityDetector {

    override val lab = Lab.CODEC

    /** One (codec, MIME) pair, with the capabilities object if it could be read. */
    private data class Entry(
        val info: MediaCodecInfo,
        val mime: String,
        val caps: CodecCapabilities?,
    ) {
        val isEncoder get() = info.isEncoder
        val hardware: Boolean?
            get() = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else null
    }

    override suspend fun detect(): LabReport {
        val codecs = probe.attempt(emptyList<MediaCodecInfo>()) {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                // Aliases are the same underlying codec under a legacy name; counting
                // them would inflate every total for no information gain.
                .filter { info -> Build.VERSION.SDK_INT < 29 || !info.isAlias }
                .toList()
        }

        val entries = codecs.flatMap { info ->
            probe.attempt(emptyList<Entry>()) {
                info.supportedTypes.map { mime ->
                    Entry(info, mime, probe.attempt(null) { info.getCapabilitiesForType(mime) })
                }
            }
        }
        val byMime: Map<String, List<Entry>> = entries.groupBy { it.mime.lowercase() }

        val notes = mutableListOf<String>()
        if (Build.VERSION.SDK_INT < 29) {
            notes += "This device runs API ${Build.VERSION.SDK_INT}. Hardware acceleration, " +
                "vendor origin and canonical codec names were added in API 29, so those " +
                "rows report that rather than guessing from codec names."
        }
        if (codecs.isEmpty()) {
            notes += "MediaCodecList returned no codecs, which normally indicates an " +
                "emulator or a stripped media stack."
        }

        return LabReport(
            lab = lab,
            sections = listOf(
                overview(codecs, entries),
                formats("video-formats", "Video formats", VIDEO_FORMATS, byMime, Domain.MEDIA),
                formats("audio-formats", "Audio formats", AUDIO_FORMATS, byMime, Domain.AUDIO),
                formats("image-formats", "Image formats", IMAGE_FORMATS, byMime, Domain.MEDIA),
                hdrVideo(byMime),
                protectedPlayback(entries),
                Section(
                    id = "codecs",
                    title = "All codecs",
                    subtitle = "${codecs.size} reported by MediaCodecList(ALL_CODECS)",
                    children = codecs
                        .sortedWith(compareBy({ it.isEncoder }, { it.name }))
                        .mapIndexed { index, info -> codecSection(index, info) },
                ),
            ),
            notes = notes,
        )
    }

    private fun overview(codecs: List<MediaCodecInfo>, entries: List<Entry>) = Section(
        id = "overview",
        title = "Codec overview",
        subtitle = "MediaCodecList(ALL_CODECS)",
        facts = listOf(
            probe.value(
                "Codecs reported",
                "MediaCodecList.getCodecInfos()",
                minApi = 21,
                domain = Domain.MEDIA,
                searchTerms = listOf("codec", "mediacodec"),
            ) { codecs.size.takeIf { it > 0 }?.toString() },
            probe.value("Decoders", "MediaCodecInfo.isEncoder()") {
                codecs.count { !it.isEncoder }.toString()
            },
            probe.value("Encoders", "MediaCodecInfo.isEncoder()") {
                codecs.count { it.isEncoder }.toString()
            },
            probe.value(
                "Hardware-accelerated",
                "MediaCodecInfo.isHardwareAccelerated()",
                minApi = 29,
                searchTerms = listOf("hardware codec", "hw accelerated"),
            ) { codecs.count { it.isHardwareAccelerated }.toString() },
            probe.value(
                "Software-only",
                "MediaCodecInfo.isSoftwareOnly()",
                minApi = 29,
                searchTerms = listOf("software codec", "c2.android"),
            ) { codecs.count { it.isSoftwareOnly }.toString() },
            probe.value(
                "Vendor-supplied",
                "MediaCodecInfo.isVendor()",
                minApi = 29,
            ) { codecs.count { it.isVendor }.toString() },
            probe.value(
                "Distinct formats handled",
                "MediaCodecInfo.getSupportedTypes()",
            ) { entries.map { it.mime.lowercase() }.distinct().size.takeIf { it > 0 }?.toString() },
            probe.value(
                "Media performance class",
                "Build.VERSION.MEDIA_PERFORMANCE_CLASS",
                minApi = 31,
                domain = Domain.MEDIA,
                searchTerms = listOf("performance class", "mpc"),
                detail = "A vendor-declared conformance tier with guaranteed codec, camera " +
                    "and memory floors. 0 or absent means the device makes no such claim.",
            ) {
                Build.VERSION.MEDIA_PERFORMANCE_CLASS
                    .takeIf { it > 0 }
                    ?.let { "Class $it (Android ${performanceClassRelease(it)})" }
            },
        ),
    )

    /**
     * One row per interesting format, each stating decode and encode separately.
     *
     * A format with neither is reported as unsupported *after* having been queried --
     * which is a different statement from "this Android version cannot tell us", and
     * the provenance carried by the fact keeps the two apart in the capability matrix.
     */
    private fun formats(
        id: String,
        title: String,
        formats: List<FormatSpec>,
        byMime: Map<String, List<Entry>>,
        domain: Domain,
    ) = Section(
        id = id,
        title = title,
        subtitle = "MediaCodecList — decode and encode reported separately",
        facts = formats.map { spec ->
            val matches = spec.mimes.flatMap { byMime[it.lowercase()].orEmpty() }
            formatFact(spec, matches, domain)
        },
    )

    private fun formatFact(spec: FormatSpec, matches: List<Entry>, domain: Domain): Fact =
        probe.verdict(
            label = spec.label,
            api = "MediaCodecList / " + spec.mimes.joinToString(", "),
            minApi = 21,
            domain = domain,
            searchTerms = spec.searchTerms + spec.mimes,
        ) {
            if (matches.isEmpty()) {
                return@verdict Probe.Verdict.no(
                    "Not supported",
                    "No codec on this device declares ${spec.mimes.joinToString(" or ")}.",
                )
            }
            val decoders = matches.filter { !it.isEncoder }
            val encoders = matches.filter { it.isEncoder }
            val text = buildString {
                append(
                    when {
                        decoders.isNotEmpty() && encoders.isNotEmpty() -> "Decode & encode"
                        decoders.isNotEmpty() -> "Decode only"
                        else -> "Encode only"
                    },
                )
                accelerationSuffix(decoders, encoders)?.let { append(" · ").append(it) }
            }
            val detail = buildString {
                if (decoders.isNotEmpty()) {
                    append("Decoders: ")
                    append(decoders.joinToString(", ") { codecLabel(it) })
                }
                if (encoders.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append("Encoders: ")
                    append(encoders.joinToString(", ") { codecLabel(it) })
                }
                largestVideoSize(decoders)?.let {
                    append('\n').append("Largest decode size: ").append(it)
                }
                largestVideoSize(encoders)?.let {
                    append('\n').append("Largest encode size: ").append(it)
                }
            }
            if (decoders.isNotEmpty() && encoders.isNotEmpty()) {
                Probe.Verdict.yes(text, detail)
            } else {
                // Half a pipeline is real support for one direction and none for the
                // other, which is exactly what PARTIAL is for.
                Probe.Verdict.partial(text, detail)
            }
        }

    private fun accelerationSuffix(decoders: List<Entry>, encoders: List<Entry>): String? {
        if (Build.VERSION.SDK_INT < 29) return "hardware acceleration not reported below API 29"
        val hwDecode = decoders.any { it.hardware == true }
        val hwEncode = encoders.any { it.hardware == true }
        return when {
            decoders.isNotEmpty() && encoders.isNotEmpty() -> when {
                hwDecode && hwEncode -> "hardware both ways"
                hwDecode -> "hardware decode, software encode"
                hwEncode -> "software decode, hardware encode"
                else -> "software only"
            }
            decoders.isNotEmpty() -> if (hwDecode) "hardware" else "software only"
            encoders.isNotEmpty() -> if (hwEncode) "hardware" else "software only"
            else -> null
        }
    }

    private fun codecLabel(entry: Entry): String {
        val suffix = when (entry.hardware) {
            true -> " (hw)"
            false -> " (sw)"
            null -> ""
        }
        return entry.info.name + suffix
    }

    private fun largestVideoSize(entries: List<Entry>): String? {
        var best: Pair<Int, Int>? = null
        entries.forEach { entry ->
            val video = probe.attempt<VideoCapabilities?>(null) { entry.caps?.videoCapabilities }
                ?: return@forEach
            val w = probe.attempt<Int?>(null) { video.supportedWidths.upper } ?: return@forEach
            val h = probe.attempt<Int?>(null) { video.supportedHeights.upper } ?: return@forEach
            if (best == null || w.toLong() * h > best!!.first.toLong() * best!!.second) {
                best = w to h
            }
        }
        return best?.let { "${Format.resolution(it.first, it.second)}" }
    }

    /**
     * HDR video decode, read from declared profiles.
     *
     * The HDR flavour a device can *display* lives in the display lab; this is the
     * separate question of what its decoders will accept. A device can decode HDR10 to
     * an SDR panel (and will tone-map), and it can have an HDR panel with no HDR10+
     * decoder, so neither answer implies the other.
     */
    private fun hdrVideo(byMime: Map<String, List<Entry>>): Section {
        val hevc = byMime[MediaFormat.MIMETYPE_VIDEO_HEVC.lowercase()].orEmpty()
        val vp9 = byMime[MediaFormat.MIMETYPE_VIDEO_VP9.lowercase()].orEmpty()
        val av1 = byMime[MediaFormat.MIMETYPE_VIDEO_AV1.lowercase()].orEmpty()
        val dolby = byMime[MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION.lowercase()].orEmpty()

        fun decodesProfile(entries: List<Entry>, vararg profiles: Int): Pair<Boolean, List<String>> {
            val hits = mutableListOf<String>()
            entries.filter { !it.isEncoder }.forEach { entry ->
                val levels = probe.attempt<Array<CodecProfileLevel>>(emptyArray()) {
                    entry.caps?.profileLevels ?: emptyArray()
                }
                if (levels.any { it.profile in profiles.toList() }) hits += entry.info.name
            }
            return (hits.isNotEmpty()) to hits
        }

        return Section(
            id = "hdr",
            title = "HDR video decode",
            subtitle = "CodecCapabilities.profileLevels",
            facts = listOf(
                probe.verdict(
                    "HDR10 decode",
                    "CodecProfileLevel (HEVC/VP9/AV1 HDR10 profiles)",
                    minApi = 24,
                    domain = Domain.MEDIA,
                    searchTerms = listOf("hdr10", "hdr", "10-bit"),
                ) {
                    val (ok, who) = decodesProfile(
                        hevc + vp9 + av1,
                        CodecProfileLevel.HEVCProfileMain10HDR10,
                        CodecProfileLevel.VP9Profile2HDR,
                        CodecProfileLevel.VP9Profile3HDR,
                        CodecProfileLevel.AV1ProfileMain10HDR10,
                    )
                    if (ok) {
                        Probe.Verdict.yes("Supported", "Declared by ${who.joinToString(", ")}")
                    } else {
                        Probe.Verdict.no(
                            "Not declared",
                            "No decoder declares an HDR10 profile.",
                        )
                    }
                },
                probe.verdict(
                    "HDR10+ decode",
                    "CodecProfileLevel (HDR10Plus profiles)",
                    minApi = 29,
                    domain = Domain.MEDIA,
                    searchTerms = listOf("hdr10+", "hdr10 plus", "dynamic metadata"),
                ) {
                    val (ok, who) = decodesProfile(
                        hevc + vp9 + av1,
                        CodecProfileLevel.HEVCProfileMain10HDR10Plus,
                        CodecProfileLevel.VP9Profile2HDR10Plus,
                        CodecProfileLevel.VP9Profile3HDR10Plus,
                        CodecProfileLevel.AV1ProfileMain10HDR10Plus,
                    )
                    if (ok) {
                        Probe.Verdict.yes("Supported", "Declared by ${who.joinToString(", ")}")
                    } else {
                        Probe.Verdict.no("Not declared")
                    }
                },
                probe.verdict(
                    "10-bit decode (HLG-capable)",
                    "CodecProfileLevel (Main10 / Profile2 profiles)",
                    minApi = 21,
                    domain = Domain.MEDIA,
                    searchTerms = listOf("hlg", "10-bit", "main10"),
                    detail = "HLG carries no per-title metadata, so a codec advertises no " +
                        "HLG profile: what matters is whether it decodes 10-bit at all. " +
                        "Whether the panel then displays HLG is in the display lab.",
                ) {
                    val (ok, who) = decodesProfile(
                        hevc + vp9 + av1,
                        CodecProfileLevel.HEVCProfileMain10,
                        CodecProfileLevel.HEVCProfileMain10HDR10,
                        CodecProfileLevel.HEVCProfileMain10HDR10Plus,
                        CodecProfileLevel.VP9Profile2,
                        CodecProfileLevel.VP9Profile3,
                        CodecProfileLevel.AV1ProfileMain10,
                    )
                    if (ok) {
                        Probe.Verdict.yes("Supported", "Declared by ${who.joinToString(", ")}")
                    } else {
                        Probe.Verdict.no("Not declared")
                    }
                },
                probe.verdict(
                    "Dolby Vision decode",
                    "MIMETYPE_VIDEO_DOLBY_VISION",
                    minApi = 24,
                    domain = Domain.MEDIA,
                    searchTerms = listOf("dolby vision", "dv", "profile 8.4"),
                ) {
                    val decoders = dolby.filter { !it.isEncoder }
                    if (decoders.isEmpty()) {
                        Probe.Verdict.no(
                            "Not supported",
                            "No codec declares video/dolby-vision.",
                        )
                    } else {
                        val profiles = decoders.flatMap { entry ->
                            probe.attempt(emptyList<String>()) {
                                entry.caps?.profileLevels
                                    ?.mapNotNull {
                                        ProfileNames.profile(
                                            MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION,
                                            it.profile,
                                        )
                                    }
                                    ?.distinct()
                                    .orEmpty()
                            }
                        }.distinct()
                        Probe.Verdict.yes(
                            "Supported",
                            buildString {
                                append("Decoders: ")
                                append(decoders.joinToString(", ") { codecLabel(it) })
                                if (profiles.isNotEmpty()) {
                                    append('\n').append("Profiles: ")
                                    append(profiles.joinToString(", "))
                                }
                            },
                        )
                    }
                },
                probe.verdict(
                    "HDR editing",
                    "CodecCapabilities.FEATURE_HdrEditing",
                    minApi = 33,
                    searchTerms = listOf("hdr editing", "hdr encode"),
                ) {
                    val encoders = (hevc + av1).filter { it.isEncoder }.filter { entry ->
                        probe.attempt(false) {
                            entry.caps?.isFeatureSupported(CodecCapabilities.FEATURE_HdrEditing)
                                ?: false
                        }
                    }
                    if (encoders.isEmpty()) {
                        Probe.Verdict.no("Not supported")
                    } else {
                        Probe.Verdict.yes(
                            "Supported",
                            "Encoders: " + encoders.joinToString(", ") { codecLabel(it) },
                        )
                    }
                },
            ),
        )
    }

    /**
     * Secure and tunneled decode.
     *
     * These are the codec half of protected playback: without a decoder that declares
     * `FEATURE_SecurePlayback`, a Widevine L1 security level cannot be used for video,
     * however capable the DRM stack claims to be. The DRM lab reports the other half.
     */
    private fun protectedPlayback(entries: List<Entry>): Section {
        fun withFeature(feature: String) = entries.filter { entry ->
            probe.attempt(false) { entry.caps?.isFeatureSupported(feature) ?: false }
        }
        val secure = withFeature(CodecCapabilities.FEATURE_SecurePlayback)
        val tunneled = withFeature(CodecCapabilities.FEATURE_TunneledPlayback)
        val lowLatency = if (Build.VERSION.SDK_INT >= 30) {
            withFeature(CodecCapabilities.FEATURE_LowLatency)
        } else {
            emptyList()
        }

        return Section(
            id = "protected",
            title = "Protected & specialised decode",
            subtitle = "CodecCapabilities.isFeatureSupported()",
            facts = listOf(
                probe.verdict(
                    "Secure decode",
                    "CodecCapabilities.FEATURE_SecurePlayback",
                    minApi = 21,
                    domain = Domain.MEDIA,
                    searchTerms = listOf("secure playback", "widevine l1", "drm", "netflix hd"),
                    detail = "A decoder that can work on encrypted buffers inside the " +
                        "TrustZone. Required for Widevine L1 video.",
                ) {
                    if (secure.isEmpty()) {
                        Probe.Verdict.no("No codec declares secure playback")
                    } else {
                        Probe.Verdict.yes(
                            "${secure.size} codec${if (secure.size == 1) "" else "s"}",
                            secure.take(8).joinToString(", ") { "${it.info.name} (${it.mime})" },
                        )
                    }
                },
                probe.verdict(
                    "Tunneled decode",
                    "CodecCapabilities.FEATURE_TunneledPlayback",
                    minApi = 21,
                    searchTerms = listOf("tunneled", "tunnel mode", "android tv"),
                    detail = "Decoding straight to the display pipeline without the frames " +
                        "passing through app memory. Common on TV devices.",
                ) {
                    if (tunneled.isEmpty()) {
                        Probe.Verdict.no("Not supported")
                    } else {
                        Probe.Verdict.yes(
                            "${tunneled.size} codec${if (tunneled.size == 1) "" else "s"}",
                            tunneled.take(8).joinToString(", ") { it.info.name },
                        )
                    }
                },
                probe.verdict(
                    "Low-latency decode",
                    "CodecCapabilities.FEATURE_LowLatency",
                    minApi = 30,
                    searchTerms = listOf("low latency", "cloud gaming", "streaming"),
                ) {
                    if (lowLatency.isEmpty()) {
                        Probe.Verdict.no("Not supported")
                    } else {
                        Probe.Verdict.yes(
                            "${lowLatency.size} codec${if (lowLatency.size == 1) "" else "s"}",
                            lowLatency.take(8).joinToString(", ") { it.info.name },
                        )
                    }
                },
                probe.verdict(
                    "Adaptive playback",
                    "CodecCapabilities.FEATURE_AdaptivePlayback",
                    minApi = 21,
                    searchTerms = listOf("adaptive", "abr", "resolution switch"),
                    detail = "Resolution changes mid-stream without reconfiguring the " +
                        "decoder, which is what adaptive streaming needs.",
                ) {
                    val adaptive = withFeature(CodecCapabilities.FEATURE_AdaptivePlayback)
                    if (adaptive.isEmpty()) {
                        Probe.Verdict.no("Not supported")
                    } else {
                        Probe.Verdict.yes("${adaptive.size} codecs")
                    }
                },
            ),
        )
    }

    private fun codecSection(index: Int, info: MediaCodecInfo): Section {
        val types = probe.attempt(emptyArray<String>()) { info.supportedTypes }
        return Section(
            id = "codec-$index",
            title = probe.attempt("Codec $index") { info.name },
            subtitle = buildString {
                append(if (probe.attempt(false) { info.isEncoder }) "Encoder" else "Decoder")
                if (Build.VERSION.SDK_INT >= 29) {
                    probe.attempt<Boolean?>(null) { info.isHardwareAccelerated }?.let {
                        append(if (it) " · hardware" else " · software")
                    }
                }
                if (types.isNotEmpty()) append(" · ").append(types.joinToString(", "))
            },
            facts = listOf(
                probe.value("Name", "MediaCodecInfo.getName()") { info.name },
                probe.value(
                    "Canonical name",
                    "MediaCodecInfo.getCanonicalName()",
                    minApi = 29,
                    detail = "The underlying codec's real name, which differs from the " +
                        "reported name when the entry is a compatibility alias.",
                ) { info.canonicalName },
                probe.flag("Encoder", "MediaCodecInfo.isEncoder()", supportedText = "Yes", unsupportedText = "No — decoder") {
                    info.isEncoder
                },
                probe.flag(
                    "Hardware-accelerated",
                    "MediaCodecInfo.isHardwareAccelerated()",
                    minApi = 29,
                    searchTerms = listOf("hardware", "hw"),
                ) { info.isHardwareAccelerated },
                probe.flag(
                    "Software-only",
                    "MediaCodecInfo.isSoftwareOnly()",
                    minApi = 29,
                    supportedText = "Yes",
                    unsupportedText = "No",
                ) { info.isSoftwareOnly },
                probe.flag(
                    "Vendor-supplied",
                    "MediaCodecInfo.isVendor()",
                    minApi = 29,
                    supportedText = "Yes — from the SoC or OEM",
                    unsupportedText = "No — part of the Android platform",
                ) { info.isVendor },
                probe.flag(
                    "Compatibility alias",
                    "MediaCodecInfo.isAlias()",
                    minApi = 29,
                    supportedText = "Yes",
                    unsupportedText = "No",
                ) { info.isAlias },
                probe.value("Supported types", "MediaCodecInfo.getSupportedTypes()") {
                    types.joinToString(", ").ifBlank { null }
                },
            ),
            children = types.map { mime ->
                typeSection(index, info, mime)
            },
        )
    }

    private fun typeSection(codecIndex: Int, info: MediaCodecInfo, mime: String): Section {
        val caps = probe.attempt<CodecCapabilities?>(null) { info.getCapabilitiesForType(mime) }
        val video = probe.attempt<VideoCapabilities?>(null) { caps?.videoCapabilities }
        val audio = probe.attempt<MediaCodecInfo.AudioCapabilities?>(null) {
            caps?.audioCapabilities
        }
        val encoderCaps = probe.attempt<MediaCodecInfo.EncoderCapabilities?>(null) {
            caps?.encoderCapabilities
        }

        return Section(
            id = "codec-$codecIndex-${mime.replace('/', '-')}",
            title = mime,
            facts = buildList {
                add(
                    probe.value(
                        "Maximum instances",
                        "CodecCapabilities.getMaxSupportedInstances()",
                        minApi = 23,
                        detail = "How many of this codec can run at once, which bounds " +
                            "multi-stream playback and editing.",
                    ) { caps?.maxSupportedInstances?.takeIf { it > 0 }?.toString() },
                )
                if (video != null) {
                    addAll(videoFacts(video, caps))
                }
                if (audio != null) {
                    addAll(audioFacts(audio))
                }
                if (encoderCaps != null) {
                    addAll(encoderFacts(encoderCaps))
                }
                add(
                    probe.value(
                        "Profiles & levels",
                        "CodecCapabilities.profileLevels",
                        absentText = Absent.NOT_EXPOSED,
                        searchTerms = listOf("profile", "level"),
                    ) {
                        val levels = caps?.profileLevels ?: return@value null
                        if (levels.isEmpty()) return@value null
                        levels
                            .map { pl ->
                                val p = ProfileNames.profile(mime, pl.profile)
                                    ?: "profile ${pl.profile}"
                                val l = ProfileNames.level(mime, pl.level)
                                    ?: "level ${pl.level}"
                                "$p / $l"
                            }
                            .distinct()
                            .sorted()
                            .joinToString(", ")
                    },
                )
                add(
                    probe.value(
                        "Colour formats",
                        "CodecCapabilities.colorFormats",
                        absentText = Absent.NOT_EXPOSED,
                        searchTerms = listOf("colour format", "color format", "yuv", "p010"),
                    ) {
                        val formats = caps?.colorFormats ?: return@value null
                        if (formats.isEmpty()) return@value null
                        formats.map { colorFormatName(it) }.distinct().joinToString(", ")
                    },
                )
                add(
                    probe.value(
                        "Features",
                        "CodecCapabilities.isFeatureSupported()",
                        absentText = Absent.NONE,
                        searchTerms = listOf("feature"),
                    ) {
                        val supported = FEATURES
                            .filter { (feature, minApi) ->
                                Build.VERSION.SDK_INT >= minApi &&
                                    probe.attempt(false) {
                                        caps?.isFeatureSupported(feature) ?: false
                                    }
                            }
                            .map { it.first }
                        supported.joinToString(", ").ifBlank { null }
                    },
                )
            },
        )
    }

    private fun videoFacts(video: VideoCapabilities, caps: CodecCapabilities?): List<Fact> = listOf(
        probe.value(
            "Resolution range",
            "VideoCapabilities.getSupportedWidths/Heights()",
            searchTerms = listOf("resolution", "4k", "8k", "1080p"),
        ) {
            val w = video.supportedWidths
            val h = video.supportedHeights
            "${w.lower}×${h.lower} to ${w.upper}×${h.upper}"
        },
        probe.value(
            "Alignment",
            "VideoCapabilities.getWidthAlignment/getHeightAlignment()",
        ) { "${video.widthAlignment} × ${video.heightAlignment} pixels" },
        probe.value(
            "Frame rate range",
            "VideoCapabilities.getSupportedFrameRates()",
            searchTerms = listOf("fps", "frame rate"),
        ) { "${video.supportedFrameRates.lower}–${video.supportedFrameRates.upper} fps" },
        probe.value(
            "Bitrate range",
            "VideoCapabilities.getBitrateRange()",
            searchTerms = listOf("bitrate", "mbps"),
        ) {
            val range = video.bitrateRange
            "${Format.bitrate(range.lower.toLong())} – ${Format.bitrate(range.upper.toLong())}"
        },
        probe.value(
            "Peak rate at 1080p",
            "VideoCapabilities.getSupportedFrameRatesFor(1920, 1080)",
            searchTerms = listOf("1080p", "fps"),
        ) {
            probe.attempt<String?>(null) {
                if (!video.isSizeSupported(1920, 1080)) return@attempt "Not supported"
                val range = video.getSupportedFrameRatesFor(1920, 1080)
                "${Format.decimal(range.upper.toFloat(), 0)} fps"
            }
        },
        probe.value(
            "Peak rate at 4K",
            "VideoCapabilities.getSupportedFrameRatesFor(3840, 2160)",
            searchTerms = listOf("4k", "uhd", "fps"),
        ) {
            probe.attempt<String?>(null) {
                if (!video.isSizeSupported(3840, 2160)) return@attempt "Not supported"
                val range = video.getSupportedFrameRatesFor(3840, 2160)
                "${Format.decimal(range.upper.toFloat(), 0)} fps"
            }
        },
        probe.value(
            "Performance points",
            "VideoCapabilities.getSupportedPerformancePoints()",
            minApi = 29,
            absentText = Absent.NOT_EXPOSED,
            searchTerms = listOf("performance point", "4k60", "1080p120"),
            detail = "Vendor-declared size/rate combinations this codec sustains. Codecs " +
                "that publish none leave this blank, which is the codec's silence and " +
                "not a claim either way.",
        ) { PerformancePointReader.summarise(video) },
    ) + if (caps != null && Build.VERSION.SDK_INT >= 33) {
        listOf(
            probe.value(
                "10-bit output format",
                "COLOR_FormatYUVP010",
                minApi = 33,
                absentText = "Not offered",
                searchTerms = listOf("p010", "10-bit"),
            ) {
                val formats = caps.colorFormats ?: return@value null
                if (formats.contains(CodecCapabilities.COLOR_FormatYUVP010)) {
                    "COLOR_FormatYUVP010 available"
                } else {
                    null
                }
            },
        )
    } else {
        emptyList()
    }

    private fun audioFacts(audio: MediaCodecInfo.AudioCapabilities): List<Fact> = listOf(
        probe.value(
            "Sample rates",
            "AudioCapabilities.getSupportedSampleRates()",
            searchTerms = listOf("sample rate", "48khz", "96khz", "192khz"),
        ) {
            val discrete = probe.attempt<IntArray?>(null) { audio.supportedSampleRates }
            if (discrete != null && discrete.isNotEmpty()) {
                discrete.sorted().joinToString(", ") { Format.kilohertz(it) }
            } else {
                probe.attempt<String?>(null) {
                    audio.supportedSampleRateRanges.joinToString(", ") { range ->
                        if (range.lower == range.upper) {
                            Format.kilohertz(range.lower)
                        } else {
                            "${Format.kilohertz(range.lower)}–${Format.kilohertz(range.upper)}"
                        }
                    }
                }
            }
        },
        probe.value(
            "Channel count",
            "AudioCapabilities.getMaxInputChannelCount()",
            searchTerms = listOf("channels", "stereo", "surround", "5.1", "7.1"),
        ) {
            val max = audio.maxInputChannelCount
            if (Build.VERSION.SDK_INT >= 31) {
                val min = probe.attempt(1) { audio.minInputChannelCount }
                if (min != max) "$min – $max" else "$max"
            } else {
                "up to $max"
            }
        },
        probe.value("Bitrate range", "AudioCapabilities.getBitrateRange()") {
            val range = audio.bitrateRange
            "${Format.bitrate(range.lower.toLong())} – ${Format.bitrate(range.upper.toLong())}"
        },
    )

    private fun encoderFacts(caps: MediaCodecInfo.EncoderCapabilities): List<Fact> = listOf(
        probe.value(
            "Bitrate modes",
            "EncoderCapabilities.isBitrateModeSupported()",
            searchTerms = listOf("cbr", "vbr", "cq", "bitrate mode"),
        ) {
            listOf(
                "CQ" to MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ,
                "VBR" to MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                "CBR" to MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
            ).filter { probe.attempt(false) { caps.isBitrateModeSupported(it.second) } }
                .joinToString(", ") { it.first }
                .ifBlank { null }
        },
        probe.value("Quality range", "EncoderCapabilities.getQualityRange()", minApi = 28) {
            val range = caps.qualityRange
            if (range.lower == range.upper) null else "${range.lower}–${range.upper}"
        },
        probe.value("Complexity range", "EncoderCapabilities.getComplexityRange()") {
            val range = caps.complexityRange
            if (range.lower == range.upper) null else "${range.lower}–${range.upper}"
        },
    )

    private fun performanceClassRelease(clazz: Int): String = when (clazz) {
        31 -> "12"
        32 -> "12L"
        33 -> "13"
        34 -> "14"
        35 -> "15"
        else -> "API $clazz"
    }

    private fun colorFormatName(value: Int): String = COLOR_FORMATS[value] ?: "format $value"

    /**
     * Codec profile and level names, read off the SDK class by reflection.
     *
     * `CodecProfileLevel` declares roughly 200 int constants and the platform gives no
     * value-to-name mapping, so the alternative is a hand-copied table that silently
     * rots. Reflection over the app's own compiled-against public class is exact, and
     * the values are disambiguated by the per-format name prefix because they collide
     * across formats -- `HEVCProfileMain10` and `AV1ProfileMain10` are both 2.
     */
    private object ProfileNames {

        private val fields: List<Pair<String, Int>> by lazy {
            runCatching {
                CodecProfileLevel::class.java.fields
                    .filter { it.type == Int::class.javaPrimitiveType }
                    .mapNotNull { field ->
                        runCatching { field.name to (field.get(null) as Int) }.getOrNull()
                    }
            }.getOrDefault(emptyList())
        }

        fun profile(mime: String, value: Int): String? = lookup(mime, value, level = false)

        fun level(mime: String, value: Int): String? = lookup(mime, value, level = true)

        private fun lookup(mime: String, value: Int, level: Boolean): String? {
            val prefix = prefixFor(mime) ?: return null
            return fields.firstOrNull { (name, v) ->
                v == value &&
                    name.startsWith(prefix) &&
                    name.contains("Level") == level &&
                    // AAC has profiles ("AACObject*") but no level constants at all.
                    (!level || prefix != "AACObject")
            }?.first
        }

        private fun prefixFor(mime: String): String? = when (mime.lowercase()) {
            MediaFormat.MIMETYPE_VIDEO_AVC -> "AVC"
            MediaFormat.MIMETYPE_VIDEO_HEVC -> "HEVC"
            MediaFormat.MIMETYPE_VIDEO_VP8 -> "VP8"
            MediaFormat.MIMETYPE_VIDEO_VP9 -> "VP9"
            MediaFormat.MIMETYPE_VIDEO_AV1 -> "AV1"
            MediaFormat.MIMETYPE_VIDEO_MPEG4 -> "MPEG4"
            MediaFormat.MIMETYPE_VIDEO_MPEG2 -> "MPEG2"
            MediaFormat.MIMETYPE_VIDEO_H263 -> "H263"
            MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION -> "DolbyVision"
            MediaFormat.MIMETYPE_AUDIO_AAC,
            MediaFormat.MIMETYPE_AUDIO_AAC_LC,
            MediaFormat.MIMETYPE_AUDIO_AAC_HE_V1,
            MediaFormat.MIMETYPE_AUDIO_AAC_HE_V2,
            MediaFormat.MIMETYPE_AUDIO_AAC_ELD,
            MediaFormat.MIMETYPE_AUDIO_AAC_XHE,
            -> "AACObject"
            else -> null
        }
    }

    /**
     * Isolated so [VideoCapabilities.PerformancePoint] is only referenced from a class
     * that never loads below API 29, keeping [CodecDetector] itself verifiable on the
     * older releases this app still supports.
     */
    private object PerformancePointReader {

        fun summarise(video: VideoCapabilities): String? {
            if (Build.VERSION.SDK_INT < 29) return null
            val points = runCatching { video.supportedPerformancePoints }.getOrNull()
                ?: return null
            if (points.isEmpty()) return null
            val targets = listOf(
                "8K60" to VideoCapabilities.PerformancePoint(7680, 4320, 60),
                "8K30" to VideoCapabilities.PerformancePoint(7680, 4320, 30),
                "4K120" to VideoCapabilities.PerformancePoint(3840, 2160, 120),
                "4K60" to VideoCapabilities.PerformancePoint(3840, 2160, 60),
                "4K30" to VideoCapabilities.PerformancePoint(3840, 2160, 30),
                "1080p240" to VideoCapabilities.PerformancePoint(1920, 1080, 240),
                "1080p120" to VideoCapabilities.PerformancePoint(1920, 1080, 120),
                "1080p60" to VideoCapabilities.PerformancePoint(1920, 1080, 60),
                "1080p30" to VideoCapabilities.PerformancePoint(1920, 1080, 30),
                "720p60" to VideoCapabilities.PerformancePoint(1280, 720, 60),
            )
            val met = targets.filter { (_, target) ->
                points.any { runCatching { it.covers(target) }.getOrDefault(false) }
            }.map { it.first }
            return if (met.isEmpty()) {
                "${points.size} declared, none covering 720p60 or above"
            } else {
                met.joinToString(", ")
            }
        }
    }

    /** A user-facing format name and the MIME types that would satisfy it. */
    private data class FormatSpec(
        val label: String,
        val mimes: List<String>,
        val searchTerms: List<String> = emptyList(),
    )

    private companion object {

        val VIDEO_FORMATS = listOf(
            FormatSpec(
                "H.264 / AVC",
                listOf(MediaFormat.MIMETYPE_VIDEO_AVC),
                listOf("h264", "avc", "mp4"),
            ),
            FormatSpec(
                "H.265 / HEVC",
                listOf(MediaFormat.MIMETYPE_VIDEO_HEVC),
                listOf("h265", "hevc", "x265"),
            ),
            FormatSpec("AV1", listOf(MediaFormat.MIMETYPE_VIDEO_AV1), listOf("av1", "av01")),
            FormatSpec("VP9", listOf(MediaFormat.MIMETYPE_VIDEO_VP9), listOf("vp9", "webm")),
            FormatSpec("VP8", listOf(MediaFormat.MIMETYPE_VIDEO_VP8), listOf("vp8")),
            FormatSpec(
                "Dolby Vision",
                listOf(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION),
                listOf("dolby vision", "dv"),
            ),
            FormatSpec("MPEG-4 Part 2", listOf(MediaFormat.MIMETYPE_VIDEO_MPEG4), listOf("mpeg4")),
            FormatSpec("MPEG-2", listOf(MediaFormat.MIMETYPE_VIDEO_MPEG2), listOf("mpeg2")),
            FormatSpec("H.263", listOf(MediaFormat.MIMETYPE_VIDEO_H263), listOf("h263")),
        )

        val AUDIO_FORMATS = listOf(
            FormatSpec(
                "AAC",
                listOf(
                    MediaFormat.MIMETYPE_AUDIO_AAC,
                    MediaFormat.MIMETYPE_AUDIO_AAC_LC,
                ),
                listOf("aac", "m4a"),
            ),
            FormatSpec(
                "xHE-AAC",
                listOf(MediaFormat.MIMETYPE_AUDIO_AAC_XHE),
                listOf("xhe-aac", "usac", "loudness"),
            ),
            FormatSpec("MP3", listOf(MediaFormat.MIMETYPE_AUDIO_MPEG), listOf("mp3", "mpeg audio")),
            FormatSpec("Opus", listOf(MediaFormat.MIMETYPE_AUDIO_OPUS), listOf("opus")),
            FormatSpec("Vorbis", listOf(MediaFormat.MIMETYPE_AUDIO_VORBIS), listOf("vorbis", "ogg")),
            FormatSpec(
                "FLAC",
                listOf(MediaFormat.MIMETYPE_AUDIO_FLAC),
                listOf("flac", "lossless", "hi-res"),
            ),
            FormatSpec(
                "Dolby Digital (AC-3)",
                listOf(MediaFormat.MIMETYPE_AUDIO_AC3),
                listOf("ac3", "dolby digital"),
            ),
            FormatSpec(
                "Dolby Digital Plus (E-AC-3)",
                listOf(
                    MediaFormat.MIMETYPE_AUDIO_EAC3,
                    MediaFormat.MIMETYPE_AUDIO_EAC3_JOC,
                ),
                listOf("eac3", "dd+", "atmos"),
            ),
            FormatSpec(
                "Dolby AC-4",
                listOf(MediaFormat.MIMETYPE_AUDIO_AC4),
                listOf("ac4", "dolby"),
            ),
            FormatSpec(
                "Dolby TrueHD",
                listOf(MediaFormat.MIMETYPE_AUDIO_DOLBY_TRUEHD),
                listOf("truehd"),
            ),
            FormatSpec(
                "DTS",
                listOf(
                    MediaFormat.MIMETYPE_AUDIO_DTS,
                    MediaFormat.MIMETYPE_AUDIO_DTS_HD,
                    MediaFormat.MIMETYPE_AUDIO_DTS_UHD,
                ),
                listOf("dts", "dts:x"),
            ),
            FormatSpec(
                "MPEG-H 3D Audio",
                listOf(
                    MediaFormat.MIMETYPE_AUDIO_MPEGH_MHA1,
                    MediaFormat.MIMETYPE_AUDIO_MPEGH_MHM1,
                    MediaFormat.MIMETYPE_AUDIO_MPEGH_BL_L3,
                    MediaFormat.MIMETYPE_AUDIO_MPEGH_BL_L4,
                ),
                listOf("mpeg-h", "3d audio"),
            ),
            FormatSpec(
                "AMR narrowband",
                listOf(MediaFormat.MIMETYPE_AUDIO_AMR_NB),
                listOf("amr", "voice"),
            ),
            FormatSpec("AMR wideband", listOf(MediaFormat.MIMETYPE_AUDIO_AMR_WB), listOf("amr-wb")),
            FormatSpec(
                "PCM / raw",
                listOf(MediaFormat.MIMETYPE_AUDIO_RAW),
                listOf("pcm", "raw audio"),
            ),
        )

        val IMAGE_FORMATS = listOf(
            FormatSpec(
                "HEIF / HEIC image",
                listOf(MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC),
                listOf("heic", "heif"),
            ),
            FormatSpec(
                "AVIF image",
                listOf(MediaFormat.MIMETYPE_IMAGE_AVIF),
                listOf("avif"),
            ),
        )

        /**
         * Codec feature name → the API level that introduced it.
         *
         * Levels taken from the SDK's own api-versions.xml, so a feature that this
         * release cannot report is shown as an API limit rather than as a missing
         * capability.
         */
        val FEATURES = listOf(
            CodecCapabilities.FEATURE_AdaptivePlayback to 21,
            CodecCapabilities.FEATURE_SecurePlayback to 21,
            CodecCapabilities.FEATURE_TunneledPlayback to 21,
            CodecCapabilities.FEATURE_IntraRefresh to 24,
            CodecCapabilities.FEATURE_PartialFrame to 26,
            CodecCapabilities.FEATURE_FrameParsing to 29,
            CodecCapabilities.FEATURE_MultipleFrames to 29,
            CodecCapabilities.FEATURE_DynamicTimestamp to 29,
            CodecCapabilities.FEATURE_LowLatency to 30,
            CodecCapabilities.FEATURE_QpBounds to 31,
            CodecCapabilities.FEATURE_EncodingStatistics to 33,
            CodecCapabilities.FEATURE_HdrEditing to 33,
        )

        /**
         * The colour formats a codec realistically reports.
         *
         * Spelled out rather than reflected because `CodecCapabilities` mixes these
         * constants with dozens of unrelated ints, and several vendor formats share
         * values with the OMX private range where a reflected name would mislead.
         */
        val COLOR_FORMATS: Map<Int, String> = mapOf(
            CodecCapabilities.COLOR_FormatSurface to "Surface",
            CodecCapabilities.COLOR_FormatYUV420Flexible to "YUV420Flexible",
            CodecCapabilities.COLOR_FormatYUV420Planar to "YUV420Planar",
            CodecCapabilities.COLOR_FormatYUV420SemiPlanar to "YUV420SemiPlanar",
            CodecCapabilities.COLOR_FormatYUV420PackedPlanar to "YUV420PackedPlanar",
            CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar to "YUV420PackedSemiPlanar",
            CodecCapabilities.COLOR_FormatYUV422Flexible to "YUV422Flexible",
            CodecCapabilities.COLOR_FormatYUV444Flexible to "YUV444Flexible",
            CodecCapabilities.COLOR_FormatYUVP010 to "YUVP010 (10-bit)",
            CodecCapabilities.COLOR_Format32bitABGR2101010 to "ABGR2101010 (10-bit)",
            CodecCapabilities.COLOR_Format32bitABGR8888 to "ABGR8888",
            CodecCapabilities.COLOR_Format32bitARGB8888 to "ARGB8888",
            CodecCapabilities.COLOR_Format24bitRGB888 to "RGB888",
            CodecCapabilities.COLOR_FormatRGBAFlexible to "RGBAFlexible",
            CodecCapabilities.COLOR_FormatRGBFlexible to "RGBFlexible",
            CodecCapabilities.COLOR_Format16bitRGB565 to "RGB565",
            CodecCapabilities.COLOR_Format64bitABGRFloat to "ABGRFloat (HDR)",
            CodecCapabilities.COLOR_FormatMonochrome to "Monochrome",
            CodecCapabilities.COLOR_QCOM_FormatYUV420SemiPlanar to "QCOM YUV420SemiPlanar",
            CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar to "TI YUV420PackedSemiPlanar",
        )
    }
}
