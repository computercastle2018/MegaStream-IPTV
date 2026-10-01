package com.MegaStream.app.playback.gate

import com.MegaStream.app.controlplane.ActivationCodeResponse
import com.MegaStream.app.controlplane.ActivationStatusResponse
import com.MegaStream.app.controlplane.ControlPlaneClient
import com.MegaStream.app.controlplane.ControlPlaneError
import com.MegaStream.app.controlplane.ControlPlaneResult
import com.MegaStream.app.controlplane.EntitlementResponse
import com.MegaStream.app.controlplane.InstallationCredentials
import com.MegaStream.app.controlplane.integration.ProductionRuntime
import com.MegaStream.app.controlplane.runtime.EntitlementResponseMapper
import com.MegaStream.app.ui.screens.license.LicenseActivationPort
import com.MegaStream.data.licensing.LocalAppEntitlement
import com.MegaStream.domain.licensing.AppEntitlement
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Transport hints never grant playback; only the existing installation-bound verifier can. */
@Singleton
class ControlPlaneLicenseActivationPort internal constructor(
    private val client: ControlPlaneClient,
    private val credentials: Provider<InstallationCredentials>,
    private val entitlement: Provider<out AppEntitlement>,
    private val refreshVerifiedDecision: suspend () -> Unit,
) : LicenseActivationPort {
    @Inject constructor(
        credentials: Provider<InstallationCredentials>,
        entitlement: Provider<LocalAppEntitlement>,
        runtime: ProductionRuntime,
        gate: PlaybackGate,
    ) : this(ControlPlaneClient(), credentials, entitlement, {
        runtime.refreshEntitlementAfterActivation()
        gate.checkNow()
    })

    override suspend fun activateDirect(rawKey: String): ControlPlaneResult<EntitlementResponse> = ioSafe {
        when (val reply = client.activate(credentials.get().credential, rawKey)) {
            is ControlPlaneResult.Success -> if (applyAuthenticated(reply.value)) reply else invalidResponse()
            is ControlPlaneResult.Failure -> reply
        }
    }

    override suspend fun requestCode(): ControlPlaneResult<ActivationCodeResponse> = ioSafe {
        client.requestActivationCode(credentials.get().credential)
    }

    override suspend fun pollCode(code: String, pollToken: String): ControlPlaneResult<ActivationStatusResponse> = ioSafe {
        when (val reply = client.pollActivationCode(credentials.get().credential, code, pollToken)) {
            is ControlPlaneResult.Failure -> reply
            is ControlPlaneResult.Success -> when (val status = reply.value) {
                is ActivationStatusResponse.Pending -> reply
                is ActivationStatusResponse.Activated -> if (applyAuthenticated(status.response)) reply else invalidResponse()
            }
        }
    }

    private suspend fun applyAuthenticated(response: EntitlementResponse): Boolean {
        currentCoroutineContext().ensureActive()
        val decision = EntitlementResponseMapper().map(response) ?: return false
        // The singleton already binds both installation ID and credential digest; no new store or
        // credentials are created here. The mapper validates metadata, not cryptographic evidence.
        entitlement.get().applyOnlineDecision(decision, response.lease)
        refreshVerifiedDecision()
        return true
    }

    /** Provider/transport failures cross this UI boundary only as a closed, redacted vocabulary. */
    private suspend fun <T> ioSafe(operation: suspend () -> ControlPlaneResult<T>): ControlPlaneResult<T> =
        withContext(Dispatchers.IO) {
            try {
                currentCoroutineContext().ensureActive()
                operation().also { currentCoroutineContext().ensureActive() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException("Activation interrupted")
            } catch (_: IOException) {
                ControlPlaneResult.Failure(ControlPlaneError.local("network_error"))
            } catch (_: Exception) {
                // Storage and platform providers can include secrets in exception messages.
                ControlPlaneResult.Failure(ControlPlaneError.local("invalid_response"))
            }
        }

    private fun invalidResponse() = ControlPlaneResult.Failure(ControlPlaneError.local("invalid_response"))
    override fun toString(): String = "ControlPlaneLicenseActivationPort([REDACTED])"
}
