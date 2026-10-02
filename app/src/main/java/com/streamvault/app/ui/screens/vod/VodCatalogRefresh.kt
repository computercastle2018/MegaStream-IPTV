package com.MegaStream.app.ui.screens.vod

import com.MegaStream.domain.model.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal data class VodCatalogRefreshState(
    val isRefreshing: Boolean = false,
    val revision: Long = 0,
    val error: String? = null
)

internal class VodCatalogRefresh(
    private val scope: CoroutineScope,
    private val needsRefresh: suspend (Long) -> Boolean,
    private val refresh: suspend (Long) -> Result<Unit>
) {
    private val mutableState = MutableStateFlow(VodCatalogRefreshState())
    val state = mutableState.asStateFlow()
    private var providerId: Long? = null
    private var enabled = false
    private var refreshJob: Job? = null

    fun enter(providerId: Long?, enabled: Boolean) {
        if (this.providerId == providerId && this.enabled == enabled) {
            refreshIfNeeded(force = false)
            return
        }
        refreshJob?.cancel()
        refreshJob = null
        this.providerId = providerId
        this.enabled = enabled
        mutableState.update { it.copy(isRefreshing = false, error = null) }
        refreshIfNeeded(force = false)
    }

    fun refreshIfNeeded(force: Boolean = true) {
        val id = providerId ?: return
        if (!enabled || refreshJob?.isActive == true) return
        refreshJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                withTimeout(120_000L) {
                    if (!force && !needsRefresh(id)) return@withTimeout
                    mutableState.update { it.copy(isRefreshing = true, error = null) }
                    val outcome = refresh(id)
                    currentCoroutineContext().ensureActive()
                    when (outcome) {
                        is Result.Success -> mutableState.update { it.copy(revision = it.revision + 1) }
                        is Result.Error -> mutableState.update { it.copy(error = outcome.message) }
                        Result.Loading -> mutableState.update { it.copy(error = "Catalog refresh did not complete") }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                mutableState.update { it.copy(error = "Catalog refresh timed out") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(error = "Catalog refresh failed") }
            } finally {
                // A cancelled provider's completion must not clear its replacement's spinner.
                if (providerId == id && enabled && refreshJob === coroutineContext[Job]) {
                    mutableState.update { it.copy(isRefreshing = false) }
                }
            }
        }
        refreshJob?.start()
    }
}
