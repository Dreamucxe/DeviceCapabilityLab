package com.devicelab.di

import android.content.Context
import com.devicelab.core.detect.Probe
import com.devicelab.data.db.DeviceLabDatabase
import com.devicelab.data.db.SnapshotDao
import com.devicelab.data.detect.AudioDetector
import com.devicelab.data.detect.CameraDetector
import com.devicelab.data.detect.CapabilityDetector
import com.devicelab.data.detect.CodecDetector
import com.devicelab.data.detect.ConnectivityDetector
import com.devicelab.data.detect.CpuDetector
import com.devicelab.data.detect.DisplayDetector
import com.devicelab.data.detect.DrmDetector
import com.devicelab.data.detect.FeaturesDetector
import com.devicelab.data.detect.GraphicsDetector
import com.devicelab.data.detect.MemoryDetector
import com.devicelab.data.detect.PlatformDetector
import com.devicelab.data.detect.SecurityDetector
import com.devicelab.data.detect.SensorDetector
import com.devicelab.data.detect.StorageDetector
import com.devicelab.data.detect.UsbDetector
import com.devicelab.data.export.ReportExporter
import com.devicelab.data.repo.Clock
import com.devicelab.data.repo.SystemClock
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * The device's API level, wrapped so detectors can be tested against any level.
     *
     * One instance for the whole graph: it is immutable and holds only an Int, and
     * sharing it means a test that substitutes a different API level changes every
     * detector at once rather than half of them.
     */
    @Provides
    @Singleton
    fun probe(): Probe = Probe()

    /**
     * Where blocking work runs.
     *
     * [Dispatchers.IO] rather than [Dispatchers.Default] because detection is dominated
     * by binder round-trips to system services and by reads of `/proc` and `/sys`, not
     * by computation. Those block a thread while waiting, which is exactly what the IO
     * pool's larger thread count exists for -- running them on Default would tie up the
     * CPU-bound pool for the length of a camera or DRM query.
     */
    @Provides
    @Singleton
    @IoDispatcher
    fun io(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @MainDispatcher
    fun main(): CoroutineDispatcher = Dispatchers.Main.immediate

    /**
     * The scope the device scan runs in.
     *
     * [SupervisorJob] so that a failure in one launched scan cannot cancel the scope and
     * take every future scan with it. It is never cancelled: its lifetime is the
     * process, and a process that is going away does not need its coroutines tidied.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(@IoDispatcher io: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + io)

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): DeviceLabDatabase =
        DeviceLabDatabase.build(context)

    @Provides
    fun snapshotDao(database: DeviceLabDatabase): SnapshotDao = database.snapshotDao()

    @Provides
    @Singleton
    fun reportExporter(): ReportExporter = ReportExporter()

    /**
     * The filesystem seam `CpuDetector` reads `/proc` and `/sys` through.
     *
     * Bound explicitly even though the constructor parameter has a Kotlin default:
     * Dagger generates a call to the full constructor and does not see defaults, so
     * without this the graph would simply fail to compile. Making it a real binding is
     * the better outcome anyway -- an instrumented test can swap in fixtures.
     */
    @Provides
    @Singleton
    fun sysFs(): CpuDetector.SysFs = CpuDetector.SysFs.Real

    /**
     * Every detector, in report order.
     *
     * An ordered [List] rather than Dagger's multibound `Set`, because a set has no
     * order and the report, the export and the scan progress all need one. Naming the
     * fifteen here also means a detector cannot be silently left out of the scan by
     * forgetting an `@IntoSet` -- adding a lab to the enum and not to this list is a
     * visible omission in one place.
     *
     * The order matches [com.devicelab.core.model.Lab] declaration order, and
     * `CapabilityRepository` sorts by it again so the two cannot drift.
     */
    @Provides
    @Singleton
    fun detectors(
        platform: PlatformDetector,
        display: DisplayDetector,
        graphics: GraphicsDetector,
        cpu: CpuDetector,
        memory: MemoryDetector,
        storage: StorageDetector,
        camera: CameraDetector,
        codec: CodecDetector,
        audio: AudioDetector,
        sensors: SensorDetector,
        connectivity: ConnectivityDetector,
        usb: UsbDetector,
        security: SecurityDetector,
        drm: DrmDetector,
        features: FeaturesDetector,
    ): List<CapabilityDetector> = listOf(
        platform, display, graphics, cpu, memory, storage, camera, codec,
        audio, sensors, connectivity, usb, security, drm, features,
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ClockModule {
    @Binds
    @Singleton
    abstract fun clock(impl: SystemClock): Clock
}
