package com.MegaStream.app.kiosk.integration

import com.MegaStream.app.kiosk.KioskResult
import com.MegaStream.app.kiosk.ManagedKioskController
import dagger.hilt.android.scopes.ActivityScoped
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Safe until an authenticated policy integration replaces the Hilt binding. */
class DefaultKioskPolicySource @Inject constructor() : KioskPolicySource {
    override val policy: StateFlow<KioskPolicy?> = MutableStateFlow<KioskPolicy?>(null).asStateFlow()
}

class ManagedKioskEngine(private val controller: ManagedKioskController) : KioskEngine {
    override fun update(policy: KioskPolicy, appForeground: Boolean, playbackActive: Boolean): KioskResult =
        controller.update(policy, appForeground, playbackActive)

    override fun requestManualExit(policy: KioskPolicy): KioskResult = controller.requestManualExit(policy)
}

/** Activity-owned facade. Call actions and bind from the UI thread (normally lifecycleScope). */
@ActivityScoped
class KioskController @Inject constructor(
    engine: KioskEngine,
    private val source: KioskPolicySource,
) {
    private val session = KioskSessionCoordinator(engine, source)
    private var policyCollection: Job? = null
    val status: StateFlow<KioskStatus> = session.status
    val uiState: StateFlow<KioskUiState> = session.uiState

    /** One collector per activity; caller scope cancellation releases the subscription. */
    fun bind(scope: CoroutineScope): Job {
        policyCollection?.takeIf { it.isActive }?.let { return it }
        return scope.launch {
            source.policy.collect { session.refreshPolicy() }
        }.also { policyCollection = it }
    }

    fun start() = session.start()
    fun resume() = session.resume()
    fun pause() = session.pause()
    fun stop() = session.stop()
    fun setPlayerPresent(present: Boolean) = session.setPlayerPresent(present)
    fun setPlaybackActive(active: Boolean) = session.setPlaybackActive(active)
    fun beginManualExit() = session.beginManualExit()
    fun cancelManualExit() = session.cancelManualExit()
    fun confirmManualExit() = session.confirmManualExit()
}
