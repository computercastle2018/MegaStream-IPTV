package com.MegaStream.data.repository

import com.MegaStream.domain.model.ExternalRatings
import com.MegaStream.domain.model.ExternalRatingsLookup
import com.MegaStream.domain.model.Result
import com.MegaStream.domain.repository.ExternalRatingsRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExternalRatingsRepositoryImpl @Inject constructor() : ExternalRatingsRepository {

    override suspend fun getRatings(lookup: ExternalRatingsLookup): Result<ExternalRatings> {
        return Result.success(ExternalRatings.unavailable())
    }
}