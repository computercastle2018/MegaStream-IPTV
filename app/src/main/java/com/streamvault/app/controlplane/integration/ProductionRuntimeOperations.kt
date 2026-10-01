package com.MegaStream.app.controlplane.integration

import com.MegaStream.app.controlplane.HeartbeatMode
import com.MegaStream.app.controlplane.runtime.RuntimeCoordinator
import com.MegaStream.app.controlplane.runtime.RuntimeMetadata
import com.MegaStream.app.controlplane.runtime.RuntimeResult
import com.MegaStream.domain.licensing.AppEntitlement
import com.MegaStream.domain.licensing.LicenseAccessDecision
import kotlinx.coroutines.CancellationException

/** Keeps registration retries stable while heartbeats observe current device metadata. */
class ProductionRuntimeOperations(
    private val installationId: String,
    private val coordinator: RuntimeCoordinator,
    private val stores: DurableRuntimeStores,
    private val entitlement: AppEntitlement,
    private val metadata: () -> RuntimeMetadata,
    private val uploadLocalSubscriptions: suspend () -> Unit = {},
) : RuntimeControllerOperations {
    override suspend fun register(): RuntimeResult =
        coordinator.register(stores.registrationMetadata(installationId, metadata()))

    override suspend fun heartbeat(appSessionId: String, mode: HeartbeatMode): RuntimeResult {
        val result = coordinator.heartbeat(metadata(), appSessionId, mode)
        if (result is RuntimeResult.HeartbeatApplied) {
            try { uploadLocalSubscriptions() }
            catch (cancelled: CancellationException) { throw cancelled }
            // Inventory failure must not alter the playback gate; retry on the next heartbeat.
            catch (_: Exception) {
                java.util.logging.Logger.getLogger("MegaStream").warning("Local subscription report failed; retrying on next heartbeat")
            }
        }
        return result
    }

    override fun gate(): LicenseAccessDecision = entitlement.gate()
}
