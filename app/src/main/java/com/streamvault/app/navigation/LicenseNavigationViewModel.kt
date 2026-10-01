package com.MegaStream.app.navigation

import androidx.lifecycle.ViewModel
import com.MegaStream.app.controlplane.integration.ProductionRuntime
import com.MegaStream.app.playback.gate.ControlPlaneLicenseActivationPort
import com.MegaStream.app.playback.gate.PlaybackGate
import com.MegaStream.app.playback.gate.PlaybackGateVerdict
import com.MegaStream.app.ui.screens.license.LicenseActivationViewModel
import com.MegaStream.app.ui.screens.license.LicenseEntitlementSnapshot
import com.MegaStream.domain.licensing.LicenseAccessState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/** Activity-owned routing state, deliberately without SavedStateHandle or a serialized request. */
@HiltViewModel
class LicenseNavigationViewModel @Inject constructor(
    private val gate: PlaybackGate,
    private val activationPort: ControlPlaneLicenseActivationPort,
    private val runtime: ProductionRuntime,
) : ViewModel() {
    internal val routing = LicensePlaybackRouting()
    val decision = gate.decision
    val blocked = gate.blocked
    val runtimeSnapshot = runtime.snapshot

    fun checkNow(): PlaybackGateVerdict = gate.checkNow()

    suspend fun awaitStartup() = runtime.awaitStartup()

    internal fun request(intent: PlaybackNavigationIntent): Boolean {
        val verdict = checkNow()
        val allowed = routing.request(intent, verdict)
        if (verdict is PlaybackGateVerdict.Blocked) gate.reportBlocked(verdict)
        return allowed
    }

    internal fun retry(): PlaybackNavigationIntent? = routing.retry(checkNow())

    internal fun suspendActive(route: String?) = routing.suspendActive(route)

    fun cancel() {
        routing.cancel()
        gate.clearBlocked()
    }

    fun consumeBlocked() = gate.clearBlocked()

    /** Each composition gets a new instance: LicenseActivationScreen disposes its model on exit. */
    fun createActivationModel(scope: CoroutineScope): LicenseActivationViewModel =
        LicenseActivationViewModel(activationPort, scope, verifiedEntitlement())

    fun verifiedEntitlement(): LicenseEntitlementSnapshot = checkNow().asLicenseEntitlement()

    override fun onCleared() {
        routing.cancel()
    }
}

internal fun PlaybackGateVerdict.asLicenseEntitlement(): LicenseEntitlementSnapshot =
    LicenseEntitlementSnapshot(
        state = when (this) {
            is PlaybackGateVerdict.Allowed -> LicenseAccessState.ALLOWED
            is PlaybackGateVerdict.Blocked -> reason
        },
        // RuntimeSnapshot has an offline deadline, NOT a verified license expiry.
        expiresAtEpochSeconds = null,
    )
