package com.MegaStream.app.ui.screens.series

import com.MegaStream.data.sync.SyncManager
import com.MegaStream.data.sync.SyncRepairSection
import com.MegaStream.domain.model.ContentType
import com.MegaStream.domain.model.Result
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever

class SeriesCatalogSyncTest {
    @Test fun manualSyncForcesOnlySeriesIndexAfterSeriesCategoriesComplete() = runBlocking<Unit> {
        val sync = mock<SyncManager>()
        whenever(sync.retrySection(7L, SyncRepairSection.SERIES)).thenReturn(Result.success(Unit))
        whenever(sync.processQueuedXtreamIndexJobs(7L, ContentType.SERIES, force = true)).thenReturn(Result.success(Unit))
        assertTrue(syncSeriesCatalog(sync, 7L) is Result.Success)
        inOrder(sync).apply {
            verify(sync).retrySection(7L, SyncRepairSection.SERIES)
            verify(sync).processQueuedXtreamIndexJobs(7L, ContentType.SERIES, force = true)
        }
        verifyNoMoreInteractions(sync)
    }

    @Test fun categoryFailureDoesNotFetchIndexOrReportSuccess() = runBlocking<Unit> {
        val sync = mock<SyncManager>()
        val failure = Result.error("Category fetch failed")
        whenever(sync.retrySection(7L, SyncRepairSection.SERIES)).thenReturn(failure)
        assertSame(failure, syncSeriesCatalog(sync, 7L))
        inOrder(sync).verify(sync).retrySection(7L, SyncRepairSection.SERIES)
        verifyNoMoreInteractions(sync)
    }

    @Test fun indexFailureIsReturnedWithoutFullProviderSync() = runBlocking<Unit> {
        val sync = mock<SyncManager>()
        val failure = Result.error("Series fetch failed")
        whenever(sync.retrySection(7L, SyncRepairSection.SERIES)).thenReturn(Result.success(Unit))
        whenever(sync.processQueuedXtreamIndexJobs(7L, ContentType.SERIES, force = true)).thenReturn(failure)
        assertSame(failure, syncSeriesCatalog(sync, 7L))
        inOrder(sync).apply {
            verify(sync).retrySection(7L, SyncRepairSection.SERIES)
            verify(sync).processQueuedXtreamIndexJobs(7L, ContentType.SERIES, force = true)
        }
        verifyNoMoreInteractions(sync)
    }
}
