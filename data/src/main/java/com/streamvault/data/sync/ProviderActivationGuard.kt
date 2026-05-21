package com.MegaStream.data.sync

import com.MegaStream.data.local.dao.ChannelDao
import com.MegaStream.domain.repository.SyncMetadataRepository
import com.MegaStream.domain.model.ProviderType
import kotlinx.coroutines.flow.first

internal suspend fun hasUsableLiveCatalogForActivation(
    providerId: Long,
    providerType: ProviderType,
    channelDao: ChannelDao,
    syncMetadataRepository: SyncMetadataRepository
): Boolean {
    if (providerType != ProviderType.XTREAM_CODES) {
        return true
    }

    if (channelDao.getCount(providerId).first() > 0) {
        return true
    }

    val metadata = syncMetadataRepository.getMetadata(providerId)
    return (metadata?.movieCount ?: 0) > 0 || (metadata?.seriesCount ?: 0) > 0
}