package com.MegaStream.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.MegaStream.data.local.entity.CategoryEntity
import com.MegaStream.data.local.entity.MovieEntity
import com.MegaStream.data.local.entity.ProviderEntity
import com.MegaStream.data.local.entity.SeriesEntity
import com.MegaStream.domain.model.ContentType
import com.MegaStream.domain.model.ProviderType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VodProviderOrderingTest {
    private lateinit var db: MegaStreamDatabase

    @Before
    fun createDatabase() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), MegaStreamDatabase::class.java
        ).build()
        for (providerId in listOf(1L, 2L)) {
            db.providerDao().insert(ProviderEntity(
                id = providerId,
                name = "Provider $providerId",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "https://provider-$providerId.example.com",
                isActive = providerId == 1L
            ))
            db.categoryDao().insertAll(listOf(ContentType.MOVIE, ContentType.SERIES).map { type ->
                CategoryEntity(providerId = providerId, categoryId = 10L, name = "VOD $type", type = type)
            })
        }
    }

    @After
    fun closeDatabase() = db.close()

    @Test
    fun moviePreviewSortsBeforeLimitAndNeverUsesLocalInsertOrderOrReleaseDate() = runTest {
        val dao = db.movieDao()
        dao.insertAll(listOf(
            MovieEntity(id = 1, streamId = 101, providerId = 1, categoryId = 10, name = "A old", addedAt = 100),
            MovieEntity(id = 2, streamId = 102, providerId = 1, categoryId = 10, name = "Z newest", addedAt = 300),
            MovieEntity(id = 3, streamId = 103, providerId = 1, categoryId = 10, name = "B middle", addedAt = 200),
            MovieEntity(id = 4, streamId = 104, providerId = 1, categoryId = 10, name = "Undated", releaseDate = "2099-01-01"),
            MovieEntity(id = 5, streamId = 105, providerId = 2, categoryId = 10, name = "Other provider", addedAt = 999)
        ))
        assertThat(dao.getFreshByCategoryPreview(1, 10, 2).first().map { it.id }).containsExactly(2L, 3L).inOrder()
        assertThat(dao.getFreshPreview(1, 10).first().map { it.id }).containsExactly(2L, 3L, 1L).inOrder()
        assertThat(dao.getById(4)?.addedAt).isEqualTo(0L)
    }

    @Test
    fun seriesPreviewUsesProviderModifiedTimestampWithUndatedRowsLast() = runTest {
        val dao = db.seriesDao()
        dao.insertAll(listOf(
            SeriesEntity(id = 1, seriesId = 101, providerId = 1, categoryId = 10, name = "A old", lastModified = 100),
            SeriesEntity(id = 2, seriesId = 102, providerId = 1, categoryId = 10, name = "Z updated", lastModified = 300),
            SeriesEntity(id = 3, seriesId = 103, providerId = 1, categoryId = 10, name = "Undated", releaseDate = "2099-01-01"),
            SeriesEntity(id = 4, seriesId = 104, providerId = 2, categoryId = 10, name = "Other provider", lastModified = 999)
        ))
        assertThat(dao.getFreshByCategoryPreview(1, 10, 2).first().map { it.id }).containsExactly(2L, 1L).inOrder()
        assertThat(dao.getFreshPreview(1, 10).first().map { it.id }).containsExactly(2L, 1L, 3L).inOrder()
        assertThat(dao.getById(3)?.lastModified).isEqualTo(0L)
    }
}
