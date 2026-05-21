package com.MegaStream.data.repository

import com.MegaStream.data.local.dao.SyncMetadataDao
import com.MegaStream.data.mapper.toDomain
import com.MegaStream.data.mapper.toEntity
import com.MegaStream.domain.model.SyncMetadata
import com.MegaStream.domain.repository.SyncMetadataRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncMetadataRepositoryImpl @Inject constructor(
    private val dao: SyncMetadataDao
) : SyncMetadataRepository {

    override fun observeMetadata(providerId: Long): Flow<SyncMetadata?> {
        return dao.get(providerId).map { it?.toDomain() }
    }

    override suspend fun getMetadata(providerId: Long): SyncMetadata? {
        return dao.getSync(providerId)?.toDomain()
    }

    override suspend fun updateMetadata(metadata: SyncMetadata) {
        dao.insertOrUpdate(metadata.toEntity())
    }

    override suspend fun clearMetadata(providerId: Long) {
        dao.delete(providerId)
    }
}
