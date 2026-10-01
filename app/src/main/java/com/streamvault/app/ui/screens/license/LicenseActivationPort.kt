package com.MegaStream.app.ui.screens.license

import com.MegaStream.app.controlplane.ActivationCodeResponse
import com.MegaStream.app.controlplane.ActivationStatusResponse
import com.MegaStream.app.controlplane.ControlPlaneResult
import com.MegaStream.app.controlplane.EntitlementResponse

/**
 * Transport seam only. Implementations must be cancellable and IO-safe: a blocking
 * ControlPlaneClient call belongs on an IO dispatcher, never on the caller's UI dispatcher.
 * Do not log or retain credentials, response bodies, exception messages, or regenerate device
 * credentials. An adapter may independently feed authenticated responses to the runtime; this
 * UI keeps only a transport-state enum and never treats a response as verified authorization.
 */
interface LicenseActivationPort {
    /** The transport requires an immutable String, whose lifetime cannot be securely erased on JVM. */
    suspend fun activateDirect(rawKey: String): ControlPlaneResult<EntitlementResponse>
    suspend fun requestCode(): ControlPlaneResult<ActivationCodeResponse>
    suspend fun pollCode(code: String, pollToken: String): ControlPlaneResult<ActivationStatusResponse>
}
