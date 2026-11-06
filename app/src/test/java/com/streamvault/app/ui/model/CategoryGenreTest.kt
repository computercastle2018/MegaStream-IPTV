package com.MegaStream.app.ui.model

import com.MegaStream.domain.model.Category
import com.MegaStream.domain.repository.ChannelRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryGenreTest {
    @Test
    fun mixedLanguageProviderGroupsUseTheSameCategories() {
        listOf(
            "NEWS - الأخبار" to CategoryGenre.NEWS,
            "أخْبَار عربية" to CategoryGenre.NEWS,
            "BEIN SPORTS HD" to CategoryGenre.SPORTS,
            "رِيَاضَة عربية" to CategoryGenre.SPORTS,
            "مسلسلات" to CategoryGenre.SERIES,
            "UK Movies" to CategoryGenre.MOVIES,
            "أطفال" to CategoryGenre.KIDS,
            "وثائقيات" to CategoryGenre.DOCUMENTARY,
            "Quran" to CategoryGenre.RELIGION,
            "Music" to CategoryGenre.MUSIC,
            "KSA - السعودية" to CategoryGenre.OTHER,
            "BEIN ENTERTAINMENT" to CategoryGenre.OTHER
        ).forEach { (name, genre) -> assertEquals(name, genre, CategoryGenre.fromName(name)) }
    }

    @Test
    fun genreFilteringNeverIncludesAllChannelsOrVirtualGroups() {
        val news = Category(1, name = "News")
        assertTrue(news.matchesGenre(CategoryGenre.NEWS))
        assertFalse(news.matchesGenre(CategoryGenre.SPORTS))
        assertFalse(news.copy(isVirtual = true).matchesGenre(CategoryGenre.NEWS))
        assertFalse(news.copy(id = ChannelRepository.ALL_CHANNELS_ID).matchesGenre(CategoryGenre.NEWS))
        assertTrue(news.copy(isVirtual = true).matchesGenre(null))
    }
}
