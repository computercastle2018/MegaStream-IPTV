package com.MegaStream.app.kiosk.integration

import com.MegaStream.app.controlplane.DevicePolicy as WireDevicePolicy
import com.MegaStream.app.controlplane.KioskMode as WireKioskMode
import com.MegaStream.app.controlplane.integration.ProductionRuntime
import com.MegaStream.app.kiosk.DevicePolicySnapshot
import com.MegaStream.app.kiosk.KioskMode as LocalKioskMode
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Feeds the authenticated control-plane device policy into the kiosk coordinator. The runtime
 * snapshot default is already OFF with local exit allowed, so a missing heartbeat, transport
 * failure, or disabled installation can never trap a user behind kiosk mode.
 */
class RuntimeKioskPolicySource @Inject constructor(
    runtime: ProductionRuntime,
) : KioskPolicySource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val policy: StateFlow<KioskPolicy?> = runtime.snapshot
        .map { snapshot -> snapshot.devicePolicy.toLocal() }
        .stateIn(scope, SharingStarted.Eagerly, null)
}

private fun WireDevicePolicy.toLocal(): DevicePolicySnapshot = DevicePolicySnapshot(
    kioskMode = when (kioskMode) {
        WireKioskMode.OFF -> LocalKioskMode.OFF
        WireKioskMode.PLAYBACK -> LocalKioskMode.PLAYBACK
        WireKioskMode.ALWAYS -> LocalKioskMode.ALWAYS
    },
    allowLocalExit = allowLocalExit,
)
