# Device Capability Lab

An Android hardware and software capability inspector. It answers one question as
precisely as the platform allows — **what can this device actually do?** — and it says
"Not exposed by Android" rather than guessing when the platform will not answer.

<p align="center">
  <a href="https://github.com/Dreamucxe/DeviceCapabilityLab/releases/latest/download/DeviceCapabilityLab.apk">
    <img src="https://img.shields.io/badge/Download-APK%20·%20v1.0-1f6feb?style=for-the-badge&logo=android&logoColor=white" alt="Download the APK">
  </a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/min%20SDK-26-informational?style=flat-square" alt="minSdk 26">
  <img src="https://img.shields.io/badge/target%20SDK-34-informational?style=flat-square" alt="targetSdk 34">
  <img src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin 2.0.21">
  <img src="https://img.shields.io/badge/Compose-Material%203-4285F4?style=flat-square" alt="Jetpack Compose, Material 3">
  <img src="https://img.shields.io/badge/permissions-2%20normal-success?style=flat-square" alt="Two normal permissions">
  <img src="https://img.shields.io/badge/network-none-success?style=flat-square" alt="No network access">
</p>

---

## The one rule

Every value shown comes from a real Android API. Nothing is inferred from the model name,
looked up in a table of known chipsets, or filled in with a plausible number.

Most device-info apps quietly blur three very different situations into a single "not
supported". This one keeps them apart, because the difference is usually the interesting
part:

| What you see | What it means |
| --- | --- |
| **Queried — not supported by this hardware** | The API answered, and the answer was no. |
| **Requires API 34+ — this device is running API 28** | The hardware might well have it. The running Android version has no API to ask. |
| **Not exposed by Android** | No API exists at any level. Named anyway, with the reason. |
| **Query failed — no determinate answer** | The call ran and returned nothing usable. Not turned into a "no". |
| **Restricted — SecurityException** | A permission or platform restriction refused the call. |
| **Available but deliberately not read** | Readable, but it identifies your device rather than describing it. Refused on purpose, and recorded. |

That last distinction matters more than it looks. `PackageManager.hasSystemFeature()`
takes a string, and asking an Android 9 device about `android.hardware.uwb` — a name
Android did not define until API 34 — returns `false` instantly and cheerfully. That
`false` is not a fact about the hardware; it is the platform not recognising the question.
Every feature row in this app is gated on the API level that introduced its *constant*, so
a device older than the name says so instead of answering no.

## What it inspects

Fifteen labs, each backed by the real system service rather than a lookup table:

| Lab | Source |
| --- | --- |
| **Android Platform** | `Build`, `Build.VERSION`, security patch level, ABIs, kernel, VNDK/Treble |
| **Display** | `Display.Mode`, refresh rates, `getHdrCapabilities()`, wide-colour gamut, cutout |
| **Graphics** | The live GL/EGL context's own renderer and version strings, Vulkan feature levels, deqp conformance |
| **CPU** | `/proc/cpuinfo`, `/sys/devices/system/cpu`, per-cluster frequency ceilings, ABI list |
| **Memory** | `ActivityManager.MemoryInfo`, memory class, large-heap limit, `/proc/meminfo` |
| **Storage** | `StatFs`, `StorageStatsManager`, every `StorageVolume`, filesystem type from `/proc/mounts` |
| **Cameras** | Full `CameraCharacteristics` per camera: hardware level, RAW, focal lengths, sensor size, ISO range, stabilisation, physical sub-cameras |
| **Media Codecs** | `MediaCodecList` — every encoder and decoder, with profiles, levels, resolutions, frame rates and hardware/software backing |
| **Audio** | `AudioManager` properties, output/input device list, low-latency and pro-audio classes, offload and spatializer support |
| **Sensors** | `SensorManager.getSensorList()` — every sensor with its real range, resolution, power draw and wake-up flag |
| **Connectivity** | `WifiManager` band and standard support, `ConnectivityManager` transports, Bluetooth adapter capabilities |
| **USB** | Host and accessory mode, currently attached devices and their interfaces |
| **Biometrics & Security** | Biometric strength class, keystore backing (TEE / StrongBox), verified boot state, encryption type |
| **DRM** | `MediaDrm` — supported schemes, Widevine security level and HDCP level |
| **Hardware Features** | Every `PackageManager.FEATURE_*` flag, API-gated, plus the vendor features no AOSP constant names |

## Features

- **Dashboard scorecard** across eight domains — Display, Graphics, Camera, Audio,
  Connectivity, Sensors, Security, Media — each marked *fully supported*, *partially
  supported*, *not exposed* or *unsupported*. There is no invented "performance score":
  a number like that would be a guess dressed up as a measurement.
- **Capability matrix** — one flat, filterable grid of every check, with the reason
  attached to each result.
- **Snapshots** — save a scan, rename it, and compare two. The diff reports
  *added / removed / changed / unchanged*, which is how you see what an OS upgrade
  actually gave you.
- **Export** to JSON, plain text or HTML. The HTML report is a single self-contained
  file that loads no fonts, scripts or stylesheets from anywhere.
- **Instant search** across every value, API name and explanation. `Vulkan`, `AV1`,
  `120Hz`, `RAW`, `Wi-Fi 6`, `Gyroscope`, `HDR` all land where you would expect.
- **Provenance on every row** — the exact API call behind the value, shown next to it, so
  any claim in the app can be checked against the platform documentation.

## Privacy

- **No `INTERNET` permission.** Not "does not use the network" — the app is structurally
  incapable of reaching it.
- **Two permissions, both normal-protection**: `ACCESS_WIFI_STATE` and
  `ACCESS_NETWORK_STATE`. Both are granted at install time, so the app has no runtime
  permission prompt anywhere.
- **No location, Bluetooth-scan, camera, microphone or storage permissions.** Camera2
  characteristics, audio device lists and Bluetooth adapter capabilities are all readable
  without them. The permissions those would unlock — SSID, scan results, paired-device
  names — describe *where you are and who you know*, not what the hardware can do.
- **No root, no Shizuku, no ADB, no accounts, no analytics.** Nothing to sign in to.
- The Security lab reads capability *classifications* only: whether fingerprint hardware
  exists, whether the keystore is hardware-backed, whether biometrics meet
  `BIOMETRIC_STRONG`. It never touches biometric templates or key material — those are
  non-exportable by hardware design, so there is no API path to them regardless of
  permissions. Exports contain no device identifiers.

## Install

Android 8.0 (API 26) or newer, any ABI.

**[⬇ Download the APK](https://github.com/Dreamucxe/DeviceCapabilityLab/releases/latest/download/DeviceCapabilityLab.apk)**

The release is signed with a developer key, so Android will ask you to allow installs from
your browser or file manager the first time.

## Build

```bash
git clone https://github.com/Dreamucxe/DeviceCapabilityLab.git
cd DeviceCapabilityLab
echo "sdk.dir=$ANDROID_HOME" > local.properties
chmod +x gradlew
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`. Without a `keystore.properties` it is
unsigned — add one with `storeFile`, `storePassword`, `keyAlias` and `keyPassword` to have
Gradle sign it.

Run the test suite with `./gradlew testReleaseUnitTest`.

## Architecture

Kotlin, Jetpack Compose, Material 3, MVVM, Hilt, Room, coroutines and `StateFlow`
throughout. UI → ViewModel → use cases → repositories → Android system APIs, with hardware
detection kept entirely out of the UI layer.

```
core/detect     Probe — the single gate every reading passes through
core/model      Fact, Provenance, Support, Lab, the scorecard and the diff
data/detect     Fifteen detectors, one per lab
data/db         Room snapshot storage
data/export     JSON, text and HTML renderers
domain          Scan, search, compare and export use cases
ui              Five tabs, the design system, and the lab screens
```

`Probe` is the interesting piece. Every value in the app is read through it, and it is
what makes the honesty rules structural rather than a matter of discipline: it checks the
API gate *before* running the read, catches `SecurityException` separately from failure,
distinguishes a null answer from a negative one, and refuses to turn "no determinate
answer" into "not supported". API-gated calls live in separate objects so a class that
cannot exist on the running version is never loaded, let alone verified.

299 JVM unit tests cover the parsers, the classification rules, the diff and the export
renderers. They target the honesty rules specifically — that `NOT_EXPOSED` never collapses
into `UNSUPPORTED`, that a null flag never becomes a "no", that an unset system property
never reads as `false`, that CPU clusters are never derived from core counts, that an HTML
export fetches nothing.

## Known limits

Stated rather than hidden, because a capability tool that overstates its own reach is the
thing it is meant to replace:

- **GPU model names** come from the live GL context, which is the only place Android
  exposes them. There is no API for the GPU's clock speed, core count or memory, so none
  is shown.
- **CPU frequencies** are the kernel's scaling ceilings from sysfs, not measured clocks.
  Modern kernels stopped putting model names and MHz in `/proc/cpuinfo` on arm64, so those
  fields are often simply absent.
- **A feature flag is a declaration, not a measurement.** A device can ship a barometer and
  neglect to declare `android.hardware.sensor.barometer`. Where a flag and a dedicated lab
  disagree, the lab that queried the hardware is the one to trust, and the app says so.
- **Codec claims are the codec's own.** `MediaCodecList` reports what each codec advertises;
  actually exercising every profile at every resolution would take minutes and heat the
  device.
- **OEM builds lie occasionally.** Where two platform sources contradict each other, both
  are shown rather than one being picked as the truth.

## Licence

MIT — see [LICENSE](LICENSE).
