package com.MegaStream.app.ui.screens.vod

import com.MegaStream.domain.model.Category
import com.MegaStream.domain.model.Movie
import com.MegaStream.domain.model.Series

internal enum class StudioContentAccess { VISIBLE, LOCKED, HIDDEN }

internal fun studioContentAccess(
    movie: Movie,
    category: Category?,
    parentalLevel: Int,
    unlockedCategoryIds: Set<Long>
): StudioContentAccess = resolveStudioContentAccess(
    movie.isAdult || movie.isUserProtected || categoryRequiresProtection(movie.categoryId, category),
    movie.categoryId, parentalLevel, unlockedCategoryIds
)

internal fun studioContentAccess(
    series: Series,
    category: Category?,
    parentalLevel: Int,
    unlockedCategoryIds: Set<Long>
): StudioContentAccess = resolveStudioContentAccess(
    series.isAdult || series.isUserProtected || categoryRequiresProtection(series.categoryId, category),
    series.categoryId, parentalLevel, unlockedCategoryIds
)

private fun categoryRequiresProtection(categoryId: Long?, category: Category?): Boolean =
    category?.isAdult == true || category?.isUserProtected == true || (categoryId != null && category == null)

private fun resolveStudioContentAccess(
    isProtected: Boolean,
    categoryId: Long?,
    parentalLevel: Int,
    unlockedCategoryIds: Set<Long>
): StudioContentAccess {
    if (!isProtected || parentalLevel == 0 ||
        (categoryId != null && kotlin.math.abs(categoryId) in unlockedCategoryIds)) {
        return StudioContentAccess.VISIBLE
    }
    return if (parentalLevel >= 3) StudioContentAccess.HIDDEN else StudioContentAccess.LOCKED
}
