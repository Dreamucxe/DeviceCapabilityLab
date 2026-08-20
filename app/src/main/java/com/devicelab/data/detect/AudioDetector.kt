package com.devicelab.data.detect

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MicrophoneInfo
import android.media.Spatializer
import android.media.audiofx.AudioEffect
import android.os.Build
import com.devicelab.core.common.Format
import com.devicelab.core.detect.Probe
import com.devicelab.core.model.Absent
import com.devicelab.core.model.Domain
import com.devicelab.core.model.Fact
import com.devicelab.core.model.Lab
import com.devicelab.core.model.LabReport
import com.devicelab.core.model.Section
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject

/**
 * The audio stack: routes, rates, latency, spatialisation and effects.
 *
 * Audio is the area where "supported" depends most on what is plugged in at the
 * moment, so the wording is careful about it. `AudioTrack.isDirectPlaybackSupported()`
 * answers for the *currently routed* output -- a device that reports no Dolby Digital
 * passthrough over its speaker will report it once an HDMI receiver is connected. The
 * section says so rather than presenting a momentary route as a fixed property of the
 * hardware.
 *
 * Nothing here opens a microphone. Input devices and microphone geometry come from
 * `AudioManager`, which describes the hardware without recording from it, and no audio
 * permission is requested.
 */
class AudioDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val probe: Probe,
) : CapabilityDetector {

    override val lab = Lab.AUDIO

    override suspend fun detect(): LabReport {
        val am = context.getSystemService(AudioManager::class.java)
        val pm = context.packageManager
        if (am == null) {
            return LabReport(
                lab,
                listOf(
                    Section(
                        "audio-unavailable",
                        "Audio",
                        facts = listOf(
                            probe.value("Audio service", "getSystemService(AudioManager)") { null },
                        ),
                    ),
                ),
                listOf("This device does not provide an AudioManager service."),
            )
        }

        return LabReport(
            lab = lab,
            sections = listOf(
                features(pm, am),
                latency(am),
                sampleRates(am),
                spatial(am),
                passthrough(am),
                outputs(am),
                inputs(am),
                microphones(am),
                effects(),
                bluetoothAudio(am),
                volumes(am),
            ),
            notes = listOf(
                "Route-dependent rows -- passthrough, direct playback, spatialisation -- " +
                    "describe the output that is connected right now. Connecting a receiver " +
                    "or headphones and rescanning will legitimately change them.",
            ),
        )
    }

    private fun features(pm: PackageManager, am: AudioManager) = Section(
        id = "features",
        title = "Declared audio features",
        subtitle = "PackageManager, AudioManager",
        facts = listOf(
            probe.flag(
                "Audio output",
                "PackageManager.FEATURE_AUDIO_OUTPUT",
                minApi = 21,
                domain = Domain.AUDIO,
                searchTerms = listOf("speaker", "audio out"),
            ) { pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT) },
            probe.flag(
                "Low-latency audio",
                "PackageManager.FEATURE_AUDIO_LOW_LATENCY",
                minApi = 21,
                domain = Domain.AUDIO,
                searchTerms = listOf("low latency", "latency", "music app"),
                detail = "A vendor claim that continuous output latency stays under 45 ms " +
                    "and cold output latency under 100 ms.",
            ) { pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY) },
            probe.flag(
                "Pro audio",
                "PackageManager.FEATURE_AUDIO_PRO",
                minApi = 23,
                domain = Domain.AUDIO,
                searchTerms = listOf("pro audio", "usb audio", "midi", "20ms"),
                detail = "A stronger claim than low-latency: round-trip under 20 ms, USB " +
                    "host and peripheral audio, and MIDI.",
            ) { pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO) },
            probe.flag(
                "Microphone",
                "PackageManager.FEATURE_MICROPHONE",
                domain = Domain.AUDIO,
                searchTerms = listOf("microphone", "mic", "recording"),
            ) { pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) },
            probe.flag(
                "MIDI",
                "PackageManager.FEATURE_MIDI",
                minApi = 23,
                searchTerms = listOf("midi", "music"),
            ) { pm.hasSystemFeature(PackageManager.FEATURE_MIDI) },
            probe.flag(
                "Haptic audio channels",
                "AudioManager.isHapticPlaybackSupported()",
                minApi = 29,
                searchTerms = listOf("haptic", "vibration", "audio coupled haptics"),
                detail = "Playback of haptic channels carried inside an audio file, used " +
                    "for ringtones with matched vibration.",
            ) { AudioManager.isHapticPlaybackSupported() },
            probe.flag(
                "Volume fixed",
                "AudioManager.isVolumeFixed()",
                minApi = 21,
                supportedText = "Yes — volume is not adjustable on this device",
                unsupportedText = "No — volume is adjustable",
            ) { am.isVolumeFixed },
            probe.flag(
                "Bluetooth SCO off-call",
                "AudioManager.isBluetoothScoAvailableOffCall()",
                searchTerms = listOf("sco", "bluetooth headset", "voice recognition"),
            ) { am.isBluetoothScoAvailableOffCall },
        ),
    )

    /**
     * The latency figures Android actually publishes.
     *
     * There is no API for round-trip latency, and inventing one from the buffer size
     * would be a fabrication: the true figure includes the mixer, the HAL, DSP
     * post-processing and the transducer. What can be stated honestly is the size of
     * one buffer at the native rate, which is a *lower bound* on the output path.
     */
    private fun latency(am: AudioManager): Section {
        val rate = probe.attempt<Int?>(null) {
            am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        }
        val frames = probe.attempt<Int?>(null) {
            am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
        }
        return Section(
            id = "latency",
            title = "Latency & buffering",
            subtitle = "AudioManager.getProperty()",
            facts = listOf(
                probe.value(
                    "Native output sample rate",
                    "PROPERTY_OUTPUT_SAMPLE_RATE",
                    minApi = 17,
                    domain = Domain.AUDIO,
                    searchTerms = listOf("sample rate", "48khz", "44.1khz", "native rate"),
                    detail = "The rate the output HAL runs at. A track at any other rate is " +
                        "resampled to this before it reaches the hardware.",
                ) { rate?.let { Format.kilohertz(it) } },
                probe.value(
                    "Frames per buffer",
                    "PROPERTY_OUTPUT_FRAMES_PER_BUFFER",
                    minApi = 17,
                    searchTerms = listOf("buffer size", "frames per buffer"),
                ) { frames?.toString() },
                probe.value(
                    "One buffer",
                    "framesPerBuffer ÷ sampleRate",
                    minApi = 17,
                    searchTerms = listOf("latency", "buffer latency", "ms"),
                    detail = "The duration of a single output buffer. This is a floor on " +
                        "output latency, not the round-trip figure -- Android exposes no " +
                        "API for that, and it can only be measured with a loopback cable.",
                ) {
                    if (rate == null || frames == null || rate <= 0) return@value null
                    "${Format.decimal(frames * 1000f / rate, 2)} ms"
                },
                probe.notExposedByAndroid(
                    "Round-trip audio latency",
                    "No platform API reports measured round-trip latency. It is a property " +
                        "of the whole chain -- app, mixer, HAL, DSP, transducer -- and is " +
                        "obtained with loopback measurement hardware, not a query.",
                    searchTerms = listOf("round trip latency", "rtl"),
                ),
                probe.value(
                    "AudioTrack native rate",
                    "AudioTrack.getNativeOutputSampleRate(STREAM_MUSIC)",
                ) {
                    AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
                        .takeIf { it > 0 }
                        ?.let { Format.kilohertz(it) }
                },
                probe.flag(
                    "Unprocessed recording source",
                    "PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED",
                    minApi = 24,
                    searchTerms = listOf("unprocessed", "raw audio", "measurement"),
                    detail = "Whether MediaRecorder.AudioSource.UNPROCESSED gives audio " +
                        "with the vendor's signal processing bypassed.",
                ) {
                    when (
                        am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
                    ) {
                        "true" -> true
                        "false" -> false
                        else -> null
                    }
                },
                probe.flag(
                    "Near-ultrasound microphone",
                    "PROPERTY_SUPPORT_MIC_NEAR_ULTRASOUND",
                    minApi = 23,
                    searchTerms = listOf("ultrasound", "18khz", "20khz"),
                ) {
                    when (am.getProperty(AudioManager.PROPERTY_SUPPORT_MIC_NEAR_ULTRASOUND)) {
                        "true" -> true
                        "false" -> false
                        else -> null
                    }
                },
                probe.flag(
                    "Near-ultrasound speaker",
                    "PROPERTY_SUPPORT_SPEAKER_NEAR_ULTRASOUND",
                    minApi = 23,
                    searchTerms = listOf("ultrasound", "speaker"),
                ) {
                    when (am.getProperty(AudioManager.PROPERTY_SUPPORT_SPEAKER_NEAR_ULTRASOUND)) {
                        "true" -> true
                        "false" -> false
                        else -> null
                    }
                },
                probe.notExposedByAndroid(
                    "AAudio MMAP / exclusive mode",
                    "Whether the device supports MMAP shared or exclusive streams is only " +
                        "discoverable through AAudio, a native (NDK) API with no Java or " +
                        "Kotlin binding.",
                    searchTerms = listOf("aaudio", "mmap", "oboe", "exclusive mode"),
                ),
            ),
        )
    }

    /**
     * Which PCM formats the framework will accept.
     *
     * `AudioTrack.getMinBufferSize()` returns an error for a combination the framework
     * refuses, which makes it a genuine query rather than a guess. It is careful to say
     * "accepted" rather than "played natively": a 192 kHz track is accepted and then
     * resampled to the HAL rate, and claiming hi-res playback from that would be a lie.
     */
    private fun sampleRates(am: AudioManager): Section {
        val native = probe.attempt<Int?>(null) {
            am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        }
        fun accepted(rate: Int, encoding: Int, channelMask: Int = AudioFormat.CHANNEL_OUT_STEREO) =
            probe.attempt(false) {
                val size = AudioTrack.getMinBufferSize(rate, channelMask, encoding)
                size > 0
            }

        return Section(
            id = "sample-rates",
            title = "PCM formats accepted",
            subtitle = "AudioTrack.getMinBufferSize() — framework acceptance, not native rate",
            facts = buildList {
                listOf(44100, 48000, 88200, 96000, 176400, 192000).forEach { rate ->
                    add(
                        probe.verdict(
                            Format.kilohertz(rate),
                            "AudioTrack.getMinBufferSize($rate, STEREO, PCM_16BIT)",
                            searchTerms = listOf("sample rate", "${rate / 1000}khz", "hi-res"),
                        ) {
                            if (!accepted(rate, AudioFormat.ENCODING_PCM_16BIT)) {
                                return@verdict Probe.Verdict.no("Rejected by the framework")
                            }
                            when {
                                native == null -> Probe.Verdict.yes("Accepted")
                                rate == native -> Probe.Verdict.yes(
                                    "Accepted — matches the native rate",
                                )
                                else -> Probe.Verdict.partial(
                                    "Accepted, resampled to ${Format.kilohertz(native)}",
                                    "The framework takes a track at this rate but the " +
                                        "output HAL runs at ${Format.kilohertz(native)}, so " +
                                        "it is converted before playback.",
                                )
                            }
                        },
                    )
                }
                add(
                    probe.flag(
                        "16-bit PCM",
                        "AudioTrack.getMinBufferSize(ENCODING_PCM_16BIT)",
                        supportedText = "Accepted",
                        unsupportedText = "Rejected",
                    ) { accepted(48000, AudioFormat.ENCODING_PCM_16BIT) },
                )
                add(
                    probe.flag(
                        "Float PCM",
                        "AudioTrack.getMinBufferSize(ENCODING_PCM_FLOAT)",
                        minApi = 21,
                        searchTerms = listOf("float", "32-bit float", "high resolution"),
                        supportedText = "Accepted",
                        unsupportedText = "Rejected",
                    ) { accepted(48000, AudioFormat.ENCODING_PCM_FLOAT) },
                )
                add(
                    probe.flag(
                        "24-bit packed PCM",
                        "AudioTrack.getMinBufferSize(ENCODING_PCM_24BIT_PACKED)",
                        minApi = 31,
                        searchTerms = listOf("24-bit", "hi-res"),
                        supportedText = "Accepted",
                        unsupportedText = "Rejected",
                    ) { accepted(48000, AudioFormat.ENCODING_PCM_24BIT_PACKED) },
                )
                add(
                    probe.flag(
                        "32-bit PCM",
                        "AudioTrack.getMinBufferSize(ENCODING_PCM_32BIT)",
                        minApi = 31,
                        searchTerms = listOf("32-bit"),
                        supportedText = "Accepted",
                        unsupportedText = "Rejected",
                    ) { accepted(48000, AudioFormat.ENCODING_PCM_32BIT) },
                )
                CHANNEL_MASKS.forEach { (label, mask) ->
                    add(
                        probe.flag(
                            label,
                            "AudioTrack.getMinBufferSize(48000, $label, PCM_16BIT)",
                            minApi = mask.second,
                            searchTerms = listOf("channels", "surround", label.lowercase()),
                            supportedText = "Accepted",
                            unsupportedText = "Rejected",
                        ) { accepted(48000, AudioFormat.ENCODING_PCM_16BIT, mask.first) },
                    )
                }
            },
        )
    }

    /** Spatial audio, which the platform only reports from Android 12L. */
    private fun spatial(am: AudioManager): Section {
        val facts = mutableListOf<Fact>()
        facts += probe.flag(
            "Spatial audio available",
            "Spatializer.isAvailable()",
            minApi = 32,
            domain = Domain.AUDIO,
            searchTerms = listOf("spatial audio", "spatializer", "3d audio"),
        ) { SpatializerReader.available(am) }
        facts += probe.flag(
            "Spatial audio enabled",
            "Spatializer.isEnabled()",
            minApi = 32,
            searchTerms = listOf("spatial audio"),
        ) { SpatializerReader.enabled(am) }
        facts += probe.value(
            "Immersive level",
            "Spatializer.getImmersiveAudioLevel()",
            minApi = 32,
            domain = Domain.AUDIO,
            searchTerms = listOf("immersive", "multichannel"),
        ) { SpatializerReader.immersiveLevel(am) }
        facts += probe.flag(
            "Head tracker available",
            "Spatializer.isHeadTrackerAvailable()",
            minApi = 33,
            searchTerms = listOf("head tracking", "head tracker"),
        ) { SpatializerReader.headTracker(am) }
        facts += probe.verdict(
            "5.1 can be spatialised",
            "Spatializer.canBeSpatialized()",
            minApi = 32,
            searchTerms = listOf("spatial", "5.1", "surround"),
        ) {
            when (SpatializerReader.canSpatialise5Point1(am)) {
                true -> Probe.Verdict.yes("Yes, for the current output")
                false -> Probe.Verdict.no("No, for the current output")
                null -> Probe.Verdict.unknown()
            }
        }
        facts += probe.notExposedByAndroid(
            "Dolby Atmos rendering",
            "Android reports spatialisation and multichannel capability, but never " +
                "whether the renderer is Dolby Atmos, Sony 360 or a vendor equivalent. " +
                "That branding lives inside the vendor's audio effect.",
            searchTerms = listOf("atmos", "dolby atmos", "360 reality audio"),
        )
        return Section(
            id = "spatial",
            title = "Spatial audio",
            subtitle = "Spatializer (Android 12L and later)",
            facts = facts,
        )
    }

    /**
     * Compressed passthrough, asked of the route that is connected now.
     *
     * `isDirectPlaybackSupported` is the only public API that answers this, and it
     * answers for the current output device. The wording never promotes a momentary
     * "no" into a hardware limitation.
     */
    private fun passthrough(am: AudioManager): Section {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()

        fun direct(encoding: Int, channels: Int, rate: Int = 48000): Boolean? {
            if (Build.VERSION.SDK_INT < 29) return null
            return probe.attempt<Boolean?>(null) {
                val format = AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(rate)
                    .setChannelMask(channels)
                    .build()
                AudioTrack.isDirectPlaybackSupported(format, attributes)
            }
        }

        return Section(
            id = "passthrough",
            title = "Compressed passthrough",
            subtitle = "AudioTrack.isDirectPlaybackSupported() — for the current output route",
            facts = PASSTHROUGH_FORMATS.map { spec ->
                probe.flag(
                    spec.label,
                    "isDirectPlaybackSupported(${spec.label})",
                    minApi = maxOf(29, spec.minApi),
                    searchTerms = spec.searchTerms,
                    supportedText = "Supported on the current route",
                    unsupportedText = "Not supported on the current route",
                    detail = "Passthrough depends on what the audio is being sent to. " +
                        "Connect an HDMI receiver or a capable dock and rescan to see " +
                        "what that route accepts.",
                ) { direct(spec.encoding, spec.channelMask) }
            } + listOf(
                probe.flag(
                    "Offloaded playback (MP3/AAC)",
                    "AudioManager.isOffloadedPlaybackSupported()",
                    minApi = 29,
                    searchTerms = listOf("offload", "dsp decode", "battery"),
                    detail = "Whether compressed audio can be handed to the DSP to decode, " +
                        "which is how long playback sessions save power.",
                ) {
                    probe.attempt<Boolean?>(null) {
                        val format = AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_MP3)
                            .setSampleRate(48000)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                        AudioManager.isOffloadedPlaybackSupported(format, attributes)
                    }
                },
                probe.value(
                    "Direct profiles for media",
                    "AudioManager.getDirectProfilesForAttributes()",
                    minApi = 33,
                    absentText = Absent.NONE,
                    searchTerms = listOf("direct profile", "passthrough"),
                ) {
                    probe.attempt<String?>(null) {
                        DirectProfileReader.summarise(am, attributes) { encodingName(it) }
                    }
                },
            ),
        )
    }

    private fun outputs(am: AudioManager): Section {
        val devices = probe.attempt(emptyArray<AudioDeviceInfo>()) {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        }
        return Section(
            id = "outputs",
            title = "Output devices",
            subtitle = "AudioManager.getDevices(GET_DEVICES_OUTPUTS)",
            facts = listOf(
                probe.value(
                    "Output devices",
                    "AudioManager.getDevices()",
                    minApi = 23,
                    domain = Domain.AUDIO,
                ) { devices.size.takeIf { it > 0 }?.toString() },
                probe.value("Types present", "AudioDeviceInfo.getType()", minApi = 23) {
                    devices.map { deviceTypeName(it.type) }.distinct().sorted()
                        .joinToString(", ").ifBlank { null }
                },
                probe.flag(
                    "Built-in speaker",
                    "TYPE_BUILTIN_SPEAKER",
                    minApi = 23,
                    domain = Domain.AUDIO,
                ) { devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } },
                probe.flag("Earpiece", "TYPE_BUILTIN_EARPIECE", minApi = 23) {
                    devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                },
                probe.flag(
                    "Headphone jack in use",
                    "TYPE_WIRED_HEADPHONES / TYPE_WIRED_HEADSET",
                    minApi = 23,
                    searchTerms = listOf("headphone", "3.5mm", "jack"),
                    supportedText = "Connected",
                    unsupportedText = "Not connected",
                ) {
                    devices.any {
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    }
                },
                probe.flag(
                    "Bluetooth A2DP connected",
                    "TYPE_BLUETOOTH_A2DP",
                    minApi = 23,
                    searchTerms = listOf("bluetooth", "a2dp", "wireless"),
                    supportedText = "Connected",
                    unsupportedText = "Not connected",
                ) { devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } },
                probe.flag(
                    "USB audio connected",
                    "TYPE_USB_DEVICE / TYPE_USB_HEADSET",
                    minApi = 23,
                    searchTerms = listOf("usb audio", "usb dac"),
                    supportedText = "Connected",
                    unsupportedText = "Not connected",
                ) {
                    devices.any {
                        it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                            it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
                    }
                },
                probe.flag(
                    "HDMI audio connected",
                    "TYPE_HDMI",
                    minApi = 23,
                    searchTerms = listOf("hdmi", "arc", "earc"),
                    supportedText = "Connected",
                    unsupportedText = "Not connected",
                ) {
                    devices.any {
                        it.type == AudioDeviceInfo.TYPE_HDMI ||
                            it.type == AudioDeviceInfo.TYPE_HDMI_ARC
                    }
                },
                probe.flag(
                    "LE Audio connected",
                    "TYPE_BLE_HEADSET / TYPE_BLE_SPEAKER",
                    minApi = 31,
                    searchTerms = listOf("le audio", "lc3", "bluetooth le"),
                    supportedText = "Connected",
                    unsupportedText = "Not connected",
                ) {
                    devices.any {
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                    }
                },
            ),
            children = devices.mapIndexed { index, device -> deviceSection("out", index, device) },
        )
    }

    private fun inputs(am: AudioManager): Section {
        val devices = probe.attempt(emptyArray<AudioDeviceInfo>()) {
            am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        }
        return Section(
            id = "inputs",
            title = "Input devices",
            subtitle = "AudioManager.getDevices(GET_DEVICES_INPUTS)",
            facts = listOf(
                probe.value("Input devices", "AudioManager.getDevices()", minApi = 23) {
                    devices.size.takeIf { it > 0 }?.toString()
                },
                probe.value("Types present", "AudioDeviceInfo.getType()", minApi = 23) {
                    devices.map { deviceTypeName(it.type) }.distinct().sorted()
                        .joinToString(", ").ifBlank { null }
                },
                probe.flag("Built-in microphone", "TYPE_BUILTIN_MIC", minApi = 23) {
                    devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                },
            ),
            children = devices.mapIndexed { index, device -> deviceSection("in", index, device) },
        )
    }

    private fun deviceSection(prefix: String, index: Int, device: AudioDeviceInfo) = Section(
        id = "$prefix-device-$index",
        title = probe.attempt("Device $index") {
            val name = device.productName?.toString().orEmpty()
            val type = deviceTypeName(device.type)
            if (name.isBlank() || name == Build.MODEL) type else "$type — $name"
        },
        facts = listOf(
            probe.value("Type", "AudioDeviceInfo.getType()", minApi = 23) {
                deviceTypeName(device.type)
            },
            probe.value("Product name", "AudioDeviceInfo.getProductName()", minApi = 23) {
                device.productName?.toString()
            },
            probe.value(
                "Sample rates",
                "AudioDeviceInfo.getSampleRates()",
                minApi = 23,
                absentText = Absent.NOT_EXPOSED,
                searchTerms = listOf("sample rate"),
                detail = "An empty list means the device imposes no constraint, not that " +
                    "it supports nothing.",
            ) {
                device.sampleRates.takeIf { it.isNotEmpty() }
                    ?.sorted()?.joinToString(", ") { Format.kilohertz(it) }
            },
            probe.value(
                "Channel counts",
                "AudioDeviceInfo.getChannelCounts()",
                minApi = 23,
                absentText = Absent.NOT_EXPOSED,
            ) {
                device.channelCounts.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(", ")
            },
            probe.value(
                "Encodings",
                "AudioDeviceInfo.getEncodings()",
                minApi = 23,
                absentText = Absent.NOT_EXPOSED,
                searchTerms = listOf("encoding", "passthrough", "pcm"),
            ) {
                device.encodings.takeIf { it.isNotEmpty() }
                    ?.map { encodingName(it) }?.distinct()?.joinToString(", ")
            },
            probe.value(
                "Channel masks",
                "AudioDeviceInfo.getChannelMasks()",
                minApi = 23,
                absentText = Absent.NOT_EXPOSED,
            ) {
                device.channelMasks.takeIf { it.isNotEmpty() }
                    ?.map { channelMaskName(it) }?.joinToString(", ")
            },
            probe.value("Address", "AudioDeviceInfo.getAddress()", minApi = 28) {
                device.address.takeIf { it.isNotBlank() }
            },
            probe.value(
                "Audio profiles",
                "AudioDeviceInfo.getAudioProfiles()",
                minApi = 31,
                absentText = Absent.NONE,
                searchTerms = listOf("audio profile"),
            ) {
                probe.attempt<String?>(null) {
                    DeviceProfileReader.summarise(device) { encodingName(it) }
                }
            },
            probe.flag("Sink", "AudioDeviceInfo.isSink()", minApi = 23) { device.isSink },
            probe.flag("Source", "AudioDeviceInfo.isSource()", minApi = 23) { device.isSource },
        ),
    )

    /**
     * Microphone geometry and response.
     *
     * `getMicrophones()` is unusually generous: position, orientation, directionality
     * and frequency response, all without a permission, because none of it is audio.
     */
    private fun microphones(am: AudioManager): Section {
        val mics: List<MicrophoneInfo> = if (Build.VERSION.SDK_INT >= 28) {
            probe.attempt(emptyList()) { am.microphones }
        } else {
            emptyList()
        }
        return Section(
            id = "microphones",
            title = "Microphones",
            subtitle = "AudioManager.getMicrophones()",
            facts = listOf(
                probe.value(
                    "Microphones reported",
                    "AudioManager.getMicrophones()",
                    minApi = 28,
                    domain = Domain.AUDIO,
                    searchTerms = listOf("microphone", "mic count"),
                ) { mics.size.takeIf { it > 0 }?.toString() },
            ),
            children = mics.mapIndexed { index, mic ->
                Section(
                    id = "mic-$index",
                    title = probe.attempt("Microphone ${index + 1}") {
                        mic.description.ifBlank { "Microphone ${index + 1}" }
                    },
                    facts = listOf(
                        probe.value("Location", "MicrophoneInfo.getLocation()", minApi = 28) {
                            when (mic.location) {
                                MicrophoneInfo.LOCATION_MAINBODY -> "Main body"
                                MicrophoneInfo.LOCATION_MAINBODY_MOVABLE -> "Main body (movable)"
                                MicrophoneInfo.LOCATION_PERIPHERAL -> "Peripheral"
                                else -> null
                            }
                        },
                        probe.value(
                            "Directionality",
                            "MicrophoneInfo.getDirectionality()",
                            minApi = 28,
                            searchTerms = listOf("omni", "cardioid", "directional"),
                        ) {
                            when (mic.directionality) {
                                MicrophoneInfo.DIRECTIONALITY_OMNI -> "Omnidirectional"
                                MicrophoneInfo.DIRECTIONALITY_BI_DIRECTIONAL -> "Bidirectional"
                                MicrophoneInfo.DIRECTIONALITY_CARDIOID -> "Cardioid"
                                MicrophoneInfo.DIRECTIONALITY_SUPER_CARDIOID -> "Super-cardioid"
                                MicrophoneInfo.DIRECTIONALITY_HYPER_CARDIOID -> "Hyper-cardioid"
                                else -> null
                            }
                        },
                        probe.value(
                            "Sensitivity",
                            "MicrophoneInfo.getSensitivity()",
                            minApi = 28,
                            searchTerms = listOf("sensitivity", "dbfs"),
                        ) {
                            mic.sensitivity
                                .takeIf { it != MicrophoneInfo.SENSITIVITY_UNKNOWN }
                                ?.let { "${Format.decimal(it, 1)} dBFS at 94 dB SPL" }
                        },
                        probe.value("Maximum SPL", "MicrophoneInfo.getMaxSpl()", minApi = 28) {
                            mic.maxSpl.takeIf { it != MicrophoneInfo.SPL_UNKNOWN }
                                ?.let { "${Format.decimal(it, 1)} dB" }
                        },
                        probe.value("Minimum SPL", "MicrophoneInfo.getMinSpl()", minApi = 28) {
                            mic.minSpl.takeIf { it != MicrophoneInfo.SPL_UNKNOWN }
                                ?.let { "${Format.decimal(it, 1)} dB" }
                        },
                        probe.value(
                            "Frequency response",
                            "MicrophoneInfo.getFrequencyResponse()",
                            minApi = 28,
                            absentText = Absent.NOT_EXPOSED,
                            searchTerms = listOf("frequency response", "hz"),
                        ) {
                            val response = mic.frequencyResponse
                            if (response.isEmpty()) return@value null
                            val low = response.minOf { it.first }
                            val high = response.maxOf { it.second }
                            "${response.size} points, ${Format.decimal(low, 0)} Hz – " +
                                "${Format.decimal(high, 0)} Hz"
                        },
                        probe.value("Position", "MicrophoneInfo.getPosition()", minApi = 28) {
                            val p = mic.position
                            if (p == MicrophoneInfo.POSITION_UNKNOWN) return@value null
                            "x ${Format.decimal(p.x, 3)} m, y ${Format.decimal(p.y, 3)} m, " +
                                "z ${Format.decimal(p.z, 3)} m"
                        },
                        probe.value("Group", "MicrophoneInfo.getGroup()", minApi = 28) {
                            mic.group.takeIf { it != MicrophoneInfo.GROUP_UNKNOWN }?.toString()
                        },
                        probe.value("Device type", "MicrophoneInfo.getType()", minApi = 28) {
                            deviceTypeName(mic.type)
                        },
                    ),
                )
            },
        )
    }

    /** Platform audio effects, which is where an OEM's DSP shows up by name. */
    private fun effects(): Section {
        val descriptors = probe.attempt(emptyArray<AudioEffect.Descriptor>()) {
            AudioEffect.queryEffects() ?: emptyArray()
        }
        fun has(type: UUID) = descriptors.any { it.type == type }

        return Section(
            id = "effects",
            title = "Audio effects",
            subtitle = "AudioEffect.queryEffects() — ${descriptors.size} registered",
            facts = listOf(
                probe.value("Effects registered", "AudioEffect.queryEffects()") {
                    descriptors.size.takeIf { it > 0 }?.toString()
                },
                probe.flag("Equaliser", "EFFECT_TYPE_EQUALIZER", searchTerms = listOf("eq", "equalizer")) {
                    has(AudioEffect.EFFECT_TYPE_EQUALIZER)
                },
                probe.flag("Bass boost", "EFFECT_TYPE_BASS_BOOST") {
                    has(AudioEffect.EFFECT_TYPE_BASS_BOOST)
                },
                probe.flag("Virtualiser", "EFFECT_TYPE_VIRTUALIZER", searchTerms = listOf("virtualizer")) {
                    has(AudioEffect.EFFECT_TYPE_VIRTUALIZER)
                },
                probe.flag("Preset reverb", "EFFECT_TYPE_PRESET_REVERB") {
                    has(AudioEffect.EFFECT_TYPE_PRESET_REVERB)
                },
                probe.flag("Environmental reverb", "EFFECT_TYPE_ENV_REVERB") {
                    has(AudioEffect.EFFECT_TYPE_ENV_REVERB)
                },
                probe.flag("Loudness enhancer", "EFFECT_TYPE_LOUDNESS_ENHANCER", minApi = 19) {
                    has(AudioEffect.EFFECT_TYPE_LOUDNESS_ENHANCER)
                },
                probe.flag(
                    "Dynamics processing",
                    "EFFECT_TYPE_DYNAMICS_PROCESSING",
                    minApi = 28,
                    searchTerms = listOf("multiband", "compressor", "limiter"),
                ) { has(AudioEffect.EFFECT_TYPE_DYNAMICS_PROCESSING) },
                probe.flag(
                    "Haptic generator",
                    "EFFECT_TYPE_HAPTIC_GENERATOR",
                    minApi = 31,
                    searchTerms = listOf("haptic"),
                ) { has(AudioEffect.EFFECT_TYPE_HAPTIC_GENERATOR) },
                probe.flag(
                    "Acoustic echo canceller",
                    "EFFECT_TYPE_AEC",
                    minApi = 18,
                    searchTerms = listOf("aec", "echo cancellation"),
                ) { has(AudioEffect.EFFECT_TYPE_AEC) },
                probe.flag(
                    "Noise suppressor",
                    "EFFECT_TYPE_NS",
                    minApi = 18,
                    searchTerms = listOf("noise suppression", "ns"),
                ) { has(AudioEffect.EFFECT_TYPE_NS) },
                probe.flag(
                    "Automatic gain control",
                    "EFFECT_TYPE_AGC",
                    minApi = 18,
                    searchTerms = listOf("agc"),
                ) { has(AudioEffect.EFFECT_TYPE_AGC) },
                probe.value(
                    "Implementors",
                    "AudioEffect.Descriptor.implementor",
                    absentText = Absent.UNKNOWN,
                    detail = "Who supplied the effects registered on this device. Vendor " +
                        "names here are the closest the platform comes to naming its DSP.",
                ) {
                    descriptors.mapNotNull { it.implementor?.takeIf { n -> n.isNotBlank() } }
                        .distinct()
                        .joinToString(", ")
                        .ifBlank { null }
                },
            ),
            children = if (descriptors.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    Section(
                        id = "effect-list",
                        title = "All registered effects",
                        facts = descriptors.mapIndexed { index, d ->
                            probe.value(
                                probe.attempt("Effect $index") { d.name ?: "Effect $index" },
                                "AudioEffect.Descriptor",
                            ) {
                                buildString {
                                    append(d.implementor?.takeIf { it.isNotBlank() } ?: "unknown")
                                    append(" · ")
                                    append(connectModeName(d.connectMode))
                                }
                            }
                        },
                    ),
                )
            },
        )
    }

    /**
     * Bluetooth audio, and an honest statement of its limit.
     *
     * The codec actually negotiated on an A2DP link -- LDAC, aptX, AAC, SBC -- is
     * readable only through `BluetoothCodecStatus`, which requires the privileged
     * `BLUETOOTH_PRIVILEGED` permission that is not grantable to an installed app.
     * The row says exactly that instead of guessing from the peer's name.
     */
    private fun bluetoothAudio(am: AudioManager): Section {
        val outputs = probe.attempt(emptyArray<AudioDeviceInfo>()) {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        }
        return Section(
            id = "bluetooth-audio",
            title = "Bluetooth audio",
            subtitle = "AudioManager, AudioDeviceInfo",
            facts = listOf(
                probe.value(
                    "Connected Bluetooth audio",
                    "AudioDeviceInfo.getType()",
                    minApi = 23,
                    absentText = Absent.NONE,
                    searchTerms = listOf("bluetooth", "a2dp", "le audio"),
                ) {
                    outputs
                        .filter {
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                                (
                                    Build.VERSION.SDK_INT >= 31 &&
                                        (
                                            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                                                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                                            )
                                    )
                        }
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString(", ") { device ->
                            val name = probe.attempt("") { device.productName?.toString() ?: "" }
                            if (name.isBlank()) {
                                deviceTypeName(device.type)
                            } else {
                                "$name (${deviceTypeName(device.type)})"
                            }
                        }
                },
                probe.notExposedByAndroid(
                    "Negotiated A2DP codec",
                    "The codec in use on a Bluetooth link (SBC, AAC, aptX, aptX HD, LDAC, " +
                        "LC3) is only readable through BluetoothCodecStatus, which needs " +
                        "the BLUETOOTH_PRIVILEGED permission. That permission is reserved " +
                        "for the platform and cannot be granted to an installed app, so no " +
                        "third-party tool can report this correctly.",
                    domain = Domain.AUDIO,
                    searchTerms = listOf("ldac", "aptx", "aac", "sbc", "lc3", "a2dp codec"),
                ),
                probe.flag(
                    "LE Audio device type known to the platform",
                    "AudioDeviceInfo.TYPE_BLE_HEADSET",
                    minApi = 31,
                    searchTerms = listOf("le audio", "lc3", "bluetooth 5.2"),
                    supportedText = "Yes — this Android version models LE Audio devices",
                    unsupportedText = "No",
                ) { Build.VERSION.SDK_INT >= 31 },
            ),
        )
    }

    private fun volumes(am: AudioManager) = Section(
        id = "volumes",
        title = "Volume ranges",
        subtitle = "AudioManager.getStreamMaxVolume()",
        facts = STREAMS.map { (label, stream) ->
            probe.value(label, "AudioManager.getStreamMaxVolume($label)") {
                val max = am.getStreamMaxVolume(stream)
                val min = if (Build.VERSION.SDK_INT >= 28) {
                    probe.attempt(0) { am.getStreamMinVolume(stream) }
                } else {
                    0
                }
                "$min – $max steps"
            }
        },
    )

    // ---- naming helpers ---------------------------------------------------

    private fun deviceTypeName(type: Int): String = DEVICE_TYPES[type] ?: "Type $type"

    private fun encodingName(encoding: Int): String = ENCODINGS[encoding] ?: "encoding $encoding"

    private fun channelMaskName(mask: Int): String =
        CHANNEL_MASK_NAMES[mask] ?: "mask 0x${Integer.toHexString(mask)}"

    private fun connectModeName(mode: String?): String = when (mode) {
        AudioEffect.EFFECT_INSERT -> "insert"
        AudioEffect.EFFECT_AUXILIARY -> "auxiliary"
        AudioEffect.EFFECT_PRE_PROCESSING -> "pre-processing"
        AudioEffect.EFFECT_POST_PROCESSING -> "post-processing"
        else -> mode ?: "unknown"
    }

    /** A compressed format worth asking the current route about. */
    private data class PassthroughSpec(
        val label: String,
        val encoding: Int,
        val channelMask: Int,
        val minApi: Int,
        val searchTerms: List<String>,
    )

    /**
     * Every touch of [android.media.Spatializer], kept out of [AudioDetector] itself.
     *
     * The class arrived in API 32. A method body that names it makes the *enclosing*
     * class's verification depend on it resolving, so the reference lives here instead
     * and each entry point re-checks the version before it is reached. On an older
     * device these methods are simply never called and this object never loads.
     */
    private object SpatializerReader {

        fun available(am: AudioManager): Boolean? =
            if (Build.VERSION.SDK_INT >= 32) am.spatializer.isAvailable else null

        fun enabled(am: AudioManager): Boolean? =
            if (Build.VERSION.SDK_INT >= 32) am.spatializer.isEnabled else null

        fun headTracker(am: AudioManager): Boolean? =
            if (Build.VERSION.SDK_INT >= 33) am.spatializer.isHeadTrackerAvailable else null

        fun immersiveLevel(am: AudioManager): String? {
            if (Build.VERSION.SDK_INT < 32) return null
            return when (am.spatializer.immersiveAudioLevel) {
                Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE -> "None"
                Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_MULTICHANNEL -> "Multichannel"
                Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_OTHER -> "Other"
                else -> null
            }
        }

        fun canSpatialise5Point1(am: AudioManager): Boolean? {
            if (Build.VERSION.SDK_INT < 32) return null
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
                .build()
            return am.spatializer.canBeSpatialized(attributes, format)
        }
    }

    /**
     * Isolated for the same reason as [SpatializerReader]: [android.media.AudioProfile]
     * only exists from API 31 and the query itself from API 33.
     */
    private object DirectProfileReader {

        fun summarise(
            am: AudioManager,
            attributes: AudioAttributes,
            name: (Int) -> String,
        ): String? {
            if (Build.VERSION.SDK_INT < 33) return null
            return am.getDirectProfilesForAttributes(attributes)
                .takeIf { it.isNotEmpty() }
                ?.map { name(it.format) }
                ?.distinct()
                ?.joinToString(", ")
        }
    }

    /** Per-device audio profiles, also API 31 and also kept out of the detector. */
    private object DeviceProfileReader {

        fun summarise(device: AudioDeviceInfo, name: (Int) -> String): String? {
            if (Build.VERSION.SDK_INT < 31) return null
            return device.audioProfiles.takeIf { it.isNotEmpty() }?.joinToString("; ") { p ->
                val encoding = name(p.format)
                val rates = p.sampleRates.takeIf { it.isNotEmpty() }
                    ?.joinToString("/") { r -> "${r / 1000}k" }
                    .orEmpty()
                if (rates.isBlank()) encoding else "$encoding @ $rates"
            }
        }
    }

    private companion object {

        val PASSTHROUGH_FORMATS = listOf(
            PassthroughSpec(
                "Dolby Digital (AC-3)",
                AudioFormat.ENCODING_AC3,
                AudioFormat.CHANNEL_OUT_5POINT1,
                21,
                listOf("ac3", "dolby digital", "passthrough"),
            ),
            PassthroughSpec(
                "Dolby Digital Plus (E-AC-3)",
                AudioFormat.ENCODING_E_AC3,
                AudioFormat.CHANNEL_OUT_5POINT1,
                21,
                listOf("eac3", "dd+"),
            ),
            PassthroughSpec(
                "Dolby Atmos in E-AC-3 (JOC)",
                AudioFormat.ENCODING_E_AC3_JOC,
                AudioFormat.CHANNEL_OUT_5POINT1,
                28,
                listOf("atmos", "joc", "eac3"),
            ),
            PassthroughSpec(
                "Dolby AC-4",
                AudioFormat.ENCODING_AC4,
                AudioFormat.CHANNEL_OUT_STEREO,
                28,
                listOf("ac4"),
            ),
            PassthroughSpec(
                "Dolby TrueHD",
                AudioFormat.ENCODING_DOLBY_TRUEHD,
                AudioFormat.CHANNEL_OUT_7POINT1_SURROUND,
                25,
                listOf("truehd", "lossless"),
            ),
            PassthroughSpec(
                "Dolby MAT",
                AudioFormat.ENCODING_DOLBY_MAT,
                AudioFormat.CHANNEL_OUT_STEREO,
                29,
                listOf("mat", "atmos"),
            ),
            PassthroughSpec(
                "DTS",
                AudioFormat.ENCODING_DTS,
                AudioFormat.CHANNEL_OUT_5POINT1,
                23,
                listOf("dts"),
            ),
            PassthroughSpec(
                "DTS-HD",
                AudioFormat.ENCODING_DTS_HD,
                AudioFormat.CHANNEL_OUT_5POINT1,
                23,
                listOf("dts-hd", "dts hd"),
            ),
            PassthroughSpec(
                "DTS:X (UHD)",
                AudioFormat.ENCODING_DTS_UHD,
                AudioFormat.CHANNEL_OUT_5POINT1,
                31,
                listOf("dts:x", "dts uhd"),
            ),
            PassthroughSpec(
                "IEC 61937 (S/PDIF)",
                AudioFormat.ENCODING_IEC61937,
                AudioFormat.CHANNEL_OUT_STEREO,
                24,
                listOf("iec61937", "spdif", "optical"),
            ),
        )

        /** Channel mask label → (mask, API level the mask was added). */
        val CHANNEL_MASKS = listOf(
            "Mono" to (AudioFormat.CHANNEL_OUT_MONO to 1),
            "Stereo" to (AudioFormat.CHANNEL_OUT_STEREO to 1),
            "Quad" to (AudioFormat.CHANNEL_OUT_QUAD to 1),
            "5.1" to (AudioFormat.CHANNEL_OUT_5POINT1 to 1),
            "7.1 surround" to (AudioFormat.CHANNEL_OUT_7POINT1_SURROUND to 23),
            "5.1.2" to (AudioFormat.CHANNEL_OUT_5POINT1POINT2 to 32),
            "7.1.4" to (AudioFormat.CHANNEL_OUT_7POINT1POINT4 to 32),
            "9.1.6" to (AudioFormat.CHANNEL_OUT_9POINT1POINT6 to 32),
        )

        val CHANNEL_MASK_NAMES: Map<Int, String> = mapOf(
            AudioFormat.CHANNEL_OUT_MONO to "mono",
            AudioFormat.CHANNEL_OUT_STEREO to "stereo",
            AudioFormat.CHANNEL_OUT_QUAD to "quad",
            AudioFormat.CHANNEL_OUT_5POINT1 to "5.1",
            AudioFormat.CHANNEL_OUT_7POINT1_SURROUND to "7.1",
            AudioFormat.CHANNEL_IN_MONO to "mono in",
            AudioFormat.CHANNEL_IN_STEREO to "stereo in",
        )

        val ENCODINGS: Map<Int, String> = mapOf(
            AudioFormat.ENCODING_PCM_8BIT to "PCM 8-bit",
            AudioFormat.ENCODING_PCM_16BIT to "PCM 16-bit",
            AudioFormat.ENCODING_PCM_FLOAT to "PCM float",
            AudioFormat.ENCODING_AC3 to "AC-3",
            AudioFormat.ENCODING_E_AC3 to "E-AC-3",
            AudioFormat.ENCODING_DTS to "DTS",
            AudioFormat.ENCODING_DTS_HD to "DTS-HD",
            AudioFormat.ENCODING_MP3 to "MP3",
            AudioFormat.ENCODING_AAC_LC to "AAC LC",
            AudioFormat.ENCODING_AAC_HE_V1 to "AAC HE v1",
            AudioFormat.ENCODING_AAC_HE_V2 to "AAC HE v2",
            AudioFormat.ENCODING_IEC61937 to "IEC 61937",
            AudioFormat.ENCODING_DOLBY_TRUEHD to "Dolby TrueHD",
            AudioFormat.ENCODING_AAC_ELD to "AAC ELD",
            AudioFormat.ENCODING_AAC_XHE to "xHE-AAC",
            AudioFormat.ENCODING_AC4 to "AC-4",
            AudioFormat.ENCODING_E_AC3_JOC to "E-AC-3 JOC",
            AudioFormat.ENCODING_DOLBY_MAT to "Dolby MAT",
            AudioFormat.ENCODING_OPUS to "Opus",
            AudioFormat.ENCODING_PCM_24BIT_PACKED to "PCM 24-bit packed",
            AudioFormat.ENCODING_PCM_32BIT to "PCM 32-bit",
            AudioFormat.ENCODING_MPEGH_BL_L3 to "MPEG-H BL L3",
            AudioFormat.ENCODING_MPEGH_BL_L4 to "MPEG-H BL L4",
            AudioFormat.ENCODING_MPEGH_LC_L3 to "MPEG-H LC L3",
            AudioFormat.ENCODING_MPEGH_LC_L4 to "MPEG-H LC L4",
            AudioFormat.ENCODING_DTS_UHD to "DTS UHD",
            AudioFormat.ENCODING_DRA to "DRA",
        )

        val DEVICE_TYPES: Map<Int, String> = mapOf(
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE to "Earpiece",
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to "Built-in speaker",
            AudioDeviceInfo.TYPE_WIRED_HEADSET to "Wired headset",
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES to "Wired headphones",
            AudioDeviceInfo.TYPE_LINE_ANALOG to "Analogue line",
            AudioDeviceInfo.TYPE_LINE_DIGITAL to "Digital line",
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO to "Bluetooth SCO",
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to "Bluetooth A2DP",
            AudioDeviceInfo.TYPE_HDMI to "HDMI",
            AudioDeviceInfo.TYPE_HDMI_ARC to "HDMI ARC",
            AudioDeviceInfo.TYPE_USB_DEVICE to "USB device",
            AudioDeviceInfo.TYPE_USB_ACCESSORY to "USB accessory",
            AudioDeviceInfo.TYPE_DOCK to "Dock",
            AudioDeviceInfo.TYPE_FM to "FM",
            AudioDeviceInfo.TYPE_BUILTIN_MIC to "Built-in microphone",
            AudioDeviceInfo.TYPE_FM_TUNER to "FM tuner",
            AudioDeviceInfo.TYPE_TV_TUNER to "TV tuner",
            AudioDeviceInfo.TYPE_TELEPHONY to "Telephony",
            AudioDeviceInfo.TYPE_AUX_LINE to "Auxiliary line",
            AudioDeviceInfo.TYPE_IP to "IP",
            AudioDeviceInfo.TYPE_BUS to "Bus",
            AudioDeviceInfo.TYPE_USB_HEADSET to "USB headset",
            AudioDeviceInfo.TYPE_HEARING_AID to "Hearing aid",
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE to "Built-in speaker (safe)",
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX to "Remote submix",
            AudioDeviceInfo.TYPE_BLE_HEADSET to "Bluetooth LE headset",
            AudioDeviceInfo.TYPE_BLE_SPEAKER to "Bluetooth LE speaker",
            AudioDeviceInfo.TYPE_BLE_BROADCAST to "Bluetooth LE broadcast",
            AudioDeviceInfo.TYPE_HDMI_EARC to "HDMI eARC",
        )

        val STREAMS = listOf(
            "Media" to AudioManager.STREAM_MUSIC,
            "Ring" to AudioManager.STREAM_RING,
            "Alarm" to AudioManager.STREAM_ALARM,
            "Notification" to AudioManager.STREAM_NOTIFICATION,
            "Call" to AudioManager.STREAM_VOICE_CALL,
            "System" to AudioManager.STREAM_SYSTEM,
            "Accessibility" to AudioManager.STREAM_ACCESSIBILITY,
            "DTMF" to AudioManager.STREAM_DTMF,
        )
    }
}
