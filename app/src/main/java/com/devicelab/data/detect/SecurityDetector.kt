package com.devicelab.data.detect

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import com.devicelab.core.detect.Probe
import com.devicelab.core.model.Domain
import com.devicelab.core.model.Lab
import com.devicelab.core.model.LabReport
import com.devicelab.core.model.Section
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject

/**
 * Biometric and keystore *capability*, and nothing else.
 *
 * This is the one lab where the boundary matters more than the coverage, so it is
 * worth being explicit about where the line is and why it is not negotiable.
 *
 * What this lab reports is classification information: whether fingerprint, face or
 * iris hardware exists, which biometric strength class the platform will authorise
 * ([BiometricManager.Authenticators.BIOMETRIC_STRONG] versus `BIOMETRIC_WEAK`),
 * whether the keystore is hardware-backed, and whether a StrongBox element is
 * present. Every one of those is a property of the device.
 *
 * What it never touches is biometric templates, raw enrollment data, and private or
 * secret key material. That is not a privacy setting this app chose to default on:
 * there is no API path to any of it. A fingerprint template never leaves the TEE or
 * the sensor's own secure buffer, and an `AndroidKeyStore` private key is a handle
 * to hardware -- `getEncoded()` on one returns null by design. Root does not change
 * that, a permission does not change that, and neither does being offline. The
 * hardware is built so the bytes cannot come out.
 *
 * Hardware backing has no query API, so the only honest way to answer it is to do
 * what the documentation prescribes: generate a key, ask the resulting [KeyInfo]
 * where it lives, and delete it. Two throwaway EC keys are created in this app's own
 * keystore namespace and removed in a `finally` block. Nothing is signed with them,
 * no bytes are read out of them, and the aliases are named so they are obvious if a
 * crash ever leaves one behind.
 */
class SecurityDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val probe: Probe,
) : CapabilityDetector {

    override val lab = Lab.SECURITY

    override suspend fun detect(): LabReport {
        val pm = context.packageManager
        val biometrics = probe.attempt<BiometricManager?>(null) {
            BiometricManager.from(context)
        }
        val tee = KeystoreProbe.run(strongBox = false)
        val strongBox = KeystoreProbe.run(strongBox = true)

        return LabReport(
            lab = lab,
            sections = listOf(
                biometricHardware(pm),
                biometricStrength(biometrics),
                biometricLimits(),
                keystoreBacking(tee, strongBox),
                keystoreFeatures(pm),
                keyBinding(pm),
                lockScreen(pm),
                verifiedBoot(pm),
                patchLevel(),
                encryption(),
                boundaries(),
            ),
            notes = listOf(
                "Hardware-backed keystore and StrongBox have no query API. They are " +
                    "answered the documented way: a throwaway EC key is generated in " +
                    "this app's own keystore namespace, its KeyInfo is read, and the key " +
                    "is deleted immediately. Nothing is signed and no key bytes are read.",
                "This lab reports capability and classification only. Biometric " +
                    "templates and private key material are non-exportable by hardware " +
                    "design — there is no API that returns them, with or without " +
                    "permissions, and being offline does not change what the OS will give up.",
            ),
        )
    }

    // ---------------------------------------------------------------- biometrics

    private fun biometricHardware(pm: PackageManager) = Section(
        id = "sec-biometric-hardware",
        title = "Biometric hardware",
        subtitle = "PackageManager feature flags",
        facts = listOf(
            probe.flag(
                "Fingerprint sensor",
                "PackageManager.FEATURE_FINGERPRINT",
                minApi = 23,
                domain = Domain.SECURITY,
                searchTerms = listOf("fingerprint", "biometric", "touch id", "under display"),
                supportedText = "Present",
                unsupportedText = "Queried — not present on this hardware",
            ) { pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) },
            probe.flag(
                "Face authentication hardware",
                "PackageManager.FEATURE_FACE",
                minApi = 29,
                domain = Domain.SECURITY,
                searchTerms = listOf("face", "face unlock", "biometric"),
                supportedText = "Present",
                unsupportedText = "Queried — not present on this hardware",
                detail = "The flag means the platform has a face authentication " +
                    "integration. It says nothing about whether that integration is " +
                    "depth-sensing or camera-only — see the row below.",
            ) { pm.hasSystemFeature(PackageManager.FEATURE_FACE) },
            probe.flag(
                "Iris scanner",
                "PackageManager.FEATURE_IRIS",
                minApi = 29,
                domain = Domain.SECURITY,
                searchTerms = listOf("iris", "eye", "biometric"),
                supportedText = "Present",
                unsupportedText = "Queried — not present on this hardware",
            ) { pm.hasSystemFeature(PackageManager.FEATURE_IRIS) },
        ),
    )

    /**
     * The strength classes the platform will actually authorise.
     *
     * `canAuthenticate` is the real question, and it is more informative than the
     * feature flags above: a device can have face hardware that only qualifies as
     * Class 2, in which case `BIOMETRIC_STRONG` correctly answers no even though
     * `FEATURE_FACE` answered yes. The two rows disagreeing is not a bug -- it is
     * the distinction between "a sensor exists" and "the OS trusts it to guard a
     * key".
     */
    private fun biometricStrength(manager: BiometricManager?) = Section(
        id = "sec-biometric-strength",
        title = "Authentication strength",
        subtitle = "BiometricManager.canAuthenticate()",
        facts = STRENGTHS.map { spec ->
            probe.verdict(
                spec.label,
                "BiometricManager.canAuthenticate(${spec.constant})",
                minApi = spec.minApi,
                domain = Domain.SECURITY,
                detail = spec.detail,
                searchTerms = spec.searchTerms,
            ) {
                manager?.let { describeStatus(it.canAuthenticate(spec.authenticators)) }
            }
        },
    )

    private fun describeStatus(status: Int): Probe.Verdict = when (status) {
        BiometricManager.BIOMETRIC_SUCCESS ->
            Probe.Verdict.yes(
                "Available and enrolled",
                "The platform will authenticate at this strength right now.",
            )

        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
            Probe.Verdict.partial(
                "Hardware present — nothing enrolled",
                "Hardware of this class exists and the platform would use it, but no " +
                    "credential of this kind has been set up. That is a device setting, " +
                    "not a hardware limit.",
            )

        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
            Probe.Verdict.partial(
                "Hardware present — currently unavailable",
                "The sensor exists but is not answering: temporarily locked out after " +
                    "failed attempts, in use by another process, or disabled by policy.",
            )

        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
            Probe.Verdict.partial(
                "Hardware present — security update required",
                "The platform has withdrawn this sensor from use pending a security " +
                    "update. The hardware is capable; the OS will not currently trust it.",
            )

        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
            Probe.Verdict.no(
                "Queried — no hardware of this class",
                "No sensor on this device meets this strength classification.",
            )

        BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED ->
            Probe.Verdict.no(
                "Not supported at this strength",
                "The platform rejected the request outright: this Android version " +
                    "cannot authorise this combination of authenticators.",
            )

        BiometricManager.BIOMETRIC_STATUS_UNKNOWN ->
            Probe.Verdict.unknown(
                "Indeterminate",
                "The compatibility layer could not establish the status on this " +
                    "version. It is reported as unknown rather than guessed either way.",
            )

        else -> Probe.Verdict.unknown(
            "Unrecognised status ($status)",
            "The platform returned a status code this build does not have a name for. " +
                "The raw value is shown rather than being mapped to the nearest guess.",
        )
    }

    /**
     * The biometric questions Android deliberately will not answer.
     *
     * All four are things other tools present as facts. They are inferred from the
     * model name against a lookup table, which is a guess about the device rather
     * than a reading from it.
     */
    private fun biometricLimits() = Section(
        id = "sec-biometric-limits",
        title = "Not exposed: biometric detail",
        subtitle = "No API on any Android version",
        facts = listOf(
            probe.notExposedByAndroid(
                "Which sensor satisfies Class 3",
                "The platform reports a strength class, not which modality earns it. " +
                    "On a device with both fingerprint and face hardware there is no API " +
                    "that says which of the two the OS classifies as BIOMETRIC_STRONG.",
                domain = Domain.SECURITY,
                searchTerms = listOf("class 3", "strong", "which sensor", "modality"),
            ),
            probe.notExposedByAndroid(
                "Fingerprint sensor technology",
                "Optical, ultrasonic, capacitive, under-display or side-mounted: none " +
                    "of it is exposed. Android reports that a fingerprint sensor exists " +
                    "and nothing about how it works or where it is.",
                domain = Domain.SECURITY,
                searchTerms = listOf("ultrasonic", "optical", "capacitive", "under display", "sensor type"),
            ),
            probe.notExposedByAndroid(
                "Face unlock depth sensing",
                "Whether face authentication uses structured light or depth hardware " +
                    "rather than the selfie camera alone is not queryable. The strength " +
                    "class above is the closest real signal: depth-based implementations " +
                    "are the ones that typically qualify as Class 3.",
                domain = Domain.SECURITY,
                searchTerms = listOf("3d face", "depth", "structured light", "face id"),
            ),
            probe.notExposedByAndroid(
                "Number of enrolled biometrics",
                "There is no count API, by design. An app may learn that something is " +
                    "enrolled, never how many or whose.",
                domain = Domain.SECURITY,
                searchTerms = listOf("enrolled", "how many fingerprints"),
            ),
        ),
    )

    // ----------------------------------------------------------------- keystore

    /**
     * Where a generated key actually lives.
     *
     * [KeyInfo.isInsideSecureHardware] exists from API 23 and answers the yes/no.
     * From API 31 `getSecurityLevel()` refines it into software / TEE / StrongBox,
     * which is the distinction worth having: both a TEE key and a StrongBox key are
     * "inside secure hardware", but only StrongBox is a discrete tamper-resistant
     * chip with its own CPU and storage.
     */
    private fun keystoreBacking(
        tee: KeystoreProbe.Result,
        strongBox: KeystoreProbe.Result,
    ) = Section(
        id = "sec-keystore-backing",
        title = "Keystore backing",
        subtitle = "Probed by generating and deleting a throwaway EC key",
        facts = listOf(
            probe.verdict(
                "Hardware-backed keystore",
                "KeyInfo.isInsideSecureHardware()",
                minApi = 23,
                domain = Domain.SECURITY,
                searchTerms = listOf("tee", "trustzone", "hardware backed", "keystore", "keymint"),
            ) {
                when {
                    tee.failure != null -> Probe.Verdict.unknown(
                        "Probe failed",
                        "Key generation did not complete: ${tee.failure}. Without a key " +
                            "there is no KeyInfo to read, so this is reported as unknown " +
                            "rather than assumed either way.",
                    )
                    tee.insideSecureHardware == true -> Probe.Verdict.yes(
                        "Yes — key generated inside secure hardware",
                        "The generated key's private half never existed in the " +
                            "application processor's memory.",
                    )
                    tee.insideSecureHardware == false -> Probe.Verdict.no(
                        "No — software keystore only",
                        "Keys are held by the keystore daemon in ordinary memory. This " +
                            "is normal on emulators and on some low-cost builds.",
                    )
                    else -> Probe.Verdict.unknown(
                        "Indeterminate",
                        "The key generated but its KeyInfo could not be read.",
                    )
                }
            },
            probe.verdict(
                "Keystore security level",
                "KeyInfo.getSecurityLevel()",
                minApi = 31,
                domain = Domain.SECURITY,
                searchTerms = listOf("security level", "strongbox", "trusted environment", "keymint"),
                detail = "Refines the row above from a boolean into which class of " +
                    "hardware holds the key.",
            ) {
                tee.securityLevel?.let { level ->
                    when (level) {
                        KeyProperties.SECURITY_LEVEL_STRONGBOX -> Probe.Verdict.yes(
                            "StrongBox",
                            "A discrete tamper-resistant secure element with its own " +
                                "processor and storage.",
                        )
                        KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> Probe.Verdict.yes(
                            "Trusted execution environment",
                            "An isolated execution mode on the main SoC — ARM " +
                                "TrustZone or the vendor equivalent.",
                        )
                        KeyProperties.SECURITY_LEVEL_SOFTWARE -> Probe.Verdict.no(
                            "Software",
                            "No hardware isolation for this key.",
                        )
                        KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> Probe.Verdict.partial(
                            "Secure hardware of an unreported kind",
                            "The keystore confirms hardware isolation but does not " +
                                "report which class. That is the platform's answer, " +
                                "reported as given.",
                        )
                        KeyProperties.SECURITY_LEVEL_UNKNOWN -> Probe.Verdict.unknown(
                            "Unknown",
                            "The keystore itself reported the level as unknown.",
                        )
                        else -> Probe.Verdict.unknown("Unrecognised level ($level)")
                    }
                }
            },
            probe.verdict(
                "StrongBox secure element",
                "KeyGenParameterSpec.Builder.setIsStrongBoxBacked(true)",
                minApi = 28,
                domain = Domain.SECURITY,
                searchTerms = listOf("strongbox", "secure element", "titan m", "tamper resistant"),
                detail = "Asked the definitive way: request a StrongBox-backed key and " +
                    "see whether the keystore provides one. A device without the " +
                    "element throws StrongBoxUnavailableException.",
            ) {
                when {
                    strongBox.strongBoxUnavailable -> Probe.Verdict.no(
                        "Queried — no StrongBox element",
                        "The keystore raised StrongBoxUnavailableException, which is " +
                            "the platform stating plainly that the hardware is absent.",
                    )
                    strongBox.insideSecureHardware == true -> Probe.Verdict.yes(
                        "Present — StrongBox key generated",
                        "A key was created in the secure element and then deleted.",
                    )
                    strongBox.failure != null -> Probe.Verdict.unknown(
                        "Probe failed",
                        "StrongBox generation failed for another reason: " +
                            "${strongBox.failure}. Not treated as absence.",
                    )
                    else -> Probe.Verdict.unknown("Indeterminate")
                }
            },
            probe.value(
                "Probe key size",
                "KeyInfo.getKeySize()",
                minApi = 23,
                searchTerms = listOf("key size", "ec", "p-256"),
                detail = "Confirms the probe key was really created to the requested " +
                    "specification rather than silently substituted.",
            ) { tee.keySize?.let { "$it-bit EC" } },
            probe.value(
                "Probe key origin",
                "KeyInfo.getOrigin()",
                minApi = 23,
                searchTerms = listOf("origin", "generated"),
            ) { tee.origin?.let { originName(it) } },
        ),
    )

    /**
     * Keystore features declared as platform features rather than probed.
     *
     * These are the flags CTS requires a device to declare honestly, so they are a
     * reliable statement of what KeyMint on this device implements.
     */
    private fun keystoreFeatures(pm: PackageManager) = Section(
        id = "sec-keystore-features",
        title = "Keystore features",
        subtitle = "PackageManager feature flags",
        facts = KEYSTORE_FEATURES.map { (label, spec) ->
            val (feature, minApi) = spec
            probe.flag(
                label,
                "PackageManager.hasSystemFeature(\"$feature\")",
                minApi = minApi,
                domain = Domain.SECURITY,
                searchTerms = listOf(label.lowercase(), "keystore"),
                supportedText = "Supported",
                unsupportedText = "Queried — not supported by this hardware",
            ) { pm.hasSystemFeature(feature) }
        } + listOf(
            probe.value(
                "Hardware keystore version",
                "FEATURE_HARDWARE_KEYSTORE → FeatureInfo.version",
                minApi = 31,
                searchTerms = listOf("keymint", "keymaster", "version"),
                detail = "The feature carries a version number: 100 and above means " +
                    "KeyMint on the AIDL interface, below that Keymaster on HIDL. Read " +
                    "from the platform's own feature list, so it is the exact number the " +
                    "device declares rather than a lower bound.",
            ) { keystoreVersion(pm, PackageManager.FEATURE_HARDWARE_KEYSTORE) },
            probe.value(
                "StrongBox keystore version",
                "FEATURE_STRONGBOX_KEYSTORE → FeatureInfo.version",
                minApi = 28,
                searchTerms = listOf("strongbox", "version", "keymint"),
            ) { keystoreVersion(pm, PackageManager.FEATURE_STRONGBOX_KEYSTORE) },
        ),
    )

    /**
     * What a key can be tied to.
     *
     * Attestation is probed rather than declared: request an attestation challenge
     * and count the certificates the keystore returns. One certificate means a
     * self-signed software chain; a longer chain means the key is attested by a
     * hardware root. Only the count is read -- no certificate contents, no public
     * key, and certainly no private key.
     */
    private fun keyBinding(pm: PackageManager): Section {
        val attestation = KeystoreProbe.attestation()
        val idAttestation = KeystoreProbe.deviceIdAttestation()
        return Section(
            id = "sec-key-binding",
            title = "Key attestation and binding",
            subtitle = "Probed with throwaway keys",
            facts = listOf(
                probe.verdict(
                    "Key attestation",
                    "KeyGenParameterSpec.Builder.setAttestationChallenge()",
                    minApi = 24,
                    domain = Domain.SECURITY,
                    searchTerms = listOf("attestation", "certificate chain", "hardware root"),
                ) {
                    when {
                        attestation.failure != null -> Probe.Verdict.unknown(
                            "Probe failed",
                            "Attestation key generation failed: ${attestation.failure}",
                        )
                        (attestation.chainLength ?: 0) > 1 -> Probe.Verdict.yes(
                            "Supported — ${attestation.chainLength} certificate chain",
                            "The keystore returned a chain rather than a single " +
                                "self-signed certificate, which means the key is " +
                                "attested up to a hardware root. Only the number of " +
                                "certificates is read; no certificate content is " +
                                "parsed or shown.",
                        )
                        attestation.chainLength == 1 -> Probe.Verdict.no(
                            "Software attestation only",
                            "A single self-signed certificate came back, so there is " +
                                "no hardware root of trust behind it.",
                        )
                        else -> Probe.Verdict.unknown("Indeterminate")
                    }
                },
                probe.verdict(
                    "Device property attestation",
                    "KeyGenParameterSpec.Builder.setDevicePropertiesAttestationIncluded()",
                    minApi = 31,
                    domain = Domain.SECURITY,
                    searchTerms = listOf("id attestation", "device properties", "brand", "model"),
                    detail = "Whether the secure hardware will vouch for the device's " +
                        "own brand, device and model strings. The attestation is " +
                        "requested to see whether it is honoured; the resulting " +
                        "certificate is not read.",
                ) {
                    when {
                        idAttestation.unsupported -> Probe.Verdict.no(
                            "Queried — not supported by this hardware",
                            "The keystore refused the request, which is how a device " +
                                "without ID attestation answers.",
                        )
                        idAttestation.chainLength != null -> Probe.Verdict.yes(
                            "Supported",
                            "The keystore accepted a key requesting device property " +
                                "attestation.",
                        )
                        idAttestation.failure != null -> Probe.Verdict.unknown(
                            "Probe failed",
                            idAttestation.failure,
                        )
                        else -> Probe.Verdict.unknown("Indeterminate")
                    }
                },
                probe.verdict(
                    "Biometric-bound keys",
                    "KeyGenParameterSpec.Builder.setUserAuthenticationParameters()",
                    minApi = 30,
                    domain = Domain.SECURITY,
                    searchTerms = listOf("auth bound", "biometric bound", "setUserAuthenticationParameters"),
                    detail = "A key the secure hardware will only use after a Class 3 " +
                        "biometric or the device credential has been verified. The key is " +
                        "generated and deleted; no authentication prompt is ever shown " +
                        "and nothing is signed.",
                ) { describeConstraint(KeystoreProbe.authBoundKey()) },
                probe.verdict(
                    "Unlocked-device-required keys",
                    "KeyGenParameterSpec.Builder.setUnlockedDeviceRequired()",
                    minApi = 28,
                    domain = Domain.SECURITY,
                    searchTerms = listOf("unlocked device required", "screen lock"),
                    detail = "A key that becomes unusable the moment the screen locks.",
                ) { describeConstraint(KeystoreProbe.unlockedDeviceKey()) },
                probe.flag(
                    "Identity Credential hardware",
                    "PackageManager.FEATURE_IDENTITY_CREDENTIAL_HARDWARE",
                    minApi = 31,
                    domain = Domain.SECURITY,
                    searchTerms = listOf("identity credential", "mdoc", "mobile driving licence"),
                    supportedText = "Supported",
                    unsupportedText = "Queried — not supported by this hardware",
                    detail = "Secure hardware for ISO 18013-5 mobile documents.",
                ) { pm.hasSystemFeature(PackageManager.FEATURE_IDENTITY_CREDENTIAL_HARDWARE) },
                probe.flag(
                    "Identity Credential direct access",
                    "PackageManager.FEATURE_IDENTITY_CREDENTIAL_HARDWARE_DIRECT_ACCESS",
                    minApi = 31,
                    domain = Domain.SECURITY,
                    searchTerms = listOf("direct access", "identity credential", "power off"),
                    supportedText = "Supported",
                    unsupportedText = "Queried — not supported by this hardware",
                    detail = "Lets a credential be presented while the device is off or " +
                        "the battery is flat.",
                ) {
                    pm.hasSystemFeature(
                        PackageManager.FEATURE_IDENTITY_CREDENTIAL_HARDWARE_DIRECT_ACCESS,
                    )
                },
            ),
        )
    }

    // -------------------------------------------------------------- lock screen

    private fun lockScreen(pm: PackageManager): Section {
        val keyguard = probe.attempt<KeyguardManager?>(null) {
            context.getSystemService(KeyguardManager::class.java)
        }
        return Section(
            id = "sec-lock-screen",
            title = "Lock screen",
            subtitle = "KeyguardManager",
            facts = listOf(
                probe.flag(
                    "Secure lock screen supported",
                    "PackageManager.FEATURE_SECURE_LOCK_SCREEN",
                    minApi = 29,
                    domain = Domain.SECURITY,
                    searchTerms = listOf("lock screen", "pin", "pattern", "password"),
                    supportedText = "Supported",
                    unsupportedText = "Queried — not supported by this build",
                    detail = "Whether this build offers a PIN, pattern or password at " +
                        "all. Some embedded and automotive builds do not.",
                ) { pm.hasSystemFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN) },
                probe.verdict(
                    "Screen lock configured",
                    "KeyguardManager.isDeviceSecure()",
                    minApi = 23,
                    domain = Domain.SECURITY,
                    searchTerms = listOf("device secure", "screen lock set"),
                ) {
                    keyguard?.let {
                        if (it.isDeviceSecure) {
                            Probe.Verdict.yes(
                                "Yes — credential set",
                                "A PIN, pattern or password is set. Keys that require " +
                                    "user authentication can therefore be created.",
                            )
                        } else {
                            Probe.Verdict.partial(
                                "No credential set",
                                "This is a device setting rather than a hardware limit, " +
                                    "but it does gate real capability: " +
                                    "authentication-bound keys cannot be generated " +
                                    "without a credential.",
                            )
                        }
                    }
                },
                probe.flag(
                    "Keyguard secure",
                    "KeyguardManager.isKeyguardSecure()",
                    minApi = 16,
                    searchTerms = listOf("keyguard"),
                    supportedText = "Yes",
                    unsupportedText = "No",
                    detail = "Includes a SIM PIN as well as a device credential, which " +
                        "is why it can differ from the row above.",
                ) { keyguard?.isKeyguardSecure },
                probe.flag(
                    "Device administration",
                    "PackageManager.FEATURE_DEVICE_ADMIN",
                    minApi = 19,
                    searchTerms = listOf("device admin", "mdm", "enterprise"),
                    supportedText = "Supported",
                    unsupportedText = "Queried — not supported by this build",
                ) { pm.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN) },
            ),
        )
    }

    // ------------------------------------------------------------ verified boot

    /**
     * Boot integrity.
     *
     * `FEATURE_VERIFIED_BOOT` is the platform's declaration that the feature exists.
     * The *state* -- green, yellow, orange -- has no API at all, only the
     * world-readable `ro.boot.*` properties that `adb shell getprop` prints. Those
     * are read through [SystemProperties], which reports an absent property as
     * unknown rather than as a "no".
     */
    private fun verifiedBoot(pm: PackageManager) = Section(
        id = "sec-verified-boot",
        title = "Boot integrity",
        subtitle = "Feature flag, then boot properties",
        facts = listOf(
            probe.flag(
                "Verified boot",
                "PackageManager.FEATURE_VERIFIED_BOOT",
                minApi = 21,
                domain = Domain.SECURITY,
                searchTerms = listOf("verified boot", "avb", "dm-verity", "secure boot"),
                supportedText = "Supported",
                unsupportedText = "Queried — not supported by this build",
            ) { pm.hasSystemFeature(PackageManager.FEATURE_VERIFIED_BOOT) },
            probe.verdict(
                "Verified boot state",
                "ro.boot.verifiedbootstate",
                domain = Domain.SECURITY,
                searchTerms = listOf("green", "yellow", "orange", "boot state", "avb"),
                detail = "Green: locked and running the OEM's signed image. Yellow: " +
                    "locked, verified against a user-supplied key. Orange: unlocked, " +
                    "verification not enforced.",
            ) {
                when (SystemProperties.get("ro.boot.verifiedbootstate")?.lowercase()) {
                    "green" -> Probe.Verdict.yes(
                        "Green — verified against the OEM key",
                        "The boot chain verified against the manufacturer's root of trust.",
                    )
                    "yellow" -> Probe.Verdict.partial(
                        "Yellow — verified against a user key",
                        "The boot chain verified, but against a key the user installed.",
                    )
                    "orange" -> Probe.Verdict.partial(
                        "Orange — verification not enforced",
                        "The bootloader is unlocked. Hardware-backed keys still work, " +
                            "but attestation will report the unlocked state.",
                    )
                    "red" -> Probe.Verdict.no(
                        "Red — verification failed",
                        "The bootloader reports a verification failure.",
                    )
                    else -> null
                }
            },
            probe.verdict(
                "Bootloader lock state",
                "ro.boot.flash.locked",
                domain = Domain.SECURITY,
                searchTerms = listOf("bootloader", "unlocked", "oem unlock"),
            ) {
                when (SystemProperties.get("ro.boot.flash.locked")) {
                    "1" -> Probe.Verdict.yes("Locked")
                    "0" -> Probe.Verdict.partial(
                        "Unlocked",
                        "An unlocked bootloader is reported as a partial rather than a " +
                            "failure: it is a deliberate owner choice, and it is " +
                            "visible in key attestation, so stating it plainly is the " +
                            "honest thing to do.",
                    )
                    else -> null
                }
            },
            SystemProperties.verdict(
                probe,
                "Kernel security enforcement (ro.secure)",
                "ro.secure",
                searchTerms = listOf("ro.secure", "adb root"),
            ),
            probe.verdict(
                "Build debuggable",
                "ro.debuggable",
                searchTerms = listOf("debuggable", "eng build", "userdebug"),
                detail = "A debuggable image allows root over adb. Production images " +
                    "report 0.",
            ) {
                when (SystemProperties.get("ro.debuggable")) {
                    "0" -> Probe.Verdict.yes("No — production image")
                    "1" -> Probe.Verdict.partial(
                        "Yes — debuggable image",
                        "This is an engineering or userdebug build.",
                    )
                    else -> null
                }
            },
            probe.value(
                "Build type and tags",
                "ro.build.type / ro.build.tags",
                searchTerms = listOf("user", "userdebug", "release-keys", "test-keys"),
                detail = "release-keys means the image was signed with the OEM's " +
                    "production key; test-keys means it was not.",
            ) {
                val type = SystemProperties.get("ro.build.type")
                val tags = SystemProperties.get("ro.build.tags")
                listOfNotNull(type, tags).joinToString(", ").ifBlank { null }
            },            probe.verdict(
                "SELinux",
                "/sys/fs/selinux/enforce",
                domain = Domain.SECURITY,
                searchTerms = listOf("selinux", "enforcing", "permissive", "mac"),
                detail = "Mandatory access control state, read from the sysfs node " +
                    "that `getenforce` reads.",
            ) {
                when (readFile("/sys/fs/selinux/enforce")) {
                    "1" -> Probe.Verdict.yes("Enforcing")
                    "0" -> Probe.Verdict.partial(
                        "Permissive",
                        "Policy violations are logged rather than blocked.",
                    )
                    else -> if (File("/sys/fs/selinux").exists()) {
                        Probe.Verdict.partial(
                            "Present, state unreadable",
                            "SELinux is mounted but the enforce node is not readable by " +
                                "an app process on this build.",
                        )
                    } else {
                        null
                    }
                }
            },
        ),
    )

    private fun patchLevel() = Section(
        id = "sec-patch-level",
        title = "Security patch level",
        subtitle = "Build.VERSION and vendor properties",
        facts = listOf(
            probe.value(
                "Platform security patch",
                "Build.VERSION.SECURITY_PATCH",
                minApi = 23,
                domain = Domain.SECURITY,
                searchTerms = listOf("patch level", "security patch", "spl"),
            ) { Build.VERSION.SECURITY_PATCH },
            SystemProperties.value(
                probe,
                "Vendor security patch",
                "ro.vendor.build.security_patch",
                detail = "Vendor code is patched separately from the platform, and the " +
                    "two dates often differ.",
                searchTerms = listOf("vendor patch", "vendor spl"),
            ),
            SystemProperties.value(
                probe,
                "Boot image patch level",
                "ro.boot.boot_security_patch_level",
                searchTerms = listOf("boot patch", "kernel patch"),
            ),
            probe.value(
                "Base OS",
                "Build.VERSION.BASE_OS",
                minApi = 23,
                searchTerms = listOf("base os"),
                detail = "Set when this build is a patch on top of another; blank on a " +
                    "primary release.",
            ) { Build.VERSION.BASE_OS },
            probe.flag(
                "Android security model compatible",
                "PackageManager.FEATURE_SECURITY_MODEL_COMPATIBLE",
                minApi = 31,
                domain = Domain.SECURITY,
                searchTerms = listOf("security model", "compatible"),
                supportedText = "Declared",
                unsupportedText = "Queried — not declared by this build",
                detail = "The build declaring that it implements Android's security " +
                    "model as specified rather than a modified variant.",
            ) {
                context.packageManager.hasSystemFeature(
                    PackageManager.FEATURE_SECURITY_MODEL_COMPATIBLE,
                )
            },
        ),
    )

    private fun encryption(): Section {
        val dpm = probe.attempt<DevicePolicyManager?>(null) {
            context.getSystemService(DevicePolicyManager::class.java)
        }
        return Section(
            id = "sec-encryption",
            title = "Storage encryption",
            subtitle = "DevicePolicyManager and crypto properties",
            facts = listOf(
                probe.verdict(
                    "Storage encryption",
                    "DevicePolicyManager.getStorageEncryptionStatus()",
                    minApi = 11,
                    domain = Domain.SECURITY,
                    searchTerms = listOf("encryption", "fbe", "fde", "encrypted"),
                ) { dpm?.let { describeEncryption(it.storageEncryptionStatus) } },
                SystemProperties.value(
                    probe,
                    "Encryption type",
                    "ro.crypto.type",
                    detail = "file: file-based encryption, the modern scheme where each " +
                        "file has its own key. block: whole-partition encryption.",
                    searchTerms = listOf("file based encryption", "fbe", "block", "metadata"),
                ) { type ->
                    when (type) {
                        "file" -> "File-based encryption (file)"
                        "block" -> "Full-disk encryption (block)"
                        "none" -> "None (none)"
                        else -> type
                    }
                },
                SystemProperties.value(
                    probe,
                    "Crypto state",
                    "ro.crypto.state",
                    searchTerms = listOf("crypto state", "encrypted"),
                ),
                probe.notExposedByAndroid(
                    "Metadata encryption and key derivation",
                    "Which cipher and key length protect userdata, and whether the key " +
                        "is derived inside the secure element, are not exposed to apps. " +
                        "The status above is the whole of what Android will report.",
                    domain = Domain.SECURITY,
                    searchTerms = listOf("aes", "cipher", "key length", "adiantum"),
                ),
            ),
        )
    }

    /**
     * The refusals, stated as rows.
     *
     * A blank space where a reader expects a fingerprint or key row invites the
     * assumption that the app failed to look. These rows say the opposite: it was
     * not attempted, here is why, and no amount of permission would change it.
     */
    private fun boundaries() = Section(
        id = "sec-boundaries",
        title = "Deliberately not read",
        subtitle = "Non-exportable by hardware design",
        facts = listOf(
            probe.notExposedByAndroid(
                "Biometric templates",
                "A fingerprint, face or iris template never leaves the secure " +
                    "processor or the sensor's own protected buffer. No API returns one " +
                    "and no permission grants access. This app does not attempt it, and " +
                    "there is nothing for it to attempt.",
                searchTerms = listOf("template", "enrollment", "fingerprint data", "biometric data"),
            ),
            probe.notExposedByAndroid(
                "Private and secret key material",
                "The probe keys above are inspected through KeyInfo, which describes a " +
                    "key without containing it. getEncoded() on an AndroidKeyStore " +
                    "private key returns null by design — the object is a handle to " +
                    "hardware, not the bytes. Nothing in this app reads or displays key " +
                    "material.",
                searchTerms = listOf("private key", "key material", "secret", "getEncoded"),
            ),
            probe.notExposedByAndroid(
                "Hardware identifiers",
                "IMEI, MEID, serial number and SIM identifiers have required " +
                    "READ_PRIVILEGED_PHONE_STATE since API 29, which is not grantable " +
                    "to an installed app. They identify the user's device rather than " +
                    "describe its capability, so they are outside this app's scope in " +
                    "any case.",
                searchTerms = listOf("imei", "serial", "meid", "identifier"),
            ),
            probe.notRead(
                "DRM device unique identifier",
                "MediaDrm.PROPERTY_DEVICE_UNIQUE_ID",
                "the DRM stack will return a stable per-device identifier, which is a " +
                    "tracking token rather than a capability. The DRM lab reads security " +
                    "level, HDCP and session limits and skips this property.",
                searchTerms = listOf("device unique id", "widevine id", "drm identifier"),
            ),
            probe.notExposedByAndroid(
                "Remote integrity verdicts",
                "Play Integrity and similar attestation services need a network round " +
                    "trip to a server. This app has no network permission and makes no " +
                    "connections, so no such verdict is available or sought.",
                searchTerms = listOf("play integrity", "safetynet", "attestation service"),
            ),
        ),
    )

    // ------------------------------------------------------------------ helpers

    private fun describeEncryption(status: Int): Probe.Verdict = when (status) {
        DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER -> Probe.Verdict.yes(
            "Active, per-user keys",
            "Encrypted with a key derived per user — file-based encryption.",
        )
        DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY -> Probe.Verdict.partial(
            "Active with the default key",
            "Encrypted, but with a key not tied to a user credential.",
        )
        DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE -> Probe.Verdict.yes("Active")
        DevicePolicyManager.ENCRYPTION_STATUS_ACTIVATING -> Probe.Verdict.partial(
            "Activating",
            "Encryption is in progress.",
        )
        DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE -> Probe.Verdict.partial(
            "Supported but inactive",
            "The device can encrypt storage and currently does not.",
        )
        DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED -> Probe.Verdict.no(
            "Queried — not supported by this build",
        )
        else -> Probe.Verdict.unknown("Unrecognised status ($status)")
    }

    private fun originName(origin: Int): String = when (origin) {
        KeyProperties.ORIGIN_GENERATED -> "Generated inside the keystore"
        KeyProperties.ORIGIN_IMPORTED -> "Imported"
        KeyProperties.ORIGIN_SECURELY_IMPORTED -> "Securely imported"
        KeyProperties.ORIGIN_UNKNOWN -> "Unknown"
        else -> "Origin $origin"
    }

    /** Turns a keystore constraint outcome into a row the reader can act on. */
    private fun describeConstraint(outcome: KeystoreProbe.Constraint): Probe.Verdict =
        when (outcome) {
            KeystoreProbe.Constraint.HONOURED -> Probe.Verdict.yes(
                "Supported",
                "The keystore created a key with this constraint in force, then the " +
                    "key was deleted.",
            )
            KeystoreProbe.Constraint.REFUSED_NO_CREDENTIAL -> Probe.Verdict.unknown(
                "Cannot be determined — no screen lock set",
                "The keystore refused the key because this device has no PIN, pattern " +
                    "or password, which says nothing about whether the hardware " +
                    "supports the constraint. Set a screen lock and rescan for a real " +
                    "answer.",
            )
            KeystoreProbe.Constraint.REFUSED_UNSUPPORTED -> Probe.Verdict.no(
                "Queried — not supported by this keystore",
                "The keystore rejected the constraint itself rather than the device's " +
                    "configuration.",
            )
            KeystoreProbe.Constraint.INDETERMINATE -> Probe.Verdict.unknown(
                "Probe inconclusive",
                "Key generation failed for a reason that cannot be attributed to " +
                    "either the hardware or the device's configuration.",
            )
        }

    /**
     * A keystore feature's version, exactly as the device declares it.
     *
     * [SystemFeatures.version] reads `FeatureInfo.version` from the platform's own
     * feature list, which carries the precise number. Where that list has no entry --
     * an OEM that declares the feature without a version, which does happen -- the
     * fallback asks `hasSystemFeature(name, version)` for successively higher versions
     * until the platform refuses. That can only establish a lower bound, so the two
     * answers are worded differently and the reader can tell which they are looking at.
     */
    private fun keystoreVersion(pm: PackageManager, feature: String): String? {
        SystemFeatures.version(pm, feature)?.takeIf { it > 0 }?.let {
            return SystemFeatures.keystoreVersionName(it)
        }
        val probed = featureVersion(pm, feature) ?: return null
        return "At least " + SystemFeatures.keystoreVersionName(probed.toInt())
    }

    /**
     * The highest version of [feature] the platform will confirm.
     *
     * `hasSystemFeature(name, version)` answers "at least this version", so the actual
     * number has to be found by walking up. The ceiling is deliberately generous and
     * the walk stops at the first refusal, so this costs a handful of binder calls and
     * cannot loop. Only a fallback: [SystemFeatures.version] is exact where it answers.
     */
    private fun featureVersion(pm: PackageManager, feature: String): String? {
        if (Build.VERSION.SDK_INT < 24) return null
        if (!pm.hasSystemFeature(feature)) return null
        var best = 0
        for (candidate in FEATURE_VERSION_CANDIDATES) {
            if (pm.hasSystemFeature(feature, candidate)) best = candidate else break
        }
        return best.takeIf { it > 0 }?.toString()
    }

    private fun readFile(path: String): String? = try {
        val file = File(path)
        if (file.isFile && file.canRead()) {
            file.readText().trim().takeIf { it.isNotEmpty() }
        } else {
            null
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * The keystore probes, isolated from the detector.
     *
     * Kept in an object of its own for the same reason the other labs isolate their
     * API-gated readers: [KeyInfo.getSecurityLevel] and `setIsStrongBoxBacked` are
     * newer than minSdk, and confining them here keeps the detector class itself
     * verifiable on API 26.
     *
     * Every method deletes its alias in a `finally` block, so a thrown exception
     * cannot leave a key behind. The aliases are prefixed with the package name and
     * the word `probe` so that one surviving a process kill is unmistakable.
     */
    private object KeystoreProbe {

        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS_TEE = "com.devicelab.probe.tee"
        private const val ALIAS_STRONGBOX = "com.devicelab.probe.strongbox"
        private const val ALIAS_ATTESTATION = "com.devicelab.probe.attestation"
        private const val ALIAS_ID_ATTESTATION = "com.devicelab.probe.id-attestation"
        private const val ALIAS_AUTH_BOUND = "com.devicelab.probe.auth-bound"
        private const val ALIAS_UNLOCKED = "com.devicelab.probe.unlocked"

        /** Metadata only: where the key lived, how big it was, where it came from. */
        data class Result(
            val insideSecureHardware: Boolean? = null,
            val securityLevel: Int? = null,
            val keySize: Int? = null,
            val origin: Int? = null,
            val chainLength: Int? = null,
            val strongBoxUnavailable: Boolean = false,
            val unsupported: Boolean = false,
            val failure: String? = null,
        )

        /** How the keystore answered a request for a constrained key. */
        enum class Constraint {
            /** The key was created with the constraint in force. */
            HONOURED,

            /** Refused because this device has no screen lock, not because of hardware. */
            REFUSED_NO_CREDENTIAL,

            /** Refused because the keystore does not implement the constraint. */
            REFUSED_UNSUPPORTED,

            /** Refused for a reason that cannot be classified either way. */
            INDETERMINATE,
        }

        fun run(strongBox: Boolean): Result {
            val alias = if (strongBox) ALIAS_STRONGBOX else ALIAS_TEE
            if (strongBox && Build.VERSION.SDK_INT < 28) {
                return Result(unsupported = true)
            }
            return generate(alias) { builder ->
                if (strongBox && Build.VERSION.SDK_INT >= 28) {
                    builder.setIsStrongBoxBacked(true)
                }
            }
        }

        fun attestation(): Result {
            if (Build.VERSION.SDK_INT < 24) return Result(unsupported = true)
            return generate(ALIAS_ATTESTATION) { builder ->
                builder.setAttestationChallenge(CHALLENGE)
            }
        }

        fun deviceIdAttestation(): Result {
            if (Build.VERSION.SDK_INT < 31) return Result(unsupported = true)
            return generate(ALIAS_ID_ATTESTATION) { builder ->
                builder.setAttestationChallenge(CHALLENGE)
                builder.setDevicePropertiesAttestationIncluded(true)
            }
        }

        /**
         * Whether the secure hardware accepts a key bound to Class 3 biometrics.
         *
         * The interesting case is the refusal. A device with no screen lock set
         * refuses the key for a reason that has nothing to do with whether the
         * hardware supports the feature, so that refusal is classified separately
         * from a genuine "not supported" -- reporting it as unsupported would be
         * exactly the inference this app exists to avoid.
         */
        fun authBoundKey(): Constraint {
            if (Build.VERSION.SDK_INT < 30) return Constraint.INDETERMINATE
            return classify(
                generate(ALIAS_AUTH_BOUND) { builder ->
                    builder.setUserAuthenticationRequired(true)
                    builder.setUserAuthenticationParameters(
                        0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or
                            KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    )
                },
            )
        }

        fun unlockedDeviceKey(): Constraint {
            if (Build.VERSION.SDK_INT < 28) return Constraint.INDETERMINATE
            return classify(
                generate(ALIAS_UNLOCKED) { builder ->
                    builder.setUnlockedDeviceRequired(true)
                },
            )
        }

        /**
         * Separates "the hardware will not do this" from "this device is not set up
         * for it", which are different answers that a boolean would flatten.
         */
        private fun classify(result: Result): Constraint {
            val failure = result.failure ?: return Constraint.HONOURED
            return when {
                CREDENTIAL_REFUSALS.any { failure.contains(it, ignoreCase = true) } ->
                    Constraint.REFUSED_NO_CREDENTIAL
                UNSUPPORTED_REFUSALS.any { failure.contains(it, ignoreCase = true) } ->
                    Constraint.REFUSED_UNSUPPORTED
                else -> Constraint.INDETERMINATE
            }
        }

        /**
         * Generate, inspect, delete.
         *
         * The private key is fetched only to hand to [KeyFactory], which returns a
         * [KeyInfo] describing the key's properties. No byte of the key is read:
         * `getEncoded()` is never called, and on a hardware-backed key it would
         * return null anyway.
         */
        private fun generate(
            alias: String,
            configure: (KeyGenParameterSpec.Builder) -> Unit,
        ): Result {
            var store: KeyStore? = null
            return try {
                store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
                if (store.containsAlias(alias)) store.deleteEntry(alias)

                val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                configure(builder)

                val generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC,
                    KEYSTORE,
                )
                generator.initialize(builder.build())
                generator.generateKeyPair()

                val privateKey = store.getKey(alias, null) as? PrivateKey
                    ?: return Result(failure = "key absent after generation")
                val factory = KeyFactory.getInstance(privateKey.algorithm, KEYSTORE)
                val info = factory.getKeySpec(privateKey, KeyInfo::class.java)
                val chain = store.getCertificateChain(alias)

                Result(
                    insideSecureHardware = SecurityLevelReader.insideSecureHardware(info),
                    securityLevel = SecurityLevelReader.securityLevel(info),
                    keySize = info.keySize,
                    origin = info.origin,
                    chainLength = chain?.size,
                )
            } catch (t: Throwable) {
                val name = t.javaClass.simpleName
                Result(
                    strongBoxUnavailable = t.javaClass.name.contains("StrongBoxUnavailable"),
                    failure = name + (t.message?.let { ": ${it.take(140)}" } ?: ""),
                )
            } finally {
                // Deleting here rather than at the end of the happy path means a
                // vendor keystore that throws mid-generation still leaves nothing.
                try {
                    store?.takeIf { it.containsAlias(alias) }?.deleteEntry(alias)
                } catch (ignored: Throwable) {
                    // Nothing further can be done; the alias is inert either way.
                }
            }
        }

        /** A fixed, non-secret challenge. Its content is irrelevant to the probe. */
        private val CHALLENGE = "devicelab-capability-probe".toByteArray()

        /**
         * Exception text that means "this device is not configured for it".
         *
         * The keystore signals a missing screen lock by throwing rather than by
         * returning anything, and the wording has changed across versions, so the
         * match is on several phrasings plus the exception type itself.
         */
        private val CREDENTIAL_REFUSALS = listOf(
            "secure lock screen",
            "must be enrolled",
            "no credential",
            "user authentication",
            "IllegalStateException",
        )

        /** Exception text that means the keystore does not implement the request. */
        private val UNSUPPORTED_REFUSALS = listOf(
            "not supported",
            "unsupported",
            "InvalidAlgorithmParameterException",
            "UnsupportedOperationException",
            "ProviderException",
        )
    }

    /** [KeyInfo.getSecurityLevel] is API 31; [KeyInfo] itself is API 23. */
    private object SecurityLevelReader {

        fun insideSecureHardware(info: KeyInfo): Boolean? = try {
            if (Build.VERSION.SDK_INT >= 31) {
                when (info.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_SOFTWARE -> false
                    KeyProperties.SECURITY_LEVEL_UNKNOWN -> null
                    else -> true
                }
            } else {
                @Suppress("DEPRECATION")
                info.isInsideSecureHardware
            }
        } catch (t: Throwable) {
            null
        }

        fun securityLevel(info: KeyInfo): Int? = try {
            if (Build.VERSION.SDK_INT >= 31) info.securityLevel else null
        } catch (t: Throwable) {
            null
        }
    }

    private data class StrengthSpec(
        val label: String,
        val authenticators: Int,
        val constant: String,
        val minApi: Int,
        val detail: String,
        val searchTerms: List<String>,
    )

    private companion object {

        /**
         * The three authenticator classes, in descending order of trust.
         *
         * `minApi` is 23 throughout because the compatibility layer answers on every
         * level this app supports: it delegates to the platform BiometricManager from
         * API 29 and to FingerprintManager below it. The class-3/class-2 split is a
         * platform concept from API 29, but a Class 3 fingerprint on API 26 is still
         * correctly reported as strong, which is why the row is not gated at 29.
         */
        val STRENGTHS = listOf(
            StrengthSpec(
                label = "Class 3 biometric (BIOMETRIC_STRONG)",
                authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG,
                constant = "Authenticators.BIOMETRIC_STRONG",
                minApi = 23,
                detail = "The only class the keystore will let guard a key. Requires a " +
                    "false-acceptance rate below 1 in 50,000 and a hardware-backed " +
                    "integration.",
                searchTerms = listOf("class 3", "strong", "biometric_strong", "fingerprint", "key"),
            ),
            StrengthSpec(
                label = "Class 2 biometric (BIOMETRIC_WEAK)",
                authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK,
                constant = "Authenticators.BIOMETRIC_WEAK",
                minApi = 23,
                detail = "Good enough to unlock the device and to gate an app, not to " +
                    "release a key. Many camera-only face implementations sit here.",
                searchTerms = listOf("class 2", "weak", "biometric_weak", "face"),
            ),
            StrengthSpec(
                label = "Device credential",
                authenticators = BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                constant = "Authenticators.DEVICE_CREDENTIAL",
                minApi = 30,
                detail = "PIN, pattern or password as an authenticator in its own right. " +
                    "The compatibility layer only accepts this flag on its own from " +
                    "API 30.",
                searchTerms = listOf("pin", "pattern", "password", "device credential"),
            ),
        )

        /** Keystore features CTS requires a device to declare truthfully. */
        val KEYSTORE_FEATURES: List<Pair<String, Pair<String, Int>>> = listOf(
            "Hardware keystore" to (PackageManager.FEATURE_HARDWARE_KEYSTORE to 31),
            "StrongBox keystore" to (PackageManager.FEATURE_STRONGBOX_KEYSTORE to 28),
            "App attest key" to (PackageManager.FEATURE_KEYSTORE_APP_ATTEST_KEY to 31),
            "Limited-use keys" to (PackageManager.FEATURE_KEYSTORE_LIMITED_USE_KEY to 31),
            "Single-use keys" to (PackageManager.FEATURE_KEYSTORE_SINGLE_USE_KEY to 31),
        )

        /**
         * Versions to test `hasSystemFeature(name, version)` against.
         *
         * These are the numbers KeyMint and Keymaster actually report, in ascending
         * order. Walking a fixed list rather than counting up keeps the number of
         * binder calls small and bounded.
         */
        val FEATURE_VERSION_CANDIDATES = listOf(20, 30, 40, 41, 100, 200, 300)
    }
}
