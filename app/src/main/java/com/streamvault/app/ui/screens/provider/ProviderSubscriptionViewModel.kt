package com.MegaStream.app.ui.screens.provider

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.MegaStream.app.R
import com.MegaStream.app.controlplane.ControlPlaneClient
import com.MegaStream.app.controlplane.ControlPlaneResult
import com.MegaStream.app.controlplane.DeviceExperienceRepository
import com.MegaStream.app.controlplane.InstallationCredentials
import com.MegaStream.app.controlplane.integration.AndroidProviderAssignmentSink
import com.MegaStream.app.controlplane.integration.ProductionRuntime
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProviderSubscriptionState(
    val busy: Boolean = false,
    val redeemed: Boolean = false,
    val success: Boolean = false,
    val error: Int? = null,
)

@HiltViewModel
class ProviderSubscriptionViewModel @Inject constructor(
    private val credentials: Provider<InstallationCredentials>,
    private val runtime: ProductionRuntime,
    private val assignments: AndroidProviderAssignmentSink,
    private val experience: DeviceExperienceRepository,
) : ViewModel() {
    private val client = ControlPlaneClient()
    private val mutableState = MutableStateFlow(ProviderSubscriptionState())
    val state = mutableState.asStateFlow()

    fun redeem(code: String) {
        val current = state.value
        if (current.busy || current.success) return
        if (!experience.state.value.allowSubscriptionDetails) return
        if (!current.redeemed && (code.trim().length !in 1..64 || code.trim().any { it.isISOControl() })) {
            mutableState.value = current.copy(error = R.string.provider_code_invalid)
            return
        }
        mutableState.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                runtime.awaitStartup()
                withContext(Dispatchers.IO) {
                    val identity = credentials.get()
                    if (!current.redeemed) {
                        when (val reply = client.redeemProvider(identity.credential, code)) {
                            is ControlPlaneResult.Failure -> {
                                mutableState.value = state.value.copy(busy = false, error = when {
                                    reply.error.status == 403 -> R.string.provider_code_unlicensed
                                    reply.error.status in listOf(400, 404, 409, 422) || reply.error.code == "invalid_request" -> R.string.provider_code_invalid
                                    else -> R.string.provider_code_failed
                                })
                                return@withContext
                            }
                            is ControlPlaneResult.Success -> mutableState.value = state.value.copy(redeemed = true)
                        }
                    }
                    when (val batch = client.getProviderAssignments(identity.credential, 0)) {
                        is ControlPlaneResult.Success -> {
                            check(batch.value.assignments.isNotEmpty()) { "Subscription not available yet" }
                            assignments.apply(identity.installationId, batch.value)
                        }
                        is ControlPlaneResult.Failure -> error("Assignments unavailable")
                    }
                    runtime.refreshLicense()
                    mutableState.value = state.value.copy(busy = false, success = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(busy = false, error =
                    if (state.value.redeemed) R.string.provider_code_sync_failed else R.string.provider_code_failed)
            }
        }
    }
}
