package com.MegaStream.app.ui.screens.vod

import org.junit.Assert.assertEquals
import org.junit.Test
import com.MegaStream.domain.model.Category
import com.MegaStream.domain.model.Movie
import com.MegaStream.domain.model.Series

class StudioContentAccessTest {
    @Test
    fun adultAndUserProtectedItems_matchExistingCatalogLevelRules() {
        val expected = listOf(StudioContentAccess.VISIBLE, StudioContentAccess.LOCKED,
            StudioContentAccess.LOCKED, StudioContentAccess.HIDDEN)
        for (item in listOf(movie.copy(isAdult = true), movie.copy(isUserProtected = true))) {
            expected.forEachIndexed { level, access ->
                assertAccess(access, item, category, level)
            }
        }
    }

    @Test
    fun protectedCategory_locksUnmarkedItemsAndOnlyItsOwnUnlockPermitsEntry() {
        for (protectedCategory in listOf(category.copy(isAdult = true), category.copy(isUserProtected = true))) {
            assertAccess(StudioContentAccess.VISIBLE, movie, protectedCategory, 0)
            for (level in 1..3) {
                val expected = if (level == 3) StudioContentAccess.HIDDEN else StudioContentAccess.LOCKED
                assertAccess(expected, movie, protectedCategory, level, setOf(99L))
                assertAccess(StudioContentAccess.VISIBLE, movie, protectedCategory, level, setOf(12L))
                assertAccess(StudioContentAccess.VISIBLE, movie.copy(categoryId = -12L),
                    protectedCategory.copy(id = -12L), level, setOf(12L))
            }
        }
    }

    @Test
    fun missingCategory_cannotExposeArtworkWhileParentalControlsAreEnabled() {
        assertAccess(StudioContentAccess.VISIBLE, movie, null, 0)
        assertAccess(StudioContentAccess.LOCKED, movie, null, 1)
        assertAccess(StudioContentAccess.HIDDEN, movie, null, 3)
        assertAccess(StudioContentAccess.VISIBLE, movie, null, 3, setOf(12L))
    }

    @Test
    fun categorylessProtectedItems_cannotBorrowAnotherCategoryUnlock() {
        val item = movie.copy(categoryId = null, isAdult = true)
        assertAccess(StudioContentAccess.LOCKED, item, null, 1, setOf(12L))
        assertAccess(StudioContentAccess.HIDDEN, item, null, 3, setOf(12L))
    }

    @Test
    fun ordinaryContent_remainsVisibleAtEveryCatalogParentalLevel() {
        for (level in 0..3) {
            assertAccess(StudioContentAccess.VISIBLE, movie, category, level)
            assertAccess(StudioContentAccess.VISIBLE, movie.copy(categoryId = null), null, level)
        }
    }

    private val movie = Movie(id = 1L, name = "Film", categoryId = 12L)
    private val category = Category(id = 12L, name = "Category")

    private fun assertAccess(expected: StudioContentAccess, item: Movie, category: Category?, level: Int,
        unlocked: Set<Long> = emptySet()) {
        assertEquals("Movie, level $level", expected, studioContentAccess(item, category, level, unlocked))
        val series = Series(id = item.id, name = item.name, categoryId = item.categoryId,
            isAdult = item.isAdult, isUserProtected = item.isUserProtected)
        assertEquals("Series, level $level", expected, studioContentAccess(series, category, level, unlocked))
    }
}
