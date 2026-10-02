package com.MegaStream.app.ui.screens.vod

import com.MegaStream.domain.model.Result
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VodCatalogRefreshTest {
    @Test
    fun immediateCompletionClearsRefreshingState() {
        val catalog = VodCatalogRefresh(CoroutineScope(Dispatchers.Unconfined), { true }) {
            Result.success(Unit)
        }
        catalog.enter(1L, true)
        assertEquals(1L, catalog.state.value.revision)
        assertFalse(catalog.state.value.isRefreshing)
    }

    @Test
    fun freshEntrySkipsFetchButManualRefreshAndStaleReentryFetch() = runTest {
        var stale = false
        var requests = 0
        val catalog = VodCatalogRefresh(this, { stale }) {
            requests++
            stale = false
            Result.success(Unit)
        }
        catalog.enter(1L, false)
        catalog.refreshIfNeeded()
        advanceUntilIdle()
        assertEquals(0, requests)
        catalog.enter(1L, true)
        advanceUntilIdle()
        assertEquals(0, requests)
        catalog.refreshIfNeeded()
        advanceUntilIdle()
        assertEquals(1, requests)
        stale = true
        catalog.enter(1L, true)
        advanceUntilIdle()
        assertEquals(2, requests)
        assertEquals(2L, catalog.state.value.revision)
        assertFalse(catalog.state.value.isRefreshing)
    }

    @Test
    fun tapsCoalesceAndOldProviderCompletionCannotPublish() = runTest {
        val oldResponse = CompletableDeferred<Unit>()
        val newResponse = CompletableDeferred<Unit>()
        val requestedProviders = mutableListOf<Long>()
        val catalog = VodCatalogRefresh(this, { true }) { provider ->
            requestedProviders += provider
            if (provider == 1L) withContext(NonCancellable) { oldResponse.await() }
            else newResponse.await()
            Result.success(Unit)
        }
        catalog.enter(1L, true)
        runCurrent()
        repeat(5) { catalog.refreshIfNeeded() }
        assertEquals(listOf(1L), requestedProviders)
        catalog.enter(2L, true)
        runCurrent()
        oldResponse.complete(Unit)
        runCurrent()
        assertTrue(catalog.state.value.isRefreshing)
        assertEquals(0L, catalog.state.value.revision)
        newResponse.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), requestedProviders)
        assertEquals(1L, catalog.state.value.revision)
        assertFalse(catalog.state.value.isRefreshing)
    }

    @Test
    fun leavingStudioCancelsAndDoesNotPublishOldError() = runTest {
        val response = CompletableDeferred<Unit>()
        val catalog = VodCatalogRefresh(this, { true }) {
            withContext(NonCancellable) { response.await() }
            Result.error("Old provider error")
        }
        catalog.enter(1L, true)
        runCurrent()
        catalog.enter(1L, false)
        response.complete(Unit)
        advanceUntilIdle()
        assertFalse(catalog.state.value.isRefreshing)
        assertEquals(null, catalog.state.value.error)
        assertEquals(0L, catalog.state.value.revision)
    }

    @Test
    fun failureTimeoutAndRetryNeverExposeUnexpectedException() = runTest {
        var hang = false
        val catalog = VodCatalogRefresh(this, { true }) {
            if (hang) CompletableDeferred<Unit>().await()
            throw IllegalStateException("https://provider.invalid/?password=secret")
        }
        catalog.enter(1L, true)
        advanceUntilIdle()
        assertEquals("Catalog refresh failed", catalog.state.value.error)
        assertFalse(catalog.state.value.isRefreshing)
        hang = true
        catalog.refreshIfNeeded()
        advanceUntilIdle()
        assertEquals("Catalog refresh timed out", catalog.state.value.error)
        assertFalse(catalog.state.value.isRefreshing)
    }
}
