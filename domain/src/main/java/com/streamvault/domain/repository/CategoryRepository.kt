package com.MegaStream.domain.repository

import com.MegaStream.domain.model.Category
import com.MegaStream.domain.model.ContentType
import com.MegaStream.domain.model.Result
import kotlinx.coroutines.flow.Flow

interface CategoryRepository {
    fun getCategories(providerId: Long): Flow<List<Category>>
    suspend fun setCategoryProtection(
        providerId: Long,
        categoryId: Long,
        type: ContentType,
        isProtected: Boolean
    ): Result<Unit>
}
