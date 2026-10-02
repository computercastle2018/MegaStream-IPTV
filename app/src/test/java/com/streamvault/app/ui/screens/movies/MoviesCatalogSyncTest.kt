package com.MegaStream.app.ui.screens.movies

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

class MoviesCatalogSyncTest {
    @Test fun manualSyncForcesOnlyMovieIndexAfterMovieCategoriesComplete() = runBlocking<Unit> {
        val sync = mock<SyncManager>()
        whenever(sync.retrySection(7L, SyncRepairSection.MOVIES)).thenReturn(Result.success(Unit))
        whenever(sync.processQueuedXtreamIndexJobs(7L, ContentType.MOVIE, force = true)).thenReturn(Result.success(Unit))
        assertTrue(syncMoviesCatalog(sync, 7L) is Result.Success)
        inOrder(sync).apply {
            verify(sync).retrySection(7L, SyncRepairSection.MOVIES)
            verify(sync).processQueuedXtreamIndexJobs(7L, ContentType.MOVIE, force = true)
        }
        verifyNoMoreInteractions(sync)
    }

    @Test fun categoryFailureDoesNotFetchIndexOrReportSuccess() = runBlocking<Unit> {
        val sync = mock<SyncManager>()
        val failure = Result.error("Category fetch failed")
        whenever(sync.retrySection(7L, SyncRepairSection.MOVIES)).thenReturn(failure)
        assertSame(failure, syncMoviesCatalog(sync, 7L))
        inOrder(sync).verify(sync).retrySection(7L, SyncRepairSection.MOVIES)
        verifyNoMoreInteractions(sync)
    }

    @Test fun indexFailureIsReturnedWithoutFullProviderSync() = runBlocking<Unit> {
        val sync = mock<SyncManager>()
        val failure = Result.error("Movie fetch failed")
        whenever(sync.retrySection(7L, SyncRepairSection.MOVIES)).thenReturn(Result.success(Unit))
        whenever(sync.processQueuedXtreamIndexJobs(7L, ContentType.MOVIE, force = true)).thenReturn(failure)
        assertSame(failure, syncMoviesCatalog(sync, 7L))
        inOrder(sync).apply {
            verify(sync).retrySection(7L, SyncRepairSection.MOVIES)
            verify(sync).processQueuedXtreamIndexJobs(7L, ContentType.MOVIE, force = true)
        }
        verifyNoMoreInteractions(sync)
    }
}
