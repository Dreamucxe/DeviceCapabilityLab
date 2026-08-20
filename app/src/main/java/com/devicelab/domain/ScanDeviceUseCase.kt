package com.devicelab.domain

import com.devicelab.core.model.CapabilityMatrix
import com.devicelab.core.model.CapabilityProfile
import com.devicelab.data.repo.CapabilityRepository
import com.devicelab.data.repo.ScanProgress
import javax.inject.Inject

/**
 * Runs a capability scan.
 *
 * A named use case rather than a repository call from the ViewModel, so that "scan the
 * device" is one thing the app can do, with one place that decides whether a cached
 * result will do.
 */
class ScanDeviceUseCase @Inject constructor(
    private val repository: CapabilityRepository,
) {
    /** The existing scan if there is one, otherwise a fresh scan. */
    suspend operator fun invoke(
        force: Boolean = false,
        onProgress: (ScanProgress) -> Unit = {},
    ): CapabilityProfile =
        if (force) repository.refresh(onProgress) else repository.profile(onProgress)

    fun cached(): CapabilityProfile? = repository.cachedProfile()

    val identity get() = repository.identity
}

/**
 * Builds the capability matrix for a profile.
 *
 * Separate from the profile itself because the matrix is a *view* -- it flattens the
 * section tree and drops measurements -- and derived views belong outside the model
 * they are derived from.
 */
class BuildMatrixUseCase @Inject constructor() {
    operator fun invoke(profile: CapabilityProfile): CapabilityMatrix =
        CapabilityMatrix.of(profile)
}
