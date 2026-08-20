package com.devicelab.data.detect

import com.devicelab.core.model.LabReport

/**
 * One capability domain's detection logic.
 *
 * Implementations touch the platform and nothing else -- no Compose, no ViewModel,
 * no repository. That keeps every Android quirk in a single testable place and is
 * what lets the same detector feed the dashboard, the matrix, search, exports and
 * snapshot diffing without duplicating the queries.
 *
 * [detect] is called from a background dispatcher and may block.
 */
interface CapabilityDetector {
    val lab: com.devicelab.core.model.Lab
    suspend fun detect(): LabReport
}
