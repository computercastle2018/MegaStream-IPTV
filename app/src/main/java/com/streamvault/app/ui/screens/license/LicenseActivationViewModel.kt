package com.MegaStream.app.ui.screens.license

import androidx.lifecycle.ViewModel
import com.MegaStream.app.controlplane.ActivationStatusResponse
import com.MegaStream.app.controlplane.ControlPlaneError
import com.MegaStream.app.controlplane.ControlPlaneResult
import com.MegaStream.app.controlplane.EntitlementState
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Standalone activation coordinator. Transport decisions are hints, never verified entitlement.
 * All state transitions and operation admission are serialized by a private monitor; callers
 * need not be main-thread confined. The injected scope is caller-owned; only this instance's
 * child jobs are cancelled by dispose.
 */
class LicenseActivationViewModel(
    private val port: LicenseActivationPort,
    scope: CoroutineScope,
    initialEntitlement: LicenseEntitlementSnapshot = LicenseEntitlementSnapshot(),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val maxPollAttempts: Int = 60,
    private val callTimeoutMillis: Long = 15_000,
) : ViewModel() {
    init {
        require(maxPollAttempts > 0)
        require(callTimeoutMillis > 0)
    }

    private val lock = Any()
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val workScope = CoroutineScope(scope.coroutineContext + owner)
    private val mutableState = MutableStateFlow(LicenseActivationState(entitlement = initialEntitlement))
    val state: StateFlow<LicenseActivationState> = mutableState.asStateFlow()
    private var generation = 0L
    private var operation: Job? = null
    private var pollToken: String? = null
    private var nextPollAt = Long.MIN_VALUE
    private var expiryWatcher: Job? = null
    private var codeGeneration = 0L

    init {
        // Also clear an idle retryable session when the caller cancels its scope.
        workScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                synchronized(lock) {
                    generation++
                    clearSecret()
                    operation = null
                    if (!state.value.disposed) {
                        mutableState.value = state.value.copy(busy = false, pollStatus = LicenseActivationPollStatus.STOPPED)
                    }
                }
            }
        }
    }

    fun selectMode(mode: LicenseActivationMode) = synchronized(lock) {
        if (state.value.disposed) return@synchronized
        cancelOperation()
        mutableState.value = LicenseActivationState(entitlement = state.value.entitlement, mode = mode)
    }

    fun submitKey(rawKey: CharArray) {
        val copy = try { rawKey.copyOf() } finally { rawKey.fill('\u0000') }
        synchronized(lock) {
            if (!canStart() || state.value.mode != LicenseActivationMode.DIRECT_KEY) {
                copy.fill('\u0000')
                return
            }
            if (copy.isEmpty() || copy.size > 512 || copy.all { it.isWhitespace() } || copy.any { it.isISOControl() }) {
                copy.fill('\u0000')
                mutableState.value = state.value.copy(error = LicenseActivationError.INVALID_KEY)
                return
            }
            clearSecret()
            mutableState.value = state.value.copy(busy = true, error = null, transportState = null, pollStatus = LicenseActivationPollStatus.REQUESTING)
            startOperation(onComplete = { synchronized(lock) { copy.fill('\u0000') } }) { id ->
                val reply = call(id, {
                    // Copy conversion and wiping share a lock. The resulting transport String is
                    // unavoidable and cannot be erased; it is never stored in this model or state.
                    val context = currentCoroutineContext()
                    val key = synchronized(lock) {
                        context.ensureActive()
                        if (!isCurrent(id) || !state.value.busy) throw CancellationException()
                        String(copy).also { copy.fill('\u0000') }
                    }
                    port.activateDirect(key)
                }) { Reply.Decision(it.decision.state) }
                synchronized(lock) {
                    if (isCurrent(id)) applyDecisionOrFailure(reply, polling = false)
                }
            }
        }
    }

    fun requestCode() = synchronized(lock) {
        if (!canStart() || state.value.mode != LicenseActivationMode.ACTIVATION_CODE) return@synchronized
        clearSecret()
        mutableState.value = state.value.copy(
            busy = true, error = null, transportState = null,
            activationCode = null, codeExpiresAtEpochMillis = null, pollAttempts = 0,
            pollStatus = LicenseActivationPollStatus.REQUESTING,
        )
        startOperation { id ->
            val reply = call(id, { port.requestCode() }) {
                Reply.Code(it.code, it.pollToken, Instant.parse(it.expiresAt).toEpochMilli())
            }
            val accepted = synchronized(lock) {
                if (!isCurrent(id)) false
                else if (reply is Reply.Code) {
                    mutableState.value = state.value.copy(activationCode = reply.code, codeExpiresAtEpochMillis = reply.expiresAt)
                    if (nowEpochMillis() >= reply.expiresAt) {
                        finish(LicenseActivationPollStatus.EXPIRED)
                        false
                    } else {
                        pollToken = reply.token
                        watchExpiry(reply.expiresAt)
                        true
                    }
                } else {
                    applyDecisionOrFailure(reply, polling = false)
                    false
                }
            }
            if (accepted) poll(id)
        }
    }

    fun retryPolling() = synchronized(lock) {
        if (!canStart() || !state.value.canRetryPolling || pollToken == null) return@synchronized
        if (expireOrExhaust()) return@synchronized
        mutableState.value = state.value.copy(busy = true, error = null)
        startOperation { poll(it) }
    }

    fun stopPolling() = synchronized(lock) {
        if (state.value.disposed) return@synchronized
        cancelOperation()
        mutableState.value = state.value.copy(busy = false, error = null, pollStatus = LicenseActivationPollStatus.STOPPED)
    }

    fun updateEntitlement(entitlement: LicenseEntitlementSnapshot) = synchronized(lock) {
        if (!state.value.disposed) mutableState.value = state.value.copy(entitlement = entitlement)
    }

    fun clearError() = synchronized(lock) {
        if (!state.value.disposed) mutableState.value = state.value.copy(error = null)
    }

    fun dispose() = synchronized(lock) {
        if (state.value.disposed) return@synchronized
        cancelOperation()
        mutableState.value = state.value.copy(disposed = true, busy = false, error = null, pollStatus = LicenseActivationPollStatus.STOPPED)
        owner.cancel()
    }

    override fun onCleared() {
        dispose()
    }

    private fun canStart() = owner.isActive && !state.value.disposed && !state.value.busy
    private fun isCurrent(id: Long) = owner.isActive && !state.value.disposed && generation == id

    /** Called under lock, including lazy start to close duplicate-operation races. */
    private fun startOperation(onComplete: () -> Unit = {}, block: suspend (Long) -> Unit) {
        val id = ++generation
        val job = workScope.launch(start = CoroutineStart.LAZY) {
            try {
                block(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(lock) {
                    if (isCurrent(id)) finish(LicenseActivationPollStatus.STOPPED, LicenseActivationError.UNKNOWN)
                }
            }
        }
        operation = job
        job.invokeOnCompletion {
            onComplete()
            synchronized(lock) {
                if (generation == id) {
                    operation = null
                    if (state.value.busy) {
                        clearSecret()
                        mutableState.value = state.value.copy(busy = false, pollStatus = LicenseActivationPollStatus.STOPPED)
                    }
                }
            }
        }
        job.start()
    }

    private suspend fun poll(id: Long) {
        while (true) {
            val wait = synchronized(lock) {
                if (!isCurrent(id) || expireOrExhaust()) return
                val now = nowEpochMillis()
                val untilPoll = remaining(nextPollAt, now)
                if (untilPoll > 0) {
                    mutableState.value = state.value.copy(pollStatus = LicenseActivationPollStatus.WAITING)
                    minOf(untilPoll, remaining(state.value.codeExpiresAtEpochMillis!!, now))
                } else 0L
            }
            if (wait > 0) {
                delay(wait)
                continue
            }
            val credentials = synchronized(lock) {
                if (!isCurrent(id) || expireOrExhaust()) return
                val code = state.value.activationCode ?: return
                val token = pollToken ?: return
                mutableState.value = state.value.copy(pollStatus = LicenseActivationPollStatus.POLLING, pollAttempts = state.value.pollAttempts + 1)
                PollCredentials(code, token)
            }
            val reply = call(id, { port.pollCode(credentials.code, credentials.token) }) {
                when (it) {
                    is ActivationStatusResponse.Activated -> Reply.Decision(it.response.decision.state)
                    is ActivationStatusResponse.Pending -> Reply.Pending(it.response.retryAfterSeconds)
                }
            }
            synchronized(lock) {
                if (!isCurrent(id)) return
                if (isExpired()) {
                    finish(LicenseActivationPollStatus.EXPIRED)
                    return
                }
                when (reply) {
                    is Reply.Pending -> {
                        nextPollAt = saturatingAdd(nowEpochMillis(), maxOf(1_000L, secondsToMillis(reply.retrySeconds)))
                        if (expireOrExhaust()) return
                        mutableState.value = state.value.copy(pollStatus = LicenseActivationPollStatus.WAITING)
                    }
                    else -> {
                        applyDecisionOrFailure(reply, polling = true)
                        return
                    }
                }
            }
        }
    }

    /**
     * The transport task is a sibling, not a child awaited by the operation: a noncooperative
     * transport cannot prevent the UI timeout. Its eventual result is dropped after cancellation.
     * Coroutine cancellation cannot forcibly terminate an arbitrary blocking transport implementation.
     * Unknown port failures deliberately become a closed UNKNOWN error, never a Throwable-bearing
     * UI result. Cancellation still propagates; no exception messages or response bodies escape.
     */
    private suspend fun <T> call(id: Long, request: suspend () -> ControlPlaneResult<T>, sanitize: (T) -> Reply): Reply {
        val completion = CompletableDeferred<Reply>()
        val deadline = saturatingAdd(nowEpochMillis(), callTimeoutMillis)
        val transport = workScope.launch {
            try {
                currentCoroutineContext().ensureActive()
                val reply = when (val result = request()) {
                    is ControlPlaneResult.Success -> sanitize(result.value)
                    is ControlPlaneResult.Failure -> Reply.Failed(mapError(result.error))
                }
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    if (isCurrent(id)) {
                        completion.complete(if (nowEpochMillis() >= deadline) Reply.Failed(LicenseActivationError.TIMEOUT) else reply)
                    }
                }
            } catch (cancelled: CancellationException) {
                completion.cancel(cancelled)
                throw cancelled
            } catch (_: SocketTimeoutException) {
                completion.complete(Reply.Failed(LicenseActivationError.TIMEOUT))
            } catch (_: IOException) {
                completion.complete(Reply.Failed(LicenseActivationError.NETWORK))
            } catch (_: ArithmeticException) {
                completion.complete(Reply.Failed(LicenseActivationError.INVALID_RESPONSE))
            } catch (_: IllegalArgumentException) {
                completion.complete(Reply.Failed(LicenseActivationError.INVALID_RESPONSE))
            } catch (_: Exception) {
                completion.complete(Reply.Failed(LicenseActivationError.UNKNOWN))
            }
        }
        return try {
            val timeout = synchronized(lock) {
                state.value.codeExpiresAtEpochMillis?.takeIf { pollToken != null }
                    ?.let { minOf(callTimeoutMillis, remaining(it, nowEpochMillis())) }
                    ?: callTimeoutMillis
            }
            val reply = withTimeout(timeout) { completion.await() }
            if (nowEpochMillis() >= deadline) Reply.Failed(LicenseActivationError.TIMEOUT) else reply
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            Reply.Failed(LicenseActivationError.TIMEOUT)
        } finally {
            completion.cancel()
            transport.cancel()
        }
    }

    private fun applyDecisionOrFailure(reply: Reply, polling: Boolean) {
        when (reply) {
            is Reply.Decision -> {
                mutableState.value = state.value.copy(transportState = reply.state)
                finish(
                    if (reply.state == EntitlementState.ALLOWED) LicenseActivationPollStatus.ACTIVATED else LicenseActivationPollStatus.STOPPED,
                    if (reply.state == EntitlementState.ALLOWED) null else LicenseActivationError.REJECTED,
                )
            }
            is Reply.Failed -> {
                if (polling && state.value.pollAttempts >= maxPollAttempts) {
                    finish(LicenseActivationPollStatus.EXHAUSTED, reply.error)
                } else if (polling && reply.error in RETRYABLE_ERRORS) {
                    mutableState.value = state.value.copy(busy = false, error = reply.error, pollStatus = LicenseActivationPollStatus.RETRYABLE_FAILURE)
                } else finish(LicenseActivationPollStatus.STOPPED, reply.error)
            }
            else -> finish(LicenseActivationPollStatus.STOPPED, LicenseActivationError.INVALID_RESPONSE)
        }
    }

    private fun isExpired() = state.value.codeExpiresAtEpochMillis?.let { nowEpochMillis() >= it } == true

    private fun expireOrExhaust(): Boolean = when {
        isExpired() -> { finish(LicenseActivationPollStatus.EXPIRED); true }
        state.value.pollAttempts >= maxPollAttempts -> { finish(LicenseActivationPollStatus.EXHAUSTED); true }
        else -> false
    }

    private fun finish(status: LicenseActivationPollStatus, error: LicenseActivationError? = null) {
        clearSecret()
        mutableState.value = state.value.copy(busy = false, pollStatus = status, error = error)
    }

    private fun watchExpiry(expiresAt: Long) {
        val ticket = codeGeneration
        val watcher = workScope.launch(start = CoroutineStart.LAZY) {
            while (true) {
                val wait = synchronized(lock) {
                    if (ticket != codeGeneration || !owner.isActive || pollToken == null) return@launch
                    remaining(expiresAt, nowEpochMillis())
                }
                if (wait > 0) {
                    delay(wait)
                } else {
                    synchronized(lock) {
                        if (ticket == codeGeneration && pollToken != null) {
                            cancelOperation()
                            finish(LicenseActivationPollStatus.EXPIRED)
                        }
                    }
                    return@launch
                }
            }
        }
        expiryWatcher = watcher
        watcher.start()
    }

    private fun clearSecret() {
        pollToken = null
        nextPollAt = Long.MIN_VALUE
        codeGeneration++
        expiryWatcher?.cancel()
        expiryWatcher = null
    }

    private fun cancelOperation() {
        generation++
        clearSecret()
        operation?.cancel()
        operation = null
    }

    private fun mapError(error: ControlPlaneError): LicenseActivationError = when {
        error.status == 429 || error.code == "rate_limited" -> LicenseActivationError.RATE_LIMITED
        error.code == "network_error" || error.status?.let { it >= 500 } == true -> LicenseActivationError.NETWORK
        error.code in setOf("invalid_response", "unsafe_response", "payload_too_large") -> LicenseActivationError.INVALID_RESPONSE
        error.status?.let { it in 400..499 } == true || error.code == "invalid_request" -> LicenseActivationError.REJECTED
        else -> LicenseActivationError.UNKNOWN
    }

    private class PollCredentials(val code: String, val token: String) {
        override fun toString() = "PollCredentials([REDACTED])"
    }

    // Only this sanitized vocabulary can cross a suspension boundary back to the state machine.
    private sealed interface Reply {
        class Code(val code: String, val token: String, val expiresAt: Long) : Reply {
            override fun toString() = "ActivationCode([REDACTED])"
        }
        class Decision(val state: EntitlementState) : Reply
        class Pending(val retrySeconds: Long) : Reply
        class Failed(val error: LicenseActivationError) : Reply
    }

    private companion object {
        val RETRYABLE_ERRORS = setOf(LicenseActivationError.NETWORK, LicenseActivationError.TIMEOUT, LicenseActivationError.RATE_LIMITED)
        fun secondsToMillis(seconds: Long): Long = if (seconds > Long.MAX_VALUE / 1_000) Long.MAX_VALUE else seconds * 1_000
        fun saturatingAdd(value: Long, positive: Long): Long = if (value > Long.MAX_VALUE - positive) Long.MAX_VALUE else value + positive
        fun remaining(deadline: Long, now: Long): Long = when {
            deadline <= now -> 0
            now < 0 && deadline > Long.MAX_VALUE + now -> Long.MAX_VALUE
            else -> deadline - now
        }
    }
}
