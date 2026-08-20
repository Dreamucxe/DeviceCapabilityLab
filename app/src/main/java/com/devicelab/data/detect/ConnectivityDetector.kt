package com.devicelab.data.detect

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Build
import android.telephony.TelephonyManager
import com.devicelab.core.detect.Probe
import com.devicelab.core.model.Absent
import com.devicelab.core.model.Domain
import com.devicelab.core.model.Lab
import com.devicelab.core.model.LabReport
import com.devicelab.core.model.Section
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Radios: Wi-Fi, Bluetooth, NFC, UWB and the cellular modem's declared capability.
 *
 * The design decision that shapes this whole lab is what it refuses to read. A Wi-Fi
 * SSID, a BSSID, a scan result, a paired device's name, an IMEI or a phone number are
 * all obtainable -- with location or phone permissions -- and all of them are the user's
 * information rather than the device's capability. None of them is requested and none is
 * displayed. What is shown is what the hardware can do: which bands the radio has, which
 * 802.11 generations it supports, which Bluetooth LE features the controller implements.
 *
 * `ACCESS_WIFI_STATE` and `ACCESS_NETWORK_STATE` are declared because most `WifiManager`
 * capability getters require them. Both are install-time permissions with no prompt and
 * no access to user data. Bluetooth's runtime permissions are deliberately *not*
 * requested, so the few rows that need them say so instead of showing a wrong answer.
 */
class ConnectivityDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val probe: Probe,
) : CapabilityDetector {

    override val lab = Lab.CONNECTIVITY

    override suspend fun detect(): LabReport {
        val pm = context.packageManager
        val wifi = probe.attempt<WifiManager?>(null) {
            context.getSystemService(WifiManager::class.java)
        }
        val bluetooth = probe.attempt<BluetoothAdapter?>(null) {
            context.getSystemService(BluetoothManager::class.java)?.adapter
        }
        val telephony = probe.attempt<TelephonyManager?>(null) {
            context.getSystemService(TelephonyManager::class.java)
        }

        return LabReport(
            lab = lab,
            sections = listOfNotNull(
                wifiBands(pm, wifi),
                wifiStandards(wifi),
                wifiSecurity(wifi),
                wifiConcurrency(wifi),
                wifiDiscovery(pm, wifi),
                bluetoothCore(pm, bluetooth),
                bluetoothLe(pm, bluetooth),
                nfc(pm),
                uwbAndPrecision(pm),
                cellular(pm, telephony),
                activeLink(),
                privacy(),
            ),
            notes = listOf(
                "No SSID, BSSID, scan result, paired-device name or SIM identifier is read. " +
                    "Those need location or phone permissions and describe the user, not " +
                    "the hardware.",
            ),
        )
    }

    // ---- Wi-Fi -------------------------------------------------------------

    private fun wifiBands(pm: PackageManager, wifi: WifiManager?) = Section(
        id = "wifi-bands",
        title = "Wi-Fi bands",
        subtitle = "WifiManager band support queries",
        facts = listOf(
            probe.flag(
                "Wi-Fi",
                "PackageManager.FEATURE_WIFI",
                domain = Domain.CONNECTIVITY,
                searchTerms = listOf("wifi", "wi-fi", "wlan"),
            ) { pm.hasSystemFeature(PackageManager.FEATURE_WIFI) },
            probe.flag(
                "2.4 GHz band",
                "WifiManager.is24GHzBandSupported()",
                minApi = 31,
                domain = Domain.CONNECTIVITY,
                searchTerms = listOf("2.4ghz", "2.4 ghz", "band"),
            ) { wifi?.is24GHzBandSupported },
            probe.flag(
                "5 GHz band",
                "WifiManager.is5GHzBandSupported()",
                minApi = 21,
                domain = Domain.CONNECTIVITY,
                searchTerms = listOf("5ghz", "5 ghz", "band"),
            ) { wifi?.is5GHzBandSupported },
            probe.flag(
                "6 GHz band",
                "WifiManager.is6GHzBandSupported()",
                minApi = 30,
                domain = Domain.CONNECTIVITY,
                searchTerms = listOf("6ghz", "6 ghz", "wifi 6e", "wi-fi 6e"),
                detail = "The band that makes Wi-Fi 6E and Wi-Fi 7 useful. Reported by the " +
                    "radio, not inferred from the marketing name.",
            ) { wifi?.is6GHzBandSupported },
            probe.flag(
                "60 GHz band",
                "WifiManager.is60GHzBandSupported()",
                minApi = 31,
                searchTerms = listOf("60ghz", "wigig", "802.11ad"),
            ) { wifi?.is60GHzBandSupported },
        ),
    )

    /**
     * 802.11 generations, asked one at a time.
     *
     * `isWifiStandardSupported` is the only honest source for this. Deriving "Wi-Fi 6"
     * from a chipset name or a marketing string is exactly the guesswork this app
     * exists to avoid, and below API 30 the platform simply cannot answer -- which the
     * rows say rather than hide.
     */
    private fun wifiStandards(wifi: WifiManager?) = Section(
        id = "wifi-standards",
        title = "Wi-Fi standards",
        subtitle = "WifiManager.isWifiStandardSupported() — Android 11 and later",
        facts = WIFI_STANDARDS.map { spec ->
            probe.flag(
                spec.label,
                "isWifiStandardSupported(${spec.constant})",
                minApi = spec.minApi,
                domain = Domain.CONNECTIVITY,
                searchTerms = spec.searchTerms,
            ) { wifi?.isWifiStandardSupported(spec.value) }
        } + listOf(
            probe.notExposedByAndroid(
                "Wi-Fi chipset name",
                "There is no API for the Wi-Fi chipset's model. Vendor system properties " +
                    "sometimes carry it, but they are inconsistent between OEMs and are not " +
                    "a capability, so this app does not present one as fact.",
                searchTerms = listOf("wifi chipset", "wlan chip"),
            ),
            probe.notExposedByAndroid(
                "Current link's Wi-Fi standard",
                "WifiInfo.getWifiStandard() reports the generation of the connection you " +
                    "are on, but obtaining WifiInfo requires a location permission because " +
                    "it also reveals the network you are attached to. This app asks the " +
                    "radio what it supports instead of asking the router what it agreed to.",
                searchTerms = listOf("link standard", "connected standard"),
            ),
        ),
    )

    private fun wifiSecurity(wifi: WifiManager?) = Section(
        id = "wifi-security",
        title = "Wi-Fi security",
        subtitle = "WPA3, OWE and provisioning support",
        facts = listOf(
            probe.flag(
                "WPA3-Personal (SAE)",
                "WifiManager.isWpa3SaeSupported()",
                minApi = 29,
                domain = Domain.CONNECTIVITY,
                searchTerms = listOf("wpa3", "sae", "security"),
            ) { wifi?.isWpa3SaeSupported },
            probe.flag(
                "WPA3 SAE Hash-to-Element",
                "WifiManager.isWpa3SaeH2eSupported()",
                minApi = 31,
                searchTerms = listOf("wpa3", "h2e", "hash to element"),
            ) { wifi?.isWpa3SaeH2eSupported },
            probe.flag(
                "WPA3 SAE public key",
                "WifiManager.isWpa3SaePublicKeySupported()",
                minApi = 31,
                searchTerms = listOf("wpa3", "public key"),
            ) { wifi?.isWpa3SaePublicKeySupported },
            probe.flag(
                "WPA3-Enterprise 192-bit (Suite B)",
                "WifiManager.isWpa3SuiteBSupported()",
                minApi = 29,
                searchTerms = listOf("wpa3 enterprise", "suite b", "192-bit"),
            ) { wifi?.isWpa3SuiteBSupported },
            probe.flag(
                "Enhanced Open (OWE)",
                "WifiManager.isEnhancedOpenSupported()",
                minApi = 29,
                searchTerms = listOf("owe", "enhanced open", "opportunistic"),
            ) { wifi?.isEnhancedOpenSupported },
            probe.flag(
                "WAPI",
                "WifiManager.isWapiSupported()",
                minApi = 30,
                searchTerms = listOf("wapi"),
            ) { wifi?.isWapiSupported },
            probe.flag(
                "Wi-Fi Easy Connect (DPP)",
                "WifiManager.isEasyConnectSupported()",
                minApi = 29,
                searchTerms = listOf("dpp", "easy connect", "qr provisioning"),
            ) { wifi?.isEasyConnectSupported },
            probe.flag(
                "Easy Connect enrollee responder",
                "WifiManager.isEasyConnectEnrolleeResponderModeSupported()",
                minApi = 31,
                searchTerms = listOf("dpp", "enrollee"),
            ) { wifi?.isEasyConnectEnrolleeResponderModeSupported },
            probe.flag(
                "Easy Connect DPP AKM",
                "WifiManager.isEasyConnectDppAkmSupported()",
                minApi = 33,
                searchTerms = listOf("dpp akm"),
            ) { wifi?.isEasyConnectDppAkmSupported },
            probe.flag(
                "Trust on first use",
                "WifiManager.isTrustOnFirstUseSupported()",
                minApi = 33,
                searchTerms = listOf("tofu", "trust on first use", "enterprise"),
            ) { wifi?.isTrustOnFirstUseSupported },
            probe.flag(
                "TLS 1.3 for enterprise Wi-Fi",
                "WifiManager.isTlsV13Supported()",
                minApi = 34,
                searchTerms = listOf("tls 1.3", "eap"),
            ) { wifi?.isTlsV13Supported },
            probe.flag(
                "Minimum TLS version selectable",
                "WifiManager.isTlsMinimumVersionSupported()",
                minApi = 34,
                searchTerms = listOf("tls"),
            ) { wifi?.isTlsMinimumVersionSupported },
            probe.flag(
                "Decorated identity prefix",
                "WifiManager.isDecoratedIdentitySupported()",
                minApi = 31,
                searchTerms = listOf("decorated identity", "roaming consortium"),
            ) { wifi?.isDecoratedIdentitySupported },
        ),
    )

    private fun wifiConcurrency(wifi: WifiManager?) = Section(
        id = "wifi-concurrency",
        title = "Wi-Fi concurrency",
        subtitle = "Simultaneous station, hotspot and multi-link operation",
        facts = listOf(
            probe.flag(
                "Station + hotspot at once",
                "WifiManager.isStaApConcurrencySupported()",
                minApi = 30,
                searchTerms = listOf("sta ap", "hotspot", "tethering", "concurrency"),
                detail = "Whether the radio can stay connected to a network while also " +
                    "running a hotspot.",
            ) { wifi?.isStaApConcurrencySupported },
            probe.flag(
                "Dual station for local-only links",
                "WifiManager.isStaConcurrencyForLocalOnlyConnectionsSupported()",
                minApi = 31,
                searchTerms = listOf("dual sta", "concurrency", "local only"),
            ) { wifi?.isStaConcurrencyForLocalOnlyConnectionsSupported },
            probe.flag(
                "Dual station for multi-internet",
                "WifiManager.isStaConcurrencyForMultiInternetSupported()",
                minApi = 33,
                searchTerms = listOf("multi internet", "dual sta"),
            ) { wifi?.isStaConcurrencyForMultiInternetSupported },
            probe.flag(
                "Make-before-break switching",
                "WifiManager.isMakeBeforeBreakWifiSwitchingSupported()",
                minApi = 31,
                searchTerms = listOf("make before break", "seamless roaming"),
                detail = "Connecting to the next network before dropping the current one, " +
                    "which is what makes roaming seamless.",
            ) { wifi?.isMakeBeforeBreakWifiSwitchingSupported },
            probe.flag(
                "Bridged access point",
                "WifiManager.isBridgedApConcurrencySupported()",
                minApi = 31,
                searchTerms = listOf("bridged ap", "dual band hotspot"),
            ) { wifi?.isBridgedApConcurrencySupported },
            probe.flag(
                "Station + bridged access point",
                "WifiManager.isStaBridgedApConcurrencySupported()",
                minApi = 31,
                searchTerms = listOf("sta bridged ap"),
            ) { wifi?.isStaBridgedApConcurrencySupported },
            probe.flag(
                "Dual-band simultaneous",
                "WifiManager.isDualBandSimultaneousSupported()",
                minApi = 34,
                searchTerms = listOf("dbs", "dual band simultaneous"),
            ) { wifi?.isDualBandSimultaneousSupported },
            probe.flag(
                "TID-to-link mapping negotiation",
                "WifiManager.isTidToLinkMappingNegotiationSupported()",
                minApi = 34,
                searchTerms = listOf("mlo", "multi-link", "wifi 7", "tid to link"),
                detail = "Part of Wi-Fi 7 multi-link operation: negotiating which traffic " +
                    "classes travel over which link.",
            ) { wifi?.isTidToLinkMappingNegotiationSupported },
            probe.flag(
                "TDLS (direct peer link)",
                "WifiManager.isTdlsSupported()",
                minApi = 21,
                searchTerms = listOf("tdls"),
            ) { wifi?.isTdlsSupported },
        ),
    )

    private fun wifiDiscovery(pm: PackageManager, wifi: WifiManager?) = Section(
        id = "wifi-discovery",
        title = "Wi-Fi discovery & ranging",
        subtitle = "Direct, Aware, RTT and Passpoint",
        facts = listOf(
            probe.flag(
                "Wi-Fi Direct (P2P)",
                "PackageManager.FEATURE_WIFI_DIRECT",
                minApi = 14,
                domain = Domain.CONNECTIVITY,
                searchTerms = listOf("wifi direct", "p2p", "miracast"),
            ) { pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) },
            probe.flag(
                "Wi-Fi P2P (radio query)",
                "WifiManager.isP2pSupported()",
                minApi = 21,
                searchTerms = listOf("p2p"),
            ) { wifi?.isP2pSupported },
            probe.flag(
                "Wi-Fi Aware (NAN)",
                "PackageManager.FEATURE_WIFI_AWARE",
                minApi = 26,
                domain = Domain.CONNECTIVITY,
                searchTerms = listOf("wifi aware", "nan", "neighbour awareness"),
            ) { pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) },
            probe.flag(
                "Wi-Fi RTT ranging (802.11mc)",
                "PackageManager.FEATURE_WIFI_RTT",
                minApi = 28,
                domain = Domain.CONNECTIVITY,
                searchTerms = listOf("rtt", "802.11mc", "ranging", "indoor positioning"),
            ) { pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT) },
            probe.flag(
                "Device-to-AP RTT",
                "WifiManager.isDeviceToApRttSupported()",
                minApi = 21,
                searchTerms = listOf("rtt", "ranging"),
            ) { wifi?.isDeviceToApRttSupported },
            probe.flag(
                "Passpoint (Hotspot 2.0)",
                "PackageManager.FEATURE_WIFI_PASSPOINT",
                minApi = 27,
                searchTerms = listOf("passpoint", "hotspot 2.0"),
            ) { pm.hasSystemFeature(PackageManager.FEATURE_WIFI_PASSPOINT) },
            probe.flag(
                "Passpoint terms & conditions",
                "WifiManager.isPasspointTermsAndConditionsSupported()",
                minApi = 31,
                searchTerms = listOf("passpoint"),
            ) { wifi?.isPasspointTermsAndConditionsSupported },
            probe.flag(
                "Wi-Fi Display R2",
                "WifiManager.isWifiDisplayR2Supported()",
                minApi = 31,
                searchTerms = listOf("wifi display", "miracast", "wireless display"),
            ) { wifi?.isWifiDisplayR2Supported },
            probe.flag(
                "Preferred network offload",
                "WifiManager.isPreferredNetworkOffloadSupported()",
                minApi = 21,
                searchTerms = listOf("pno", "offload", "background scan"),
                detail = "Scanning for known networks in the Wi-Fi chip while the CPU " +
                    "sleeps.",
            ) { wifi?.isPreferredNetworkOffloadSupported },
            probe.flag(
                "Enhanced power reporting",
                "WifiManager.isEnhancedPowerReportingSupported()",
                minApi = 21,
                searchTerms = listOf("power reporting", "link layer stats"),
            ) { wifi?.isEnhancedPowerReportingSupported },
            probe.value(
                "Network suggestions per app",
                "WifiManager.getMaxNumberOfNetworkSuggestionsPerApp()",
                minApi = 29,
                searchTerms = listOf("network suggestion"),
            ) { wifi?.maxNumberOfNetworkSuggestionsPerApp?.takeIf { it > 0 }?.toString() },
            probe.value(
                "Channels per specifier request",
                "WifiManager.getMaxNumberOfChannelsPerNetworkSpecifierRequest()",
                minApi = 34,
            ) {
                wifi?.maxNumberOfChannelsPerNetworkSpecifierRequest
                    ?.takeIf { it > 0 }?.toString()
            },
        ),
    )

    // ---- Bluetooth ---------------------------------------------------------

    /**
     * Bluetooth, and an explicit statement about the version number.
     *
     * There is no API that returns "Bluetooth 5.3". The controller's supported features
     * are queryable, and those features are what a version number is shorthand for, so
     * that is what is shown -- with a row saying why the version itself is absent.
     */
    private fun bluetoothCore(pm: PackageManager, adapter: BluetoothAdapter?) = Section(
        id = "bluetooth",
        title = "Bluetooth",
        subtitle = "BluetoothAdapter controller capabilities",
        facts = listOf(
            probe.flag(
                "Bluetooth",
                "PackageManager.FEATURE_BLUETOOTH",
                domain = Domain.CONNECTIVITY,
                searchTerms = listOf("bluetooth", "bt"),
            ) { pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) },
            probe.flag(
                "Bluetooth Low Energy",
                "PackageManager.FEATURE_BLUETOOTH_LE",
                minApi = 18,
                domain = Domain.CONNECTIVITY,
                searchTerms = listOf("ble", "bluetooth le", "low energy"),
            ) { pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) },
            probe.flag(
                "Adapter present",
                "BluetoothManager.getAdapter()",
                minApi = 18,
                supportedText = "Yes",
                unsupportedText = "No adapter reported",
            ) { adapter != null },
            probe.notExposedByAndroid(
                "Bluetooth version",
                "No public API returns the controller's Bluetooth Core version. The rows " +
                    "below query the individual features a version number stands for -- LE " +
                    "2M PHY and extended advertising arrived with 5.0, periodic " +
                    "advertising with 5.0 as well -- which is a stronger answer than a " +
                    "number read from an unreliable system property.",
                domain = Domain.CONNECTIVITY,
                searchTerms = listOf("bluetooth version", "bluetooth 5", "bt 5.3"),
            ),
            probe.value(
                "Maximum connected audio devices",
                "BluetoothAdapter.getMaxConnectedAudioDevices()",
                minApi = 33,
                absentText = Absent.UNAVAILABLE,
                searchTerms = listOf("multipoint", "connected audio"),
                detail = "Requires the BLUETOOTH_CONNECT runtime permission, which this " +
                    "app does not request.",
            ) { null },
            probe.notExposedByAndroid(
                "Adapter state, name and paired devices",
                "Reading whether Bluetooth is on, the adapter's name, or the list of " +
                    "paired devices needs the BLUETOOTH_CONNECT runtime permission. Those " +
                    "are the user's devices rather than this device's capability, so the " +
                    "permission is not requested.",
                searchTerms = listOf("paired", "bonded", "bluetooth name"),
            ),
        ),
    )

    private fun bluetoothLe(pm: PackageManager, adapter: BluetoothAdapter?): Section {
        val hasLe = probe.attempt(false) {
            pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        }
        return Section(
            id = "bluetooth-le",
            title = "Bluetooth LE features",
            subtitle = if (hasLe) {
                "Controller feature queries — no permission required"
            } else {
                "This device reports no Bluetooth LE support"
            },
            facts = listOf(
                probe.flag(
                    "LE 2M PHY",
                    "BluetoothAdapter.isLe2MPhySupported()",
                    minApi = 26,
                    domain = Domain.CONNECTIVITY,
                    searchTerms = listOf("2m phy", "bluetooth 5", "le high speed"),
                    detail = "The doubled-rate physical layer introduced with Bluetooth 5.0.",
                ) { adapter?.isLe2MPhySupported },
                probe.flag(
                    "LE Coded PHY",
                    "BluetoothAdapter.isLeCodedPhySupported()",
                    minApi = 26,
                    searchTerms = listOf("coded phy", "long range", "bluetooth 5"),
                    detail = "The long-range coded physical layer, also from Bluetooth 5.0.",
                ) { adapter?.isLeCodedPhySupported },
                probe.flag(
                    "LE extended advertising",
                    "BluetoothAdapter.isLeExtendedAdvertisingSupported()",
                    minApi = 26,
                    searchTerms = listOf("extended advertising", "bluetooth 5"),
                ) { adapter?.isLeExtendedAdvertisingSupported },
                probe.flag(
                    "LE periodic advertising",
                    "BluetoothAdapter.isLePeriodicAdvertisingSupported()",
                    minApi = 26,
                    searchTerms = listOf("periodic advertising"),
                ) { adapter?.isLePeriodicAdvertisingSupported },
                probe.flag(
                    "Multiple advertisement",
                    "BluetoothAdapter.isMultipleAdvertisementSupported()",
                    minApi = 21,
                    searchTerms = listOf("multiple advertisement", "beacon"),
                ) { adapter?.isMultipleAdvertisementSupported },
                probe.flag(
                    "Offloaded scan filtering",
                    "BluetoothAdapter.isOffloadedFilteringSupported()",
                    minApi = 21,
                    searchTerms = listOf("offloaded filtering", "hardware filter"),
                    detail = "Filtering advertisements in the controller so the CPU can " +
                        "stay asleep.",
                ) { adapter?.isOffloadedFilteringSupported },
                probe.flag(
                    "Offloaded scan batching",
                    "BluetoothAdapter.isOffloadedScanBatchingSupported()",
                    minApi = 21,
                    searchTerms = listOf("scan batching"),
                ) { adapter?.isOffloadedScanBatchingSupported },
                probe.value(
                    "Maximum advertising data length",
                    "BluetoothAdapter.getLeMaximumAdvertisingDataLength()",
                    minApi = 26,
                    searchTerms = listOf("advertising data length"),
                    detail = "31 bytes is the legacy limit; a larger figure means extended " +
                        "advertising is genuinely available.",
                ) {
                    adapter?.leMaximumAdvertisingDataLength
                        ?.takeIf { it > 0 }
                        ?.let { "$it bytes" }
                },
                probe.notExposedByAndroid(
                    "LE Audio and LC3",
                    "BluetoothAdapter.isLeAudioSupported() exists but is annotated for " +
                        "system use and is not callable by an installed app. The audio lab " +
                        "reports whether the platform models LE Audio device types, which " +
                        "is the part a third-party app can legitimately see.",
                    searchTerms = listOf("le audio", "lc3", "auracast"),
                ),
            ),
        )
    }

    // ---- NFC, UWB, cellular -------------------------------------------------

    private fun nfc(pm: PackageManager): Section {
        val adapter = probe.attempt<NfcAdapter?>(null) { NfcAdapter.getDefaultAdapter(context) }
        return Section(
            id = "nfc",
            title = "NFC",
            subtitle = "PackageManager and NfcAdapter",
            facts = listOf(
                probe.flag(
                    "NFC",
                    "PackageManager.FEATURE_NFC",
                    domain = Domain.CONNECTIVITY,
                    searchTerms = listOf("nfc", "tap to pay"),
                ) { pm.hasSystemFeature(PackageManager.FEATURE_NFC) },
                probe.flag(
                    "Host card emulation",
                    "PackageManager.FEATURE_NFC_HOST_CARD_EMULATION",
                    minApi = 19,
                    searchTerms = listOf("hce", "card emulation", "payments"),
                ) { pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION) },
                probe.flag(
                    "Host card emulation (NFC-F)",
                    "PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF",
                    minApi = 24,
                    searchTerms = listOf("hcef", "felica"),
                ) { pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF) },
                probe.flag(
                    "Secure element (eSE)",
                    "PackageManager.FEATURE_NFC_OFF_HOST_CARD_EMULATION_ESE",
                    minApi = 29,
                    domain = Domain.SECURITY,
                    searchTerms = listOf("ese", "secure element", "embedded se"),
                ) {
                    pm.hasSystemFeature(
                        PackageManager.FEATURE_NFC_OFF_HOST_CARD_EMULATION_ESE,
                    )
                },
                probe.flag(
                    "Secure element (UICC)",
                    "PackageManager.FEATURE_NFC_OFF_HOST_CARD_EMULATION_UICC",
                    minApi = 29,
                    searchTerms = listOf("uicc", "sim secure element"),
                ) {
                    pm.hasSystemFeature(
                        PackageManager.FEATURE_NFC_OFF_HOST_CARD_EMULATION_UICC,
                    )
                },
                probe.flag(
                    "Android Beam",
                    "PackageManager.FEATURE_NFC_BEAM",
                    minApi = 29,
                    searchTerms = listOf("beam", "ndef push"),
                    detail = "Removed from Android 10 onwards on most devices; a false " +
                        "answer here is normal on a modern build.",
                ) { pm.hasSystemFeature(PackageManager.FEATURE_NFC_BEAM) },
                probe.flag(
                    "Secure NFC",
                    "NfcAdapter.isSecureNfcSupported()",
                    minApi = 29,
                    domain = Domain.SECURITY,
                    searchTerms = listOf("secure nfc", "screen locked"),
                    detail = "Whether the device can require an unlocked screen before NFC " +
                        "transactions are allowed.",
                ) { if (Build.VERSION.SDK_INT >= 29) adapter?.isSecureNfcSupported else null },
            ),
        )
    }

    private fun uwbAndPrecision(pm: PackageManager) = Section(
        id = "uwb",
        title = "Ultra-wideband & precision ranging",
        facts = listOf(
            probe.flag(
                "Ultra-wideband",
                "PackageManager.hasSystemFeature(\"android.hardware.uwb\")",
                minApi = 31,
                domain = Domain.CONNECTIVITY,
                searchTerms = listOf("uwb", "ultra wideband", "precision finding"),
                detail = "The feature string predates the PackageManager.FEATURE_UWB " +
                    "constant, so it is queried by name to give a true answer on Android " +
                    "12 and 13 devices that do have the hardware.",
            ) { pm.hasSystemFeature(FEATURE_UWB_STRING) },
            probe.flag(
                "LoWPAN transport",
                "NetworkCapabilities.TRANSPORT_LOWPAN",
                minApi = 27,
                searchTerms = listOf("lowpan", "thread", "802.15.4"),
                detail = "Whether this Android version models a low-power mesh transport. " +
                    "Presence of the constant is not proof of a radio -- the active-link " +
                    "section reports what is actually connected.",
            ) { Build.VERSION.SDK_INT >= 27 },
            probe.notExposedByAndroid(
                "Thread / Matter radio",
                "Android 14 added a Thread network API restricted to system components. " +
                    "An installed app cannot query whether an 802.15.4 radio is present.",
                searchTerms = listOf("thread", "matter", "border router"),
            ),
        ),
    )

    /**
     * The modem's declared capability, with no SIM or subscriber data.
     *
     * Everything here comes from feature flags and permission-free `TelephonyManager`
     * getters. Network operator, signal strength, IMEI, subscriber ID and phone number
     * are all deliberately absent -- they identify the user, not the hardware.
     */
    private fun cellular(pm: PackageManager, tm: TelephonyManager?) = Section(
        id = "cellular",
        title = "Cellular",
        subtitle = "Declared modem capability — no SIM or subscriber data is read",
        facts = listOf(
            probe.flag(
                "Telephony",
                "PackageManager.FEATURE_TELEPHONY",
                domain = Domain.CONNECTIVITY,
                searchTerms = listOf("cellular", "telephony", "modem"),
            ) { pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) },
            probe.flag(
                "GSM radio",
                "PackageManager.FEATURE_TELEPHONY_GSM",
                minApi = 7,
                searchTerms = listOf("gsm"),
            ) { pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_GSM) },
            probe.flag(
                "CDMA radio",
                "PackageManager.FEATURE_TELEPHONY_CDMA",
                minApi = 7,
                searchTerms = listOf("cdma"),
            ) { pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CDMA) },
            probe.flag(
                "Radio access",
                "PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS",
                minApi = 33,
                searchTerms = listOf("radio access"),
            ) { pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS) },
            probe.flag(
                "Voice calling",
                "PackageManager.FEATURE_TELEPHONY_CALLING",
                minApi = 33,
                searchTerms = listOf("calling", "voice"),
            ) { pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING) },
            probe.flag(
                "Cellular data",
                "PackageManager.FEATURE_TELEPHONY_DATA",
                minApi = 33,
                searchTerms = listOf("mobile data"),
            ) { pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_DATA) },
            probe.flag(
                "Messaging",
                "PackageManager.FEATURE_TELEPHONY_MESSAGING",
                minApi = 33,
                searchTerms = listOf("sms", "messaging"),
            ) { pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING) },
            probe.flag(
                "IMS (VoLTE / VoWiFi framework)",
                "PackageManager.FEATURE_TELEPHONY_IMS",
                minApi = 29,
                searchTerms = listOf("ims", "volte", "vowifi"),
            ) { pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_IMS) },
            probe.flag(
                "eUICC (eSIM)",
                "PackageManager.FEATURE_TELEPHONY_EUICC",
                minApi = 28,
                searchTerms = listOf("esim", "euicc"),
            ) { pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_EUICC) },
            probe.flag(
                "eUICC multiple enabled profiles",
                "PackageManager.FEATURE_TELEPHONY_EUICC_MEP",
                minApi = 33,
                searchTerms = listOf("mep", "dual esim"),
            ) { pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_EUICC_MEP) },
            probe.flag(
                "Subscription management",
                "PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION",
                minApi = 33,
                searchTerms = listOf("subscription"),
            ) { pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION) },
            probe.value(
                "Modems supported",
                "TelephonyManager.getSupportedModemCount()",
                minApi = 30,
                searchTerms = listOf("dual sim", "modem count"),
            ) { tm?.supportedModemCount?.takeIf { it > 0 }?.toString() },
            probe.value(
                "Modems active",
                "TelephonyManager.getActiveModemCount()",
                minApi = 30,
                searchTerms = listOf("active modem", "dual sim"),
            ) { tm?.activeModemCount?.takeIf { it > 0 }?.toString() },
            probe.flag(
                "Voice capable",
                "TelephonyManager.isVoiceCapable()",
                minApi = 22,
                searchTerms = listOf("voice capable"),
            ) { tm?.isVoiceCapable },
            probe.flag(
                "SMS capable",
                "TelephonyManager.isSmsCapable()",
                minApi = 21,
                searchTerms = listOf("sms capable"),
            ) { tm?.isSmsCapable },
            probe.flag(
                "Data capable",
                "TelephonyManager.isDataCapable()",
                minApi = 31,
                searchTerms = listOf("data capable"),
            ) { tm?.isDataCapable },
            probe.flag(
                "Simultaneous voice and data",
                "TelephonyManager.isConcurrentVoiceAndDataSupported()",
                minApi = 26,
                searchTerms = listOf("svlte", "concurrent voice data"),
            ) { tm?.isConcurrentVoiceAndDataSupported },
            probe.flag(
                "Real-time text (RTT)",
                "TelephonyManager.isRttSupported()",
                minApi = 29,
                searchTerms = listOf("rtt", "real time text", "accessibility"),
            ) { tm?.isRttSupported },
            probe.notExposedByAndroid(
                "5G NR band support",
                "There is no public API that lists the radio's supported bands or " +
                    "confirms 5G NR capability as a device property. The connected " +
                    "network's type is observable with a phone-state permission, but that " +
                    "reports the network you are on, not what the modem can do.",
                domain = Domain.CONNECTIVITY,
                searchTerms = listOf("5g", "nr", "sub-6", "mmwave", "bands", "lte bands"),
            ),
            probe.notExposedByAndroid(
                "Operator, signal strength and SIM identifiers",
                "Deliberately not read. The network operator, signal strength, IMEI, " +
                    "subscriber ID and phone number identify the user and the account, not " +
                    "the hardware's capability.",
                searchTerms = listOf("imei", "operator", "carrier", "signal"),
            ),
        ),
    )

    /**
     * What is connected right now, from transports only.
     *
     * `NetworkCapabilities` needs `ACCESS_NETWORK_STATE`, an install-time permission,
     * and the transport list carries no identifying detail -- "Wi-Fi" without which
     * Wi-Fi, "cellular" without which carrier.
     */
    private fun activeLink(): Section {
        val cm = probe.attempt<ConnectivityManager?>(null) {
            context.getSystemService(ConnectivityManager::class.java)
        }
        val caps = probe.attempt<NetworkCapabilities?>(null) {
            cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        }
        return Section(
            id = "active-link",
            title = "Active link",
            subtitle = "ConnectivityManager — transport type only, no network identity",
            facts = listOf(
                probe.value(
                    "Transports",
                    "NetworkCapabilities.hasTransport()",
                    minApi = 23,
                    absentText = Absent.NONE,
                    searchTerms = listOf("transport", "connection"),
                ) {
                    caps ?: return@value null
                    TRANSPORTS
                        .filter { (_, spec) ->
                            Build.VERSION.SDK_INT >= spec.second && caps.hasTransport(spec.first)
                        }
                        .map { it.first }
                        .joinToString(", ")
                        .ifBlank { null }
                },
                probe.value(
                    "Reported downstream bandwidth",
                    "NetworkCapabilities.getLinkDownstreamBandwidthKbps()",
                    minApi = 21,
                    searchTerms = listOf("bandwidth", "downstream"),
                    detail = "An estimate the transport publishes for the current link. It " +
                        "is not a measurement and not a hardware maximum.",
                ) { caps?.linkDownstreamBandwidthKbps?.takeIf { it > 0 }?.let { "$it kbps" } },
                probe.value(
                    "Reported upstream bandwidth",
                    "NetworkCapabilities.getLinkUpstreamBandwidthKbps()",
                    minApi = 21,
                ) { caps?.linkUpstreamBandwidthKbps?.takeIf { it > 0 }?.let { "$it kbps" } },
                probe.flag(
                    "Ethernet",
                    "PackageManager.FEATURE_ETHERNET",
                    minApi = 24,
                    searchTerms = listOf("ethernet", "rj45"),
                ) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_ETHERNET) },
            ),
        )
    }

    /** A standing statement of what this lab will not read, and why. */
    private fun privacy() = Section(
        id = "connectivity-privacy",
        title = "Deliberate omissions",
        subtitle = "Readable with permissions, but not capability information",
        facts = listOf(
            probe.notExposedByAndroid(
                "Network names (SSID) and scan results",
                "Reading the connected SSID or nearby networks requires a location " +
                    "permission, because a Wi-Fi scan is a location signal. This app asks " +
                    "the radio what it supports instead, so it needs no location access " +
                    "and can report Wi-Fi capability with the radio switched off.",
                searchTerms = listOf("ssid", "bssid", "scan", "nearby networks"),
            ),
            probe.notExposedByAndroid(
                "MAC addresses",
                "Since Android 6 an app cannot read the device's real Wi-Fi or Bluetooth " +
                    "MAC address; the platform returns a constant placeholder. It is an " +
                    "identifier rather than a capability, so nothing is shown here.",
                searchTerms = listOf("mac address", "hardware address"),
            ),
        ),
    )

    /** An 802.11 generation and the constant that names it. */
    private data class WifiStandard(
        val label: String,
        val value: Int,
        val constant: String,
        val minApi: Int,
        val searchTerms: List<String>,
    )

    private companion object {

        /**
         * The UWB feature string, used directly rather than via the constant.
         *
         * `PackageManager.FEATURE_UWB` was only added to the SDK in API 34, but the
         * platform has answered this feature name since API 31. Querying the string
         * lets an Android 12 or 13 device with UWB report it truthfully instead of
         * being told the API level is too low.
         */
        const val FEATURE_UWB_STRING = "android.hardware.uwb"

        val WIFI_STANDARDS = listOf(
            WifiStandard(
                "802.11a/b/g (legacy)",
                ScanResult.WIFI_STANDARD_LEGACY,
                "WIFI_STANDARD_LEGACY",
                30,
                listOf("802.11g", "legacy wifi"),
            ),
            WifiStandard(
                "Wi-Fi 4 (802.11n)",
                ScanResult.WIFI_STANDARD_11N,
                "WIFI_STANDARD_11N",
                30,
                listOf("wifi 4", "802.11n"),
            ),
            WifiStandard(
                "Wi-Fi 5 (802.11ac)",
                ScanResult.WIFI_STANDARD_11AC,
                "WIFI_STANDARD_11AC",
                30,
                listOf("wifi 5", "802.11ac"),
            ),
            WifiStandard(
                "Wi-Fi 6 (802.11ax)",
                ScanResult.WIFI_STANDARD_11AX,
                "WIFI_STANDARD_11AX",
                30,
                listOf("wifi 6", "wi-fi 6", "802.11ax"),
            ),
            WifiStandard(
                "Wi-Fi 7 (802.11be)",
                ScanResult.WIFI_STANDARD_11BE,
                "WIFI_STANDARD_11BE",
                33,
                listOf("wifi 7", "wi-fi 7", "802.11be", "mlo"),
            ),
            WifiStandard(
                "WiGig (802.11ad)",
                ScanResult.WIFI_STANDARD_11AD,
                "WIFI_STANDARD_11AD",
                31,
                listOf("wigig", "802.11ad", "60ghz"),
            ),
        )

        /** Transport label → (constant, API level the constant was added). */
        val TRANSPORTS = listOf(
            "Wi-Fi" to (NetworkCapabilities.TRANSPORT_WIFI to 21),
            "Cellular" to (NetworkCapabilities.TRANSPORT_CELLULAR to 21),
            "Bluetooth" to (NetworkCapabilities.TRANSPORT_BLUETOOTH to 21),
            "Ethernet" to (NetworkCapabilities.TRANSPORT_ETHERNET to 21),
            "VPN" to (NetworkCapabilities.TRANSPORT_VPN to 21),
            "Wi-Fi Aware" to (NetworkCapabilities.TRANSPORT_WIFI_AWARE to 26),
            "LoWPAN" to (NetworkCapabilities.TRANSPORT_LOWPAN to 27),
            "USB" to (NetworkCapabilities.TRANSPORT_USB to 31),
        )
    }
}
