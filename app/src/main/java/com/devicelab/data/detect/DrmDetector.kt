package com.devicelab.data.detect

import android.media.MediaDrm
import android.os.Build
import com.devicelab.core.detect.Probe
import com.devicelab.core.model.Domain
import com.devicelab.core.model.Fact
import com.devicelab.core.model.Lab
import com.devicelab.core.model.LabReport
import com.devicelab.core.model.Section
import java.util.UUID
import javax.inject.Inject

/**
 * DRM: which content-protection schemes exist and what they will actually permit.
 *
 * This is the lab that most often decides whether a device can play a paid stream
 * at full resolution, and it is also the one most often stated wrongly. The claim
 * "Widevine L1" is repeated from spec sheets far more often than it is read from the
 * device, and the two disagree on plenty of hardware -- the same model can ship L1
 * in one region and L3 in another, and a custom ROM can drop a device from L1 to L3
 * without changing anything visible in Settings.
 *
 * So every row here comes from [MediaDrm] on this device. Two distinct sources are
 * used and kept visibly separate:
 *
 *  * Platform APIs. `getMaxSecurityLevel`, `getMaxHdcpLevel`, `getMaxSessionCount`
 *    and friends arrived in API 28 and return documented `SECURITY_LEVEL_*` and
 *    `HDCP_*` constants. On API 26 and 27 they do not exist, and the rows say so
 *    rather than falling back to a guess.
 *  * Vendor properties. `getPropertyString("securityLevel")` returning `L1` or `L3`
 *    is a Widevine convention rather than an Android API. It is reported because it
 *    is the vocabulary streaming services actually use, and it is labelled as a
 *    vendor property so the reader knows which is which.
 *
 * One thing is deliberately skipped: `PROPERTY_DEVICE_UNIQUE_ID`. The DRM stack will
 * hand over a stable per-device identifier, and that is a tracking token, not a
 * capability. The row records the refusal instead of quietly leaving a gap.
 *
 * No licence is ever requested, so nothing here needs a network connection and no
 * key is provisioned, stored or read.
 */
class DrmDetector @Inject constructor(
    private val probe: Probe,
) : CapabilityDetector {

    override val lab = Lab.DRM

    override suspend fun detect(): LabReport {
        val advertised = probe.attempt(emptyList<UUID>()) { SchemeReader.supportedSchemes() }
        val known = SCHEMES.map { spec -> spec to isSupported(spec.uuid) }
        val extras = advertised.filter { uuid ->
            SCHEMES.none { it.uuid == uuid }
        }

        return LabReport(
            lab = lab,
            sections = listOf(
                schemes(known, advertised),
                platformLimits(),
            ) +
                known.filter { it.second == true }.map { detail(it.first) } +
                extras.map { unnamedScheme(it) } +
                listOf(boundaries()),
            notes = listOf(
                "Security level, HDCP ceiling and session limits are read from the DRM " +
                    "plugin itself. No licence is requested and no key is provisioned, " +
                    "so this lab works with no network connection.",
                "L1 / L2 / L3 are Widevine's own vocabulary, not Android's. Rows sourced " +
                    "from a vendor property rather than a platform API are labelled as such.",
            ),
        )
    }

    // ------------------------------------------------------------------ schemes

    /**
     * Which schemes this device has a plugin for.
     *
     * `isCryptoSchemeSupported` has existed since API 18 and answers without
     * instantiating anything, which makes it the cheapest and most reliable probe in
     * this lab. From API 30 the platform will also enumerate its plugins, and any
     * UUID that enumeration returns which is not in the table below gets a row of
     * its own further down -- an unrecognised scheme is reported as a raw UUID rather
     * than dropped.
     */
    private fun schemes(
        known: List<Pair<SchemeSpec, Boolean?>>,
        advertised: List<UUID>,
    ) = Section(
        id = "drm-schemes",
        title = "Crypto schemes",
        subtitle = "MediaDrm.isCryptoSchemeSupported()",
        facts = known.map { (spec, supported) ->
            probe.verdict(
                spec.label,
                "MediaDrm.isCryptoSchemeSupported(${spec.shortUuid})",
                minApi = 18,
                domain = Domain.MEDIA,
                detail = spec.detail,
                searchTerms = spec.searchTerms,
            ) {
                when (supported) {
                    true -> Probe.Verdict.yes("Plugin present", "UUID ${spec.uuid}")
                    false -> Probe.Verdict.no(
                        "Queried — no plugin on this device",
                        "UUID ${spec.uuid}",
                    )
                    null -> null
                }
            }
        } + listOf(
            probe.value(
                "Plugins enumerated by the platform",
                "MediaDrm.getSupportedCryptoSchemes()",
                minApi = 30,
                searchTerms = listOf("crypto schemes", "plugins", "drm list"),
                detail = "The platform's own list, which is the authority on what is " +
                    "installed. Below API 30 there is no enumeration API and each " +
                    "scheme has to be asked about individually.",
            ) {
                advertised.takeIf { it.isNotEmpty() }?.let { list ->
                    val plural = if (list.size == 1) "plugin" else "plugins"
                    "${list.size} $plural"
                }
            },
        ),
    )

    /**
     * The device-wide ceilings, which are plugin-independent.
     *
     * [MediaDrm.getMaxSecurityLevel] is static: it reports the highest level any
     * plugin on this device could reach, which is a property of the hardware rather
     * than of a particular DRM vendor.
     */
    private fun platformLimits() = Section(
        id = "drm-platform",
        title = "Platform ceiling",
        subtitle = "Device-wide, independent of plugin",
        facts = listOf(
            probe.verdict(
                "Maximum security level",
                "MediaDrm.getMaxSecurityLevel()",
                minApi = 28,
                domain = Domain.MEDIA,
                searchTerms = listOf("security level", "hw secure", "l1", "tee", "secure decode"),
                detail = "The strongest protection the hardware can offer any plugin. A " +
                    "plugin can be configured below this, never above it.",
            ) { SchemeReader.maxSecurityLevel()?.let { securityLevelVerdict(it) } },
            probe.notExposedByAndroid(
                "Which decoders run inside the TEE",
                "A device can protect H.264 in hardware and fall back to software for " +
                    "AV1, and there is no API that lists which codecs the secure " +
                    "pipeline covers. The per-format rows in each plugin's section " +
                    "below are as close as Android gets.",
                domain = Domain.MEDIA,
                searchTerms = listOf("tee", "secure decoder", "which codecs", "trustzone"),
            ),
        ),
    )

    /**
     * Everything one plugin will say about itself.
     *
     * A [MediaDrm] instance is created, interrogated and released. The four
     * properties Android documents -- vendor, version, description, algorithms --
     * are read first, then the Widevine-specific ones, each labelled as a vendor
     * property. A session is opened only if the plugin allows it without
     * provisioning, because provisioning would need a network round trip this app
     * cannot and will not make.
     */
    private fun detail(spec: SchemeSpec): Section {
        val plugin = PluginReader.read(spec.uuid)
        return Section(
            id = "drm-${spec.id}",
            title = spec.label,
            subtitle = plugin.vendor?.takeIf { it.isNotBlank() } ?: "Plugin detail",
            facts = standardProperties(spec, plugin) +
                vendorProperties(spec, plugin) +
                levels(spec, plugin) +
                hdcp(plugin) +
                sessions(plugin),
            children = listOf(
                secureDecoderSection(spec, plugin),
                contentTypeSection(spec),
            ),
        )
    }

    private fun standardProperties(spec: SchemeSpec, plugin: PluginReader.Plugin): List<Fact> {
        if (plugin.failure != null) {
            return listOf(
                probe.value(
                    "Plugin instance",
                    "MediaDrm(${spec.shortUuid})",
                    minApi = 18,
                    detail = "isCryptoSchemeSupported reported the scheme as present, " +
                        "but constructing the plugin failed. That combination usually " +
                        "means a vendor plugin that is registered and not functional.",
                ) { null },
                probe.value(
                    "Instantiation error",
                    "MediaDrm(${spec.shortUuid})",
                    minApi = 18,
                ) { plugin.failure },
            )
        }
        return listOf(
            probe.value(
                "Vendor",
                "MediaDrm.getPropertyString(PROPERTY_VENDOR)",
                minApi = 18,
                searchTerms = listOf("vendor", "drm vendor"),
            ) { plugin.vendor },
            probe.value(
                "Version",
                "MediaDrm.getPropertyString(PROPERTY_VERSION)",
                minApi = 18,
                searchTerms = listOf("version", "widevine version", "cdm version"),
            ) { plugin.version },
            probe.value(
                "Description",
                "MediaDrm.getPropertyString(PROPERTY_DESCRIPTION)",
                minApi = 18,
            ) { plugin.description },
            probe.value(
                "Algorithms",
                "MediaDrm.getPropertyString(PROPERTY_ALGORITHMS)",
                minApi = 18,
                searchTerms = listOf("algorithms", "aes", "hmac", "cipher"),
                detail = "The crypto primitives the plugin exposes for generic " +
                    "encrypt/decrypt operations.",
            ) { plugin.algorithms },
        )
    }

    /**
     * Widevine's own properties.
     *
     * These are not in the Android SDK. They are strings the Widevine CDM answers
     * to, which is why every row names the property and says "vendor property" --
     * a device with a different DRM vendor will legitimately return nothing here.
     */
    private fun vendorProperties(spec: SchemeSpec, plugin: PluginReader.Plugin): List<Fact> =
        if (!spec.vendorProperties) {
            emptyList()
        } else {
            listOf(
                probe.verdict(
                    "Security level (vendor property)",
                    "MediaDrm.getPropertyString(\"securityLevel\")",
                    minApi = 18,
                    domain = Domain.MEDIA,
                    searchTerms = listOf("l1", "l2", "l3", "widevine level", "security level"),
                    detail = "The level streaming services quote. It is a Widevine " +
                        "convention rather than an Android API, and it is the plugin's " +
                        "own answer about itself.",
                ) { plugin.vendorSecurityLevel?.let { widevineLevelVerdict(it) } },
                probe.value(
                    "System ID (vendor property)",
                    "MediaDrm.getPropertyString(\"systemId\")",
                    minApi = 18,
                    searchTerms = listOf("system id", "widevine system id"),
                    detail = "Identifies the CDM implementation, not the device. It is " +
                        "shared by every device using the same CDM build.",
                ) { plugin.systemId },
                probe.value(
                    "OEMCrypto API version (vendor property)",
                    "MediaDrm.getPropertyString(\"oemCryptoApiVersion\")",
                    minApi = 18,
                    searchTerms = listOf("oemcrypto", "api version"),
                    detail = "The version of the hardware crypto interface the plugin " +
                        "talks to. Higher versions are required for newer Widevine " +
                        "features.",
                ) { plugin.oemCryptoApiVersion },
                probe.value(
                    "Resource rating tier (vendor property)",
                    "MediaDrm.getPropertyString(\"resourceRatingTier\")",
                    minApi = 18,
                    searchTerms = listOf("resource rating", "tier"),
                    detail = "Widevine's own grading of how much concurrent secure " +
                        "decoding the device can sustain.",
                ) { plugin.resourceRatingTier },
            )
        }

    private fun levels(spec: SchemeSpec, plugin: PluginReader.Plugin): List<Fact> = listOf(
        probe.verdict(
            "Session security level",
            "MediaDrm.getSecurityLevel(sessionId)",
            minApi = 28,
            domain = Domain.MEDIA,
            searchTerms = listOf("session security level", "hw secure all", "l1"),
            detail = "The platform constant for a real session, which is the " +
                "authoritative form of the level. Reading it needs a session, and a " +
                "session needs the plugin to be provisioned already — this app cannot " +
                "provision one because it has no network access.",
        ) {
            when {
                plugin.sessionSecurityLevel != null ->
                    securityLevelVerdict(plugin.sessionSecurityLevel)
                plugin.sessionFailure != null -> Probe.Verdict.unknown(
                    "No session could be opened",
                    "${plugin.sessionFailure}. The ceiling and vendor rows above still " +
                        "describe the plugin; only the per-session confirmation is missing.",
                )
                else -> null
            }
        },
        probe.value(
            "Scheme UUID",
            "MediaDrm scheme identifier",
            minApi = 18,
            searchTerms = listOf("uuid", spec.label.lowercase()),
        ) { spec.uuid.toString() },
    )

    private fun hdcp(plugin: PluginReader.Plugin): List<Fact> = listOf(
        probe.verdict(
            "Maximum HDCP level",
            "MediaDrm.getMaxHdcpLevel()",
            minApi = 28,
            domain = Domain.MEDIA,
            searchTerms = listOf("hdcp", "hdcp 2.2", "hdcp 2.3", "external display", "4k"),
            detail = "The strongest link protection this device's outputs can " +
                "negotiate. A protected 4K stream typically demands HDCP 2.2 or above.",
        ) { plugin.maxHdcp?.let { hdcpVerdict(it, ceiling = true) } },
        probe.verdict(
            "Connected HDCP level",
            "MediaDrm.getConnectedHdcpLevel()",
            minApi = 28,
            searchTerms = listOf("hdcp", "connected", "hdmi", "current"),
            detail = "What is negotiated right now. On a phone with nothing plugged in " +
                "this reports no digital output, which is not a fault — connect a " +
                "display and rescan and it will change.",
        ) { plugin.connectedHdcp?.let { hdcpVerdict(it, ceiling = false) } },
    )

    private fun sessions(plugin: PluginReader.Plugin): List<Fact> = listOf(
        probe.value(
            "Maximum concurrent sessions",
            "MediaDrm.getMaxSessionCount()",
            minApi = 28,
            searchTerms = listOf("sessions", "concurrent", "streams"),
            detail = "How many protected streams the plugin can hold open at once, " +
                "across every app on the device.",
        ) { plugin.maxSessions?.takeIf { it > 0 }?.toString() },
        probe.value(
            "Sessions open now",
            "MediaDrm.getOpenSessionCount()",
            minApi = 28,
            searchTerms = listOf("open sessions"),
            detail = "Counts sessions held by every app, so a video app running in the " +
                "background will show up here.",
        ) { plugin.openSessions?.toString() },
    )

    /**
     * Whether a secure decoder is mandatory for a given format.
     *
     * This is the closest Android comes to saying which codecs the protected
     * pipeline covers, and it is a genuinely useful answer: a `true` means the
     * plugin will refuse to decode that format outside the secure path.
     */
    private fun secureDecoderSection(spec: SchemeSpec, plugin: PluginReader.Plugin) = Section(
        id = "drm-${spec.id}-secure-decoder",
        title = "Secure decoder requirement",
        subtitle = "MediaDrm.requiresSecureDecoder()",
        facts = MIME_TYPES.map { mime ->
            probe.verdict(
                mime.label,
                "MediaDrm.requiresSecureDecoder(\"${mime.type}\")",
                minApi = 31,
                domain = Domain.MEDIA,
                searchTerms = mime.searchTerms,
            ) {
                plugin.secureDecoderRequired[mime.type]?.let { required ->
                    if (required) {
                        Probe.Verdict.yes(
                            "Secure decoder required",
                            "The plugin will only decode this format inside the " +
                                "protected pipeline.",
                        )
                    } else {
                        Probe.Verdict.partial(
                            "Secure decoder not required",
                            "The plugin will decode this format without the protected " +
                                "pipeline. That is a capability, not a defect — it is " +
                                "how lower-resolution tiers are served.",
                        )
                    }
                }
            }
        },
    )

    /**
     * Container and security-level combinations the scheme will accept.
     *
     * `isCryptoSchemeSupported(uuid, mimeType)` arrived in API 19 and the
     * three-argument form taking a security level in API 29. The three-argument form
     * is the interesting one: it answers "will this scheme protect this container at
     * this level", which is exactly the question a streaming app asks.
     */
    private fun contentTypeSection(spec: SchemeSpec) = Section(
        id = "drm-${spec.id}-containers",
        title = "Containers and levels",
        subtitle = "MediaDrm.isCryptoSchemeSupported(uuid, mimeType, level)",
        facts = CONTAINERS.map { container ->
            probe.flag(
                container.label,
                "MediaDrm.isCryptoSchemeSupported(${spec.shortUuid}, \"${container.type}\")",
                minApi = 19,
                searchTerms = container.searchTerms,
                supportedText = "Supported",
                unsupportedText = "Queried — not supported by this plugin",
            ) { SchemeReader.supportsContainer(spec.uuid, container.type) }
        } + SECURITY_LEVEL_PROBES.map { level ->
            probe.flag(
                level.label,
                "MediaDrm.isCryptoSchemeSupported(${spec.shortUuid}, \"video/mp4\", " +
                    "${level.constant})",
                minApi = 29,
                domain = Domain.MEDIA,
                searchTerms = level.searchTerms,
                supportedText = "Supported for video/mp4",
                unsupportedText = "Queried — not supported by this plugin",
                detail = level.detail,
            ) { SchemeReader.supportsLevel(spec.uuid, "video/mp4", level.value) }
        },
    )

    /**
     * A plugin the platform enumerated that this build has no name for.
     *
     * Reporting the raw UUID is the honest option. Guessing at a vendor from a
     * partial UUID match would be exactly the kind of invention this app avoids,
     * and a genuinely unknown scheme is still useful information.
     */
    private fun unnamedScheme(uuid: UUID): Section {
        val plugin = PluginReader.read(uuid)
        return Section(
            id = "drm-unnamed-${uuid.toString().take(8)}",
            title = plugin.vendor?.takeIf { it.isNotBlank() } ?: "Unrecognised scheme",
            subtitle = uuid.toString(),
            facts = listOf(
                probe.value(
                    "Scheme UUID",
                    "MediaDrm.getSupportedCryptoSchemes()",
                    minApi = 30,
                    searchTerms = listOf("uuid", "drm"),
                    detail = "The platform reports a plugin for this UUID and this " +
                        "build has no name for it. The identifier is shown as-is rather " +
                        "than matched to a guess.",
                ) { uuid.toString() },
                probe.value(
                    "Vendor",
                    "MediaDrm.getPropertyString(PROPERTY_VENDOR)",
                    minApi = 18,
                ) { plugin.vendor },
                probe.value(
                    "Version",
                    "MediaDrm.getPropertyString(PROPERTY_VERSION)",
                    minApi = 18,
                ) { plugin.version },
                probe.value(
                    "Description",
                    "MediaDrm.getPropertyString(PROPERTY_DESCRIPTION)",
                    minApi = 18,
                ) { plugin.description },
            ),
        )
    }

    private fun boundaries() = Section(
        id = "drm-boundaries",
        title = "Deliberately not read",
        subtitle = "Obtainable, and not a capability",
        facts = listOf(
            probe.notRead(
                "Device unique identifier",
                "MediaDrm.getPropertyString(PROPERTY_DEVICE_UNIQUE_ID)",
                "the DRM stack returns a stable per-device value that identifies this " +
                    "handset across apps and reinstalls. It says nothing about what the " +
                    "device can do, so it is not read, not stored and not exported.",
                searchTerms = listOf("device unique id", "drm id", "tracking"),
            ),
            probe.notRead(
                "Provisioning and licence data",
                "MediaDrm.getProvisionRequest() / getKeyRequest()",
                "requesting a licence means contacting a DRM server. This app has no " +
                    "network permission, and a provisioning round trip would ship a " +
                    "device identifier to a third party to answer a question the rows " +
                    "above already answer offline.",
                searchTerms = listOf("provisioning", "licence", "license", "key request"),
            ),
            probe.notRead(
                "Offline licences and secure stops",
                "MediaDrm.getOfflineLicenseKeySetIds() / getSecureStops()",
                "these enumerate licences other apps have stored for downloaded " +
                    "content. That is a record of what the user has been watching, not " +
                    "a device capability.",
                searchTerms = listOf("offline license", "secure stop", "downloads"),
            ),
        ),
    )

    // ------------------------------------------------------------------ mapping

    private fun isSupported(uuid: UUID): Boolean? =
        probe.attempt(null) { MediaDrm.isCryptoSchemeSupported(uuid) }

    private fun securityLevelVerdict(level: Int): Probe.Verdict = when (level) {
        MediaDrm.SECURITY_LEVEL_HW_SECURE_ALL -> Probe.Verdict.yes(
            "HW_SECURE_ALL",
            "Crypto, decode and the display path all stay inside secure hardware. " +
                "This is the level a service needs to see before it will send its " +
                "highest-resolution stream, and it corresponds to Widevine L1.",
        )
        MediaDrm.SECURITY_LEVEL_HW_SECURE_DECODE -> Probe.Verdict.yes(
            "HW_SECURE_DECODE",
            "Crypto and decode happen in secure hardware; the display path is not " +
                "covered.",
        )
        MediaDrm.SECURITY_LEVEL_HW_SECURE_CRYPTO -> Probe.Verdict.partial(
            "HW_SECURE_CRYPTO",
            "Keys and crypto are in hardware, decoding is not. This is Widevine L2 " +
                "territory and it is uncommon.",
        )
        MediaDrm.SECURITY_LEVEL_SW_SECURE_DECODE -> Probe.Verdict.partial(
            "SW_SECURE_DECODE",
            "Decoding is protected in software only. Most services cap resolution " +
                "here.",
        )
        MediaDrm.SECURITY_LEVEL_SW_SECURE_CRYPTO -> Probe.Verdict.partial(
            "SW_SECURE_CRYPTO",
            "Keys are handled in software. This corresponds to Widevine L3, and " +
                "streaming services generally limit playback to standard definition.",
        )
        MediaDrm.SECURITY_LEVEL_UNKNOWN -> Probe.Verdict.unknown(
            "Unknown",
            "The plugin itself reported the level as unknown.",
        )
        else -> Probe.Verdict.unknown(
            "Unrecognised level ($level)",
            "The plugin returned a constant this build has no name for. The raw value " +
                "is shown rather than mapped to the nearest guess.",
        )
    }

    private fun widevineLevelVerdict(raw: String): Probe.Verdict = when (raw.uppercase()) {
        "L1" -> Probe.Verdict.yes(
            "L1",
            "All key handling and decoding happen inside the trusted execution " +
                "environment. Required by most services for HD and above.",
        )
        "L2" -> Probe.Verdict.partial(
            "L2",
            "Keys are handled in the TEE, decoding is not. Rare in practice.",
        )
        "L3" -> Probe.Verdict.partial(
            "L3",
            "Key handling is in software. Services typically restrict playback to " +
                "standard definition.",
        )
        else -> Probe.Verdict.unknown(
            raw,
            "The plugin returned a level string this build does not recognise. It is " +
                "shown exactly as reported.",
        )
    }

    private fun hdcpVerdict(level: Int, ceiling: Boolean): Probe.Verdict {
        val name = HDCP_NAMES[level] ?: "Level $level"
        return when (level) {
            MediaDrm.HDCP_NO_DIGITAL_OUTPUT -> if (ceiling) {
                Probe.Verdict.no(
                    "$name — no digital output",
                    "This device has no digital video output at all, so link " +
                        "protection does not apply to it.",
                )
            } else {
                Probe.Verdict.partial(
                    "$name — nothing connected",
                    "Expected on a device with nothing plugged into it. The ceiling " +
                        "row above is the device's real capability.",
                )
            }
            MediaDrm.HDCP_NONE -> Probe.Verdict.partial(
                "$name — unprotected",
                "The output carries no link protection. A protected stream will be " +
                    "downscaled or refused.",
            )
            MediaDrm.HDCP_LEVEL_UNKNOWN -> Probe.Verdict.unknown(
                name,
                "The plugin could not determine the level.",
            )
            else -> Probe.Verdict.yes(
                name,
                if (ceiling) {
                    "The strongest link protection this device can negotiate."
                } else {
                    "Negotiated on the output that is connected right now."
                },
            )
        }
    }

    // ------------------------------------------------------------------ readers

    /**
     * The static [MediaDrm] queries, isolated because most of them are newer than
     * minSdk and referencing them from the detector body would drag API 28+ symbols
     * into a class that has to verify on API 26.
     */
    private object SchemeReader {

        fun supportedSchemes(): List<UUID> =
            if (Build.VERSION.SDK_INT >= 30) MediaDrm.getSupportedCryptoSchemes() else emptyList()

        fun maxSecurityLevel(): Int? =
            if (Build.VERSION.SDK_INT >= 28) MediaDrm.getMaxSecurityLevel() else null

        fun supportsContainer(uuid: UUID, mime: String): Boolean? = try {
            if (Build.VERSION.SDK_INT >= 19) {
                MediaDrm.isCryptoSchemeSupported(uuid, mime)
            } else {
                null
            }
        } catch (t: Throwable) {
            null
        }

        fun supportsLevel(uuid: UUID, mime: String, level: Int): Boolean? = try {
            if (Build.VERSION.SDK_INT >= 29) {
                MediaDrm.isCryptoSchemeSupported(uuid, mime, level)
            } else {
                null
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * One plugin, opened and closed.
     *
     * Everything is wrapped individually because DRM plugins are among the least
     * reliable code on an Android device: a vendor CDM that throws
     * `UnsupportedOperationException` from `getMaxHdcpLevel` while answering
     * everything else correctly is normal, and losing the whole section to it would
     * be the wrong outcome.
     *
     * `release()` rather than `close()` because `release` has existed since API 18
     * while `AutoCloseable` was only added to [MediaDrm] later. It runs in a
     * `finally`, so a plugin that throws mid-interrogation is still let go.
     */
    private object PluginReader {

        data class Plugin(
            val vendor: String? = null,
            val version: String? = null,
            val description: String? = null,
            val algorithms: String? = null,
            val vendorSecurityLevel: String? = null,
            val systemId: String? = null,
            val oemCryptoApiVersion: String? = null,
            val resourceRatingTier: String? = null,
            val sessionSecurityLevel: Int? = null,
            val sessionFailure: String? = null,
            val maxHdcp: Int? = null,
            val connectedHdcp: Int? = null,
            val maxSessions: Int? = null,
            val openSessions: Int? = null,
            val secureDecoderRequired: Map<String, Boolean> = emptyMap(),
            val failure: String? = null,
        )

        fun read(uuid: UUID): Plugin {
            var drm: MediaDrm? = null
            return try {
                drm = MediaDrm(uuid)
                val instance = drm
                val session = openSession(instance)
                try {
                    Plugin(
                        vendor = property(instance, MediaDrm.PROPERTY_VENDOR),
                        version = property(instance, MediaDrm.PROPERTY_VERSION),
                        description = property(instance, MediaDrm.PROPERTY_DESCRIPTION),
                        algorithms = property(instance, MediaDrm.PROPERTY_ALGORITHMS),
                        vendorSecurityLevel = property(instance, "securityLevel"),
                        systemId = property(instance, "systemId"),
                        oemCryptoApiVersion = property(instance, "oemCryptoApiVersion"),
                        resourceRatingTier = property(instance, "resourceRatingTier"),
                        sessionSecurityLevel = session.id?.let { sessionLevel(instance, it) },
                        sessionFailure = session.failure,
                        maxHdcp = intQuery { instance.maxHdcpLevel },
                        connectedHdcp = intQuery { instance.connectedHdcpLevel },
                        maxSessions = intQuery { instance.maxSessionCount },
                        openSessions = intQuery { instance.openSessionCount },
                        secureDecoderRequired = secureDecoders(instance),
                    )
                } finally {
                    session.id?.let { id ->
                        try {
                            instance.closeSession(id)
                        } catch (ignored: Throwable) {
                            // The plugin is being released next in any case.
                        }
                    }
                }
            } catch (t: Throwable) {
                Plugin(failure = describe(t))
            } finally {
                try {
                    @Suppress("DEPRECATION")
                    drm?.release()
                } catch (ignored: Throwable) {
                    // Nothing useful remains to be done with a plugin that will not close.
                }
            }
        }

        private data class Session(val id: ByteArray? = null, val failure: String? = null)

        /**
         * A session is opened only to read its security level.
         *
         * No key request is made, so the session carries no licence and no key. A
         * plugin that has never been provisioned throws `NotProvisionedException`,
         * which is reported as-is: provisioning would require the network access this
         * app does not have.
         */
        private fun openSession(drm: MediaDrm): Session = try {
            Session(id = drm.openSession())
        } catch (t: Throwable) {
            Session(failure = describe(t))
        }

        private fun sessionLevel(drm: MediaDrm, session: ByteArray): Int? = try {
            if (Build.VERSION.SDK_INT >= 28) drm.getSecurityLevel(session) else null
        } catch (t: Throwable) {
            null
        }

        private fun property(drm: MediaDrm, name: String): String? = try {
            drm.getPropertyString(name)?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            null
        }

        private fun intQuery(read: () -> Int): Int? = try {
            if (Build.VERSION.SDK_INT >= 28) read() else null
        } catch (t: Throwable) {
            null
        }

        private fun secureDecoders(drm: MediaDrm): Map<String, Boolean> {
            if (Build.VERSION.SDK_INT < 31) return emptyMap()
            val out = LinkedHashMap<String, Boolean>()
            MIME_TYPES.forEach { mime ->
                try {
                    out[mime.type] = drm.requiresSecureDecoder(mime.type)
                } catch (ignored: Throwable) {
                    // A plugin that will not answer for one format still answers for
                    // the others; the missing key becomes an unknown row.
                }
            }
            return out
        }

        private fun describe(t: Throwable): String =
            t.javaClass.simpleName + (t.message?.let { ": ${it.take(140)}" } ?: "")
    }

    // -------------------------------------------------------------------- table

    private data class SchemeSpec(
        val id: String,
        val label: String,
        val uuid: UUID,
        val detail: String,
        val searchTerms: List<String>,
        val vendorProperties: Boolean = false,
    ) {
        /** The first UUID group, enough to identify the scheme in a provenance line. */
        val shortUuid: String get() = uuid.toString().substringBefore('-')
    }

    private data class MimeSpec(
        val label: String,
        val type: String,
        val searchTerms: List<String>,
    )

    private data class LevelProbe(
        val label: String,
        val value: Int,
        val constant: String,
        val detail: String,
        val searchTerms: List<String>,
    )

    private companion object {

        /**
         * The DRM schemes worth asking about.
         *
         * The UUIDs are the registered DASH-IF content-protection identifiers. Apple
         * FairPlay is included precisely because it is *not* an Android scheme: a row
         * that says "queried, no plugin" is more informative than silence for anyone
         * wondering why an iOS-targeted stream will not play.
         */
        val SCHEMES = listOf(
            SchemeSpec(
                id = "widevine",
                label = "Widevine",
                uuid = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"),
                detail = "Google's scheme, and the one nearly every streaming service " +
                    "on Android uses.",
                searchTerms = listOf("widevine", "drm", "l1", "l3", "netflix", "streaming"),
                vendorProperties = true,
            ),
            SchemeSpec(
                id = "playready",
                label = "PlayReady",
                uuid = UUID.fromString("9a04f079-9840-4286-ab92-e65be0885f95"),
                detail = "Microsoft's scheme. Common on set-top boxes and televisions, " +
                    "uncommon on phones.",
                searchTerms = listOf("playready", "microsoft", "drm"),
            ),
            SchemeSpec(
                id = "clearkey",
                label = "ClearKey",
                uuid = UUID.fromString("e2719d58-a985-b3c9-781a-b030af78d30e"),
                detail = "The unencrypted reference scheme from the EME specification. " +
                    "Present on essentially every device and protects nothing — it " +
                    "exists for testing.",
                searchTerms = listOf("clearkey", "eme", "reference"),
            ),
            SchemeSpec(
                id = "wiseplay",
                label = "WisePlay",
                uuid = UUID.fromString("3d5e6d35-9b9a-41e8-b843-dd3c6e72c42c"),
                detail = "Huawei's scheme, used by Chinese streaming services.",
                searchTerms = listOf("wiseplay", "huawei", "drm"),
            ),
            SchemeSpec(
                id = "marlin",
                label = "Marlin",
                uuid = UUID.fromString("5e629af5-38da-4063-8977-97ffbd9902d4"),
                detail = "An open consortium scheme, mostly seen in Japanese broadcast " +
                    "and IPTV.",
                searchTerms = listOf("marlin", "drm"),
            ),
            SchemeSpec(
                id = "primetime",
                label = "Adobe Primetime",
                uuid = UUID.fromString("f239e769-efa3-4850-9c16-a903c6932efb"),
                detail = "Adobe's scheme. Discontinued, and asked about because older " +
                    "devices still carry the plugin.",
                searchTerms = listOf("primetime", "adobe", "access", "drm"),
            ),
            SchemeSpec(
                id = "fairplay",
                label = "Apple FairPlay Streaming",
                uuid = UUID.fromString("94ce86fb-07ff-4f43-adb8-93d2fa968ca2"),
                detail = "Apple's scheme, which has no Android implementation. Asked " +
                    "about so the answer is a stated no rather than an absent row.",
                searchTerms = listOf("fairplay", "apple", "hls", "drm"),
            ),
        )

        /** Formats worth asking `requiresSecureDecoder` about. */
        val MIME_TYPES = listOf(
            MimeSpec("H.264 / AVC", "video/avc", listOf("h264", "avc", "secure decoder")),
            MimeSpec("H.265 / HEVC", "video/hevc", listOf("h265", "hevc", "secure decoder")),
            MimeSpec("VP9", "video/x-vnd.on2.vp9", listOf("vp9", "secure decoder")),
            MimeSpec("AV1", "video/av01", listOf("av1", "secure decoder")),
            MimeSpec("AAC", "audio/mp4a-latm", listOf("aac", "secure decoder", "audio")),
        )

        /** Containers `isCryptoSchemeSupported` can be asked about. */
        val CONTAINERS = listOf(
            MimeSpec("MP4 / CENC container", "video/mp4", listOf("mp4", "cenc", "dash")),
            MimeSpec("WebM container", "video/webm", listOf("webm", "vp9")),
            MimeSpec("MPEG-2 transport stream", "video/mp2t", listOf("mp2t", "ts", "hls")),
            MimeSpec("MP4 audio", "audio/mp4", listOf("audio", "mp4", "aac")),
        )

        /**
         * Levels to test a scheme against for `video/mp4`.
         *
         * Only the two that distinguish a real hardware pipeline from a software one
         * are asked about; the intermediate constants add rows without adding
         * information a reader would act on.
         */
        val SECURITY_LEVEL_PROBES = listOf(
            LevelProbe(
                label = "video/mp4 at HW_SECURE_ALL",
                value = MediaDrm.SECURITY_LEVEL_HW_SECURE_ALL,
                constant = "SECURITY_LEVEL_HW_SECURE_ALL",
                detail = "The level a service checks for before sending its highest " +
                    "resolution tier.",
                searchTerms = listOf("hw secure all", "l1", "4k", "hd", "security level"),
            ),
            LevelProbe(
                label = "video/mp4 at SW_SECURE_CRYPTO",
                value = MediaDrm.SECURITY_LEVEL_SW_SECURE_CRYPTO,
                constant = "SECURITY_LEVEL_SW_SECURE_CRYPTO",
                detail = "The software fallback. Support here with none above means " +
                    "standard-definition playback only.",
                searchTerms = listOf("sw secure crypto", "l3", "software", "sd"),
            ),
        )

        /**
         * `HDCP_*` constant names.
         *
         * Written out rather than derived, and every entry is an int constant so the
         * newer ones inline at compile time and are safe to name on any device.
         */
        val HDCP_NAMES: Map<Int, String> = mapOf(
            MediaDrm.HDCP_LEVEL_UNKNOWN to "Unknown",
            MediaDrm.HDCP_NONE to "None",
            MediaDrm.HDCP_V1 to "HDCP 1.x",
            MediaDrm.HDCP_V2 to "HDCP 2.0",
            MediaDrm.HDCP_V2_1 to "HDCP 2.1",
            MediaDrm.HDCP_V2_2 to "HDCP 2.2",
            MediaDrm.HDCP_V2_3 to "HDCP 2.3",
            MediaDrm.HDCP_NO_DIGITAL_OUTPUT to "No digital output",
        )
    }
}
