package com.MegaStream.app.ui.screens.settings

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.MegaStream.app.R
import com.MegaStream.domain.model.Provider
import com.MegaStream.domain.model.ProviderType
import com.MegaStream.domain.model.SyncMetadata
import com.MegaStream.domain.repository.MovieRepository
import com.MegaStream.domain.repository.ProviderRepository
import com.MegaStream.domain.repository.SeriesRepository
import com.MegaStream.domain.repository.SyncMetadataRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SettingsDerivedStateObserversTest {
    private val providerRepository: ProviderRepository = mock()
    private val syncMetadataRepository: SyncMetadataRepository = mock()
    private val movieRepository: MovieRepository = mock()
    private val seriesRepository: SeriesRepository = mock()
    private val application: Application = mock()

    @Test
    fun `observeProviderDiagnostics uses live movie and series library counts over metadata counts`() = runTest {
        val provider = Provider(
            id = 7L,
            name = "Premium",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.com"
        )
        whenever(providerRepository.getProviders()).thenReturn(flowOf(listOf(provider)))
        whenever(syncMetadataRepository.observeMetadata(7L)).thenReturn(
            flowOf(
                SyncMetadata(
                    providerId = 7L,
                    movieCount = 109_899,
                    seriesCount = 26_147,
                    movieCatalogStale = true
                )
            )
        )
        whenever(movieRepository.getLibraryCount(7L)).thenReturn(flowOf(140_484))
        whenever(seriesRepository.getLibraryCount(7L)).thenReturn(flowOf(32_037))
        whenever(application.getString(R.string.settings_capability_xtream_without_epg))
            .thenReturn("Xtream without EPG")

        val diagnostics = observeProviderDiagnostics(
            providerRepository = providerRepository,
            syncMetadataRepository = syncMetadataRepository,
            movieRepository = movieRepository,
            seriesRepository = seriesRepository,
            application = application
        ).first()

        assertThat(diagnostics.getValue(7L).movieCount).isEqualTo(140_484)
        assertThat(diagnostics.getValue(7L).seriesCount).isEqualTo(32_037)
        assertThat(diagnostics.getValue(7L).movieCatalogStale).isTrue()
    }
}