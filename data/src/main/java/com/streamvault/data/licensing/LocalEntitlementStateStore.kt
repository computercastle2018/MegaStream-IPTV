package com.MegaStream.data.licensing

import com.MegaStream.domain.licensing.ClockReading
import com.MegaStream.domain.licensing.LicenseAccessState
import com.MegaStream.domain.licensing.OfflineLease
import com.MegaStream.domain.licensing.OnlineEntitlementDecision
import com.MegaStream.domain.licensing.TrustedTimeAnchor

/** One atomic persistence unit. Only authenticated leases belong in this trusted local store. */
data class LocalEntitlementSnapshot(
    val lease: OfflineLease? = null,
    val anchor: TrustedTimeAnchor? = null,
    val authenticatedDenial: LicenseAccessState? = null,
    val lastClockReading: ClockReading? = null,
    val verificationRequired: Boolean = false,
    val trustedTimeHighWaterEpochSeconds: Long? = null,
    /** Authenticated denial metadata; a missing identity retains the known license and revision. */
    val onlineDenial: OnlineEntitlementDecision? = null,
)

/**
 * Persistence-neutral, synchronous storage for the entitlement gate.
 *
 * Implementations must atomically replace the whole snapshot, protect persisted data from tampering,
 * and propagate write failures. All entitlement instances sharing backing storage must share this
 * store instance: its monitor serializes their read/validate/write transactions. No network or
 * suspending work is performed while holding that monitor.
 */
interface LocalEntitlementStateStore {
    fun read(): LocalEntitlementSnapshot
    fun write(snapshot: LocalEntitlementSnapshot)
}

/** Thread-safe fake; retaining this instance models persistence across entitlement recreation. */
class InMemoryLocalEntitlementStateStore(
    initialSnapshot: LocalEntitlementSnapshot = LocalEntitlementSnapshot(),
) : LocalEntitlementStateStore {
    private var snapshot = initialSnapshot

    @Synchronized
    override fun read(): LocalEntitlementSnapshot = snapshot

    @Synchronized
    override fun write(snapshot: LocalEntitlementSnapshot) {
        this.snapshot = snapshot
    }
}
