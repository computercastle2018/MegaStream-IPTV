package com.MegaStream.app.controlplane.integration

import com.MegaStream.app.controlplane.*
import com.MegaStream.domain.model.Provider
import com.MegaStream.domain.model.ProviderType
import com.MegaStream.domain.repository.ProviderRepository
import com.MegaStream.domain.model.Result
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class AndroidProviderAssignmentSinkTest {
    private val repository: ProviderRepository = mock()
    private val stored = mutableMapOf<String, Long>()
    private val sink = AndroidProviderAssignmentSink(repository, { stored[it] ?: 0 }, { key, value ->
        if (value == null) stored.remove(key) else stored[key] = value
    })
    private val installation = "6e244c20-c9ef-422d-a1da-2d84570bc949"
    private val assignment = "c09be6e4-d57a-4831-971a-afcf666d3caf"
    private val profile = "ad880939-ef12-4aba-8687-98f4354ccad8"
    private val key = "$installation/$assignment"
    private fun batch(revision: Long = 1) = ProviderAssignmentsResponse(revision, listOf(
        ProviderAssignment(assignment, profile, revision, revision, ProviderAssignmentPolicy.AUTO_ENABLED,
            "My subscription", XtreamProviderConfiguration("https://provider.example", "user", "secret",
                ProviderEpgSyncMode.BACKGROUND, true, ProviderLiveSyncMode.AUTO)),
    ), emptyList())

    @Test fun savesThroughSecureRepositoryRefreshesAndDoesNotDuplicateOnNextHeartbeat() = runTest {
        whenever(repository.addProvider(any())).thenReturn(Result.success(7L))
        whenever(repository.setActiveProvider(7)).thenReturn(Result.success(Unit))
        whenever(repository.refreshProviderData(eq(7), eq(true), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(Unit))
        sink.apply(installation, batch())
        val saved = argumentCaptor<Provider>()
        verify(repository).addProvider(saved.capture())
        assertEquals("secret", saved.firstValue.password)
        assertEquals(ProviderType.XTREAM_CODES, saved.firstValue.type)
        inOrder(repository) {
            verify(repository).refreshProviderData(eq(7), eq(true), anyOrNull(), anyOrNull(), anyOrNull())
            verify(repository).setActiveProvider(7)
        }
        assertEquals(7L, stored[key])
        assertEquals(1L, stored["$key/revision"])
        whenever(repository.getProvider(7)).thenReturn(saved.firstValue.copy(id = 7))
        sink.apply(installation, batch())
        verify(repository, times(1)).addProvider(any())
        verify(repository, times(1)).refreshProviderData(eq(7), eq(true), anyOrNull(), anyOrNull(), anyOrNull())
        assertFalse(stored.toString().contains("secret"))
    }

    @Test fun failedCatalogSyncRetainsRowMappingForRetryAndTombstoneDeletesOnlyMappedRow() = runTest {
        whenever(repository.addProvider(any())).thenReturn(Result.success(7L))
        whenever(repository.setActiveProvider(7)).thenReturn(Result.success(Unit))
        whenever(repository.refreshProviderData(eq(7), eq(true), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.error("sensitive upstream failure"))
        try { sink.apply(installation, batch()); fail("Sync must fail") }
        catch (failure: IllegalStateException) { assertFalse(failure.message.orEmpty().contains("sensitive")) }
        assertEquals(7L, stored[key])
        assertNull(stored["$key/revision"])
        whenever(repository.getProvider(7)).thenReturn(Provider(id = 7, name = "Saved", type = ProviderType.XTREAM_CODES,
            serverUrl = "https://provider.example", isActive = false))
        whenever(repository.updateProvider(any())).thenReturn(Result.success(Unit))
        whenever(repository.refreshProviderData(eq(7), eq(true), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(Unit))
        sink.apply(installation, batch())
        verify(repository, times(1)).addProvider(any())
        verify(repository).setActiveProvider(7)
        assertEquals(1L, stored["$key/revision"])
        whenever(repository.deleteProvider(7)).thenReturn(Result.success(Unit))
        sink.apply(installation, ProviderAssignmentsResponse(2, emptyList(), listOf(
            ProviderAssignmentTombstone(assignment, 2, "2026-10-30T00:00:00Z"),
        )))
        verify(repository).deleteProvider(7)
        assertTrue(stored.isEmpty())
    }
}
