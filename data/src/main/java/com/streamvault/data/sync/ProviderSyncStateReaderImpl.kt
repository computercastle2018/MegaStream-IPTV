package com.MegaStream.data.sync

import com.MegaStream.data.local.dao.XtreamIndexJobDao
import com.MegaStream.domain.manager.ProviderSyncStateReader
import com.MegaStream.domain.model.SyncState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class ProviderSyncStateReaderImpl @Inject constructor(
    private val syncManager: SyncManager,
    private val xtreamIndexJobDao: XtreamIndexJobDao
) : ProviderSyncStateReader {
    override fun currentSyncState(providerId: Long): SyncState = syncManager.currentSyncState(providerId)

    override fun observeBackgroundIndexingActive(providerId: Long): Flow<Boolean> =
        xtreamIndexJobDao.observeForProvider(providerId).map { jobs ->
            jobs.any { job ->
                job.section in setOf("MOVIE", "SERIES") &&
                    job.state in setOf("QUEUED", "RUNNING", "PARTIAL", "STALE", "FAILED_RETRYABLE")
            }
        }
}
