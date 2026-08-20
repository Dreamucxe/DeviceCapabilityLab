package com.devicelab.data.repo

import android.os.Build
import com.devicelab.core.model.CapabilityProfile
import com.devicelab.core.model.DeviceIdentity
import com.devicelab.core.model.Lab
import com.devicelab.core.model.LabReport
import com.devicelab.core.model.Section
import com.devicelab.data.detect.CapabilityDetector
import com.devicelab.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A scan in progress, reported per lab so the UI can show real progress. */
sealed interface ScanProgress {
    data object Idle : ScanProgress

    data class Running(val completed: Int, val total: Int, val current: Lab) : ScanProgress {
        val fraction: Float get() = if (total == 0) 0f else completed.toFloat() / total
    }

    data class Done(val profile: CapabilityProfile, val durationMillis: Long) : ScanProgress
}

/**
 * Runs the detectors and caches the result.
 *
 * Detection is sequential, not parallel. It looks like an obvious candidate for
 * `async` over fifteen labs, and it is deliberately not: several detectors take
 * exclusive platform resources -- CameraDetector opens the camera characteristics
 * service, DrmDetector instantiates and releases real DRM plugin instances, and
 * AudioDetector queries the audio HAL -- and OEM implementations of those are not
 * reliably safe to enter concurrently. A hang in a vendor DRM plugin while the camera
 * service is mid-query is a class of failure that is very hard to diagnose and that
 * the user experiences as a frozen app. Sequential detection costs a second or two on
 * a first scan and removes that entire category. Progress is emitted per lab so the
 * wait is legible.
 *
 * The result is cached in memory for the process lifetime because Section 28 requires
 * static capability information to be cached during a scan, and because none of it can
 * change while the app is running: a hardware feature list is fixed when the system
 * server starts, and an Android version change means a reboot, which ends the process.
 * [refresh] exists for the pull-to-rescan action, which is about the user's confidence
 * rather than about the data having changed.
 */
@Singleton
class CapabilityRepository @Inject constructor(
    // `@JvmSuppressWildcards` is load-bearing, not decoration. Kotlin compiles a
    // `List<CapabilityDetector>` parameter to `List<? extends CapabilityDetector>`, and
    // Dagger matches bindings on the exact Java type -- so without it the module provides
    // `List<CapabilityDetector>` and this asks for a wildcard type nothing satisfies.
    private val detectors: List<@JvmSuppressWildcards CapabilityDetector>,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val clock: Clock,
) {

    @Volatile
    private var cached: CapabilityProfile? = null

    val identity: DeviceIdentity
        get() = DeviceIdentity(
            manufacturer = Build.MANUFACTURER.orEmpty().ifBlank { "Unknown" },
            model = Build.MODEL.orEmpty().ifBlank { "Unknown" },
            device = Build.DEVICE.orEmpty().ifBlank { "Unknown" },
            androidRelease = Build.VERSION.RELEASE.orEmpty().ifBlank { "Unknown" },
            apiLevel = Build.VERSION.SDK_INT,
            fingerprint = Build.FINGERPRINT.orEmpty().ifBlank { "Unknown" },
        )

    /** The cached profile, or null if nothing has been scanned yet. */
    fun cachedProfile(): CapabilityProfile? = cached

    /**
     * Runs every detector, reporting progress through [onProgress].
     *
     * A detector that throws does not abort the scan. Each is individually guarded, and
     * a failure becomes a [LabReport] carrying a note that says which lab failed and
     * why. Section 29 requires that a missing hardware feature never crashes the app;
     * the same must hold for a vendor implementation that throws where the
     * documentation promises a value, which is common enough that treating it as fatal
     * would make the app unusable on exactly the devices most worth inspecting.
     */
    suspend fun scan(onProgress: (ScanProgress) -> Unit = {}): CapabilityProfile =
        withContext(io) {
            val started = clock.elapsedMillis()
            val ordered = detectors.sortedBy { it.lab.ordinal }
            val reports = ArrayList<LabReport>(ordered.size)
            ordered.forEachIndexed { index, detector ->
                onProgress(ScanProgress.Running(index, ordered.size, detector.lab))
                reports += runCatching { detector.detect() }
                    .getOrElse { failed(detector.lab, it) }
            }
            val profile = CapabilityProfile(clock.wallClockMillis(), reports)
            cached = profile
            onProgress(ScanProgress.Done(profile, clock.elapsedMillis() - started))
            profile
        }

    /** The cached profile, scanning only if there is none. */
    suspend fun profile(onProgress: (ScanProgress) -> Unit = {}): CapabilityProfile =
        cached ?: scan(onProgress)

    suspend fun refresh(onProgress: (ScanProgress) -> Unit = {}): CapabilityProfile =
        scan(onProgress)

    /**
     * The report for a lab whose detector threw.
     *
     * It carries the exception class and message, not a generic apology. A reader
     * seeing "DrmDetector: NoClassDefFoundError" learns something true about their
     * device; "could not read DRM information" teaches them nothing.
     */
    private fun failed(lab: Lab, cause: Throwable): LabReport = LabReport(
        lab = lab,
        sections = listOf(
            Section(
                id = "error",
                title = "Detection failed",
                subtitle = "This lab could not be read on this device",
            )
        ),
        notes = listOf(
            "The ${lab.title} detector raised " + cause.javaClass.simpleName +
                (cause.message?.let { ": ${it.take(200)}" } ?: "") +
                ". Nothing is shown for this lab rather than a guess at what it " +
                "would have reported."
        ),
    )
}

/**
 * Time, behind an interface.
 *
 * Both readings are needed and they are not interchangeable. [wallClockMillis] labels
 * a snapshot, and must be the real date even though the user can change it.
 * [elapsedMillis] measures the scan, and must be monotonic -- a clock adjustment
 * mid-scan would otherwise produce a negative duration.
 */
interface Clock {
    fun wallClockMillis(): Long
    fun elapsedMillis(): Long
}

@Singleton
class SystemClock @Inject constructor() : Clock {
    override fun wallClockMillis(): Long = System.currentTimeMillis()
    override fun elapsedMillis(): Long = android.os.SystemClock.elapsedRealtime()
}
