package com.MegaStream.domain.repository

import com.MegaStream.domain.model.ExternalRatings
import com.MegaStream.domain.model.ExternalRatingsLookup
import com.MegaStream.domain.model.Result

interface ExternalRatingsRepository {
    suspend fun getRatings(lookup: ExternalRatingsLookup): Result<ExternalRatings>
}