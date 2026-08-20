package com.devicelab.data.detect

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import com.devicelab.core.detect.Probe
import com.devicelab.core.model.Absent
import com.devicelab.core.model.Fact
import com.devicelab.core.model.Lab
import com.devicelab.core.model.LabReport
import com.devicelab.core.model.Section
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * USB: which modes the port supports, and what is plugged into it right now.
 *
 * Enumerating attached devices needs no permission -- only *communicating* with one
 * does, and this lab never opens a connection. Descriptors come from the device list
 * the platform already holds, so nothing is claimed and no device is disturbed.
 *
 * What Android will not tell an app is the port's own electrical capability: whether
 * it is USB 2.0 or 3.2, whether it does DisplayPort alt mode, what Power Delivery
 * profiles it negotiates. `UsbPort` exists but is restricted to system components, and
 * those rows say so rather than guessing from the SoC.
 */
class UsbDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val probe: Probe,
) : CapabilityDetector {

    override val lab = Lab.USB

    override suspend fun detect(): LabReport {
        val pm = context.packageManager
        val usb = probe.attempt<UsbManager?>(null) {
            context.getSystemService(UsbManager::class.java)
        }
        val devices = probe.attempt(emptyList<UsbDevice>()) {
            usb?.deviceList?.values?.toList().orEmpty()
        }
        val accessories = probe.attempt(0) { usb?.accessoryList?.size ?: 0 }

        return LabReport(
            lab = lab,
            sections = listOf(
                modes(pm, usb),
                portCapability(),
                attached(devices, accessories),
            ),
            notes = listOf(
                "Attached devices are enumerated from descriptors the platform already " +
                    "holds. No USB connection is opened, so no permission dialog appears " +
                    "and no device is interrupted.",
            ),
        )
    }

    private fun modes(pm: PackageManager, usb: UsbManager?) = Section(
        id = "usb-modes",
        title = "USB modes",
        subtitle = "PackageManager feature flags",
        facts = listOf(
            probe.flag(
                "USB host mode",
                "PackageManager.FEATURE_USB_HOST",
                minApi = 12,
                searchTerms = listOf("usb host", "otg", "on the go"),
                detail = "The port can act as a host, powering and driving keyboards, " +
                    "drives, cameras and audio interfaces.",
            ) { pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST) },
            probe.flag(
                "USB accessory mode",
                "PackageManager.FEATURE_USB_ACCESSORY",
                minApi = 12,
                searchTerms = listOf("usb accessory", "aoa"),
                detail = "The device can be driven by an external USB host that implements " +
                    "the Android Open Accessory protocol.",
            ) { pm.hasSystemFeature(PackageManager.FEATURE_USB_ACCESSORY) },
            probe.flag(
                "USB MIDI",
                "PackageManager.FEATURE_MIDI",
                minApi = 23,
                searchTerms = listOf("midi", "usb midi", "music"),
            ) { pm.hasSystemFeature(PackageManager.FEATURE_MIDI) },
            probe.flag(
                "UsbManager service present",
                "getSystemService(UsbManager)",
                minApi = 12,
                supportedText = "Yes",
                unsupportedText = "No — this build has no USB manager",
            ) { usb != null },
        ),
    )

    /**
     * The questions Android refuses to answer about the port itself.
     *
     * These are the ones users most want and the ones most often faked by other tools.
     * `android.hardware.usb.UsbPort` carries the data connection state, power role and
     * alt-mode support, but every accessor on it is guarded by `MANAGE_USB`, a
     * signature permission. There is no fallback, so there is no value to show.
     */
    private fun portCapability() = Section(
        id = "usb-port",
        title = "Port capability",
        subtitle = "Restricted to system components",
        facts = listOf(
            probe.notExposedByAndroid(
                "USB generation (2.0 / 3.x)",
                "No public API reports the port's signalling speed. It depends on the " +
                    "connector, the SoC's controller and the OEM's wiring, and Android " +
                    "exposes none of the three to an installed app. A speed shown by any " +
                    "app without root is inferred, not measured.",
                searchTerms = listOf("usb 3", "usb 2.0", "gen 1", "gen 2", "speed", "5gbps"),
            ),
            probe.notExposedByAndroid(
                "DisplayPort alternate mode",
                "Alt-mode support is part of UsbPort, whose accessors require the " +
                    "signature-level MANAGE_USB permission. Whether a phone can drive an " +
                    "external monitor over USB-C is not queryable by a third-party app.",
                searchTerms = listOf("displayport", "alt mode", "dp alt", "external display"),
            ),
            probe.notExposedByAndroid(
                "USB Power Delivery profiles",
                "The negotiated PD contract, supported voltages and current limits live " +
                    "behind MANAGE_USB. Charging wattage claims in other tools come from " +
                    "vendor documentation, not from the device.",
                searchTerms = listOf("power delivery", "pd", "charging", "watt", "pps"),
            ),
            probe.notExposedByAndroid(
                "Thunderbolt",
                "Android has no Thunderbolt API. Nothing in the platform distinguishes a " +
                    "Thunderbolt-capable port from a plain USB-C one.",
                searchTerms = listOf("thunderbolt", "usb4"),
            ),
        ),
    )

    private fun attached(devices: List<UsbDevice>, accessories: Int): Section {
        val classes = probe.attempt(emptyList<String>()) {
            devices.map { deviceClassName(it.deviceClass) }.distinct().sorted()
        }
        return Section(
            id = "usb-attached",
            title = "Attached now",
            subtitle = if (devices.isEmpty()) {
                "Nothing attached — plug a device in and rescan"
            } else {
                "${devices.size} device(s) on the bus"
            },
            facts = listOf(
                probe.value(
                    "Devices attached",
                    "UsbManager.getDeviceList()",
                    minApi = 12,
                    absentText = Absent.NONE,
                    searchTerms = listOf("usb device", "attached"),
                ) { devices.size.takeIf { it > 0 }?.toString() },
                probe.value(
                    "Accessories attached",
                    "UsbManager.getAccessoryList()",
                    minApi = 12,
                    absentText = Absent.NONE,
                ) { accessories.takeIf { it > 0 }?.toString() },
                probe.value(
                    "Device classes present",
                    "UsbDevice.getDeviceClass()",
                    minApi = 12,
                    absentText = Absent.NONE,
                ) { classes.joinToString(", ").ifBlank { null } },
            ),
            children = devices.mapIndexed { index, device -> deviceSection(index, device) },
        )
    }

    private fun deviceSection(index: Int, device: UsbDevice) = Section(
        id = "usb-device-$index",
        title = probe.attempt("USB device $index") {
            val product = if (Build.VERSION.SDK_INT >= 21) device.productName else null
            product?.takeIf { it.isNotBlank() } ?: device.deviceName
        },
        subtitle = probe.attempt(null) {
            if (Build.VERSION.SDK_INT >= 21) {
                device.manufacturerName?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        },
        facts = listOf(
            probe.value("Device name", "UsbDevice.getDeviceName()", minApi = 12) {
                device.deviceName
            },
            probe.value("Product", "UsbDevice.getProductName()", minApi = 21) {
                device.productName
            },
            probe.value("Manufacturer", "UsbDevice.getManufacturerName()", minApi = 21) {
                device.manufacturerName
            },
            probe.value(
                "Vendor / product ID",
                "UsbDevice.getVendorId() / getProductId()",
                minApi = 12,
                searchTerms = listOf("vid", "pid", "vendor id"),
            ) {
                val vid = String.format("%04X", device.vendorId)
                val pid = String.format("%04X", device.productId)
                "$vid:$pid"
            },
            probe.value("Class", "UsbDevice.getDeviceClass()", minApi = 12) {
                "${deviceClassName(device.deviceClass)} (${device.deviceClass})"
            },
            probe.value("Subclass / protocol", "UsbDevice.getDeviceSubclass()", minApi = 12) {
                "${device.deviceSubclass} / ${device.deviceProtocol}"
            },
            probe.value(
                "USB version",
                "UsbDevice.getVersion()",
                minApi = 23,
                searchTerms = listOf("usb version", "bcdusb"),
                detail = "The version the attached device declares in its descriptor. It " +
                    "describes the peripheral, not the port it is plugged into.",
            ) { device.version },
            probe.value("Configurations", "UsbDevice.getConfigurationCount()", minApi = 21) {
                device.configurationCount.toString()
            },
            probe.value("Interfaces", "UsbDevice.getInterfaceCount()", minApi = 12) {
                device.interfaceCount.toString()
            },
            probe.value(
                "Interface classes",
                "UsbInterface.getInterfaceClass()",
                minApi = 12,
                absentText = Absent.NONE,
                detail = "An interface class is what the device actually does: a headset " +
                    "declaring Audio, a drive declaring Mass Storage.",
            ) {
                (0 until device.interfaceCount)
                    .map { deviceClassName(device.getInterface(it).interfaceClass) }
                    .distinct()
                    .joinToString(", ")
                    .ifBlank { null }
            },
        ),
        children = probe.attempt(emptyList()) {
            if (Build.VERSION.SDK_INT >= 21) {
                (0 until device.configurationCount).map { configIndex ->
                    configurationSection(device.getConfiguration(configIndex), configIndex)
                }
            } else {
                (0 until device.interfaceCount).map { interfaceIndex ->
                    interfaceSection(device.getInterface(interfaceIndex), "$interfaceIndex")
                }
            }
        },
    )

    private fun configurationSection(config: UsbConfiguration, index: Int) = Section(
        id = "usb-config-$index",
        title = probe.attempt("Configuration ${index + 1}") {
            config.name?.takeIf { it.isNotBlank() } ?: "Configuration ${index + 1}"
        },
        facts = listOf(
            probe.value("Identifier", "UsbConfiguration.getId()", minApi = 21) {
                config.id.toString()
            },
            probe.flag("Self-powered", "UsbConfiguration.isSelfPowered()", minApi = 21) {
                config.isSelfPowered
            },
            probe.flag("Remote wake-up", "UsbConfiguration.isRemoteWakeup()", minApi = 21) {
                config.isRemoteWakeup
            },
            probe.value(
                "Maximum power",
                "UsbConfiguration.getMaxPower()",
                minApi = 21,
                searchTerms = listOf("bus power", "ma"),
                detail = "How much bus current this configuration asks for.",
            ) { "${config.maxPower * 2} mA" },
            probe.value("Interfaces", "UsbConfiguration.getInterfaceCount()", minApi = 21) {
                config.interfaceCount.toString()
            },
        ),
        children = probe.attempt(emptyList()) {
            (0 until config.interfaceCount).map { i ->
                interfaceSection(config.getInterface(i), "$index-$i")
            }
        },
    )

    private fun interfaceSection(iface: UsbInterface, idSuffix: String) = Section(
        id = "usb-interface-$idSuffix",
        title = probe.attempt("Interface $idSuffix") {
            val name = if (Build.VERSION.SDK_INT >= 21) iface.name else null
            name?.takeIf { it.isNotBlank() }
                ?: "${deviceClassName(iface.interfaceClass)} interface"
        },
        facts = listOf(
            probe.value("Class", "UsbInterface.getInterfaceClass()", minApi = 12) {
                "${deviceClassName(iface.interfaceClass)} (${iface.interfaceClass})"
            },
            probe.value(
                "Subclass / protocol",
                "UsbInterface.getInterfaceSubclass()",
                minApi = 12,
            ) { "${iface.interfaceSubclass} / ${iface.interfaceProtocol}" },
            probe.value("Identifier", "UsbInterface.getId()", minApi = 12) {
                iface.id.toString()
            },
            probe.value(
                "Alternate setting",
                "UsbInterface.getAlternateSetting()",
                minApi = 21,
            ) { iface.alternateSetting.toString() },
            probe.value("Endpoints", "UsbInterface.getEndpointCount()", minApi = 12) {
                iface.endpointCount.toString()
            },
        ) + endpointFacts(iface),
    )

    private fun endpointFacts(iface: UsbInterface): List<Fact> = probe.attempt(emptyList()) {
        (0 until iface.endpointCount).map { i ->
            val endpoint = iface.getEndpoint(i)
            probe.value(
                "Endpoint ${i + 1}",
                "UsbEndpoint",
                minApi = 12,
            ) {
                buildString {
                    append(endpointTypeName(endpoint.type))
                    append(", ")
                    append(if (endpoint.direction == UsbConstants.USB_DIR_IN) "in" else "out")
                    append(", ")
                    append("${endpoint.maxPacketSize} B packets")
                    endpoint.interval.takeIf { it > 0 }?.let { append(", interval $it") }
                }
            }
        }
    }

    private fun endpointTypeName(type: Int): String = when (type) {
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "Control"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "Isochronous"
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "Bulk"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "Interrupt"
        else -> "Type $type"
    }

    private fun deviceClassName(cls: Int): String = CLASSES[cls] ?: "Class $cls"

    private companion object {

        /**
         * USB base class codes, named from the USB-IF assignment list.
         *
         * `UsbConstants` covers most of these; the ones it omits are given their
         * standard names with the numeric code kept alongside so an unusual device is
         * still identifiable.
         */
        val CLASSES: Map<Int, String> = mapOf(
            UsbConstants.USB_CLASS_PER_INTERFACE to "Per-interface",
            UsbConstants.USB_CLASS_AUDIO to "Audio",
            UsbConstants.USB_CLASS_COMM to "Communications",
            UsbConstants.USB_CLASS_HID to "Human interface",
            UsbConstants.USB_CLASS_PHYSICA to "Physical",
            UsbConstants.USB_CLASS_STILL_IMAGE to "Still image",
            UsbConstants.USB_CLASS_PRINTER to "Printer",
            UsbConstants.USB_CLASS_MASS_STORAGE to "Mass storage",
            UsbConstants.USB_CLASS_HUB to "Hub",
            UsbConstants.USB_CLASS_CDC_DATA to "CDC data",
            UsbConstants.USB_CLASS_CSCID to "Smart card",
            UsbConstants.USB_CLASS_CONTENT_SEC to "Content security",
            UsbConstants.USB_CLASS_VIDEO to "Video",
            0x0F to "Personal healthcare",
            0x10 to "Audio/video",
            0x11 to "Billboard",
            0x12 to "USB-C bridge",
            0x3C to "I3C",
            0xDC to "Diagnostic",
            UsbConstants.USB_CLASS_WIRELESS_CONTROLLER to "Wireless controller",
            UsbConstants.USB_CLASS_MISC to "Miscellaneous",
            UsbConstants.USB_CLASS_APP_SPEC to "Application specific",
            UsbConstants.USB_CLASS_VENDOR_SPEC to "Vendor specific",
        )
    }
}
