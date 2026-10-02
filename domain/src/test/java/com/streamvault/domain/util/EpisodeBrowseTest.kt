package com.MegaStream.domain.util

import com.MegaStream.domain.model.Episode
import com.MegaStream.domain.model.Season
import com.MegaStream.domain.model.Series
import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeBrowseTest {
    private val episodes = listOf(
        Episode(id = 11, title = "البداية", seasonNumber = 1, episodeNumber = 1, releaseDate = "2030-01-01"),
        Episode(id = 12, title = "Second", seasonNumber = 1, episodeNumber = 2, plot = "العودة", durationSeconds = 100, watchProgress = 6000),
        Episode(id = 21, title = "New season", seasonNumber = 2, episodeNumber = 1, durationSeconds = 100, watchProgress = 95000),
        Episode(id = 22, title = "Latest", seasonNumber = 2, episodeNumber = 2, releaseDate = "2000-01-01")
    )
    private val series = Series(id = 1, name = "Series", seasons = listOf(
        Season(seasonNumber = 1, episodes = episodes.take(2)),
        Season(seasonNumber = 2, episodes = episodes.drop(2))
    ))

    @Test
    fun newestFirst_usesSeasonThenEpisodeNotReleaseDateAndDoesNotMutatePlaybackOrder() {
        assertEquals(listOf(22L, 21L, 12L, 11L), browseSeriesEpisodes(series, EpisodeBrowseQuery()).map { it.id })
        assertEquals(listOf(11L, 12L, 21L, 22L), series.seasons.flatMap { it.episodes }.map { it.id })
        assertEquals(listOf(11L, 12L, 21L, 22L),
            browseSeriesEpisodes(series, EpisodeBrowseQuery(order = EpisodeOrder.OLDEST)).map { it.id })
    }

    @Test
    fun seasonAndSearchFilters_intersectAndSupportArabicPlotAndTrimmedTitles() {
        val cases = listOf(
            EpisodeBrowseQuery(seasonNumber = 1) to listOf(12L, 11L),
            EpisodeBrowseQuery(search = " LATEST ") to listOf(22L),
            EpisodeBrowseQuery(search = "العودة") to listOf(12L),
            EpisodeBrowseQuery(search = "البداية") to listOf(11L),
            EpisodeBrowseQuery(seasonNumber = 2, search = "2") to listOf(22L),
            EpisodeBrowseQuery(seasonNumber = 1, search = "Latest") to emptyList()
        )
        for ((query, expected) in cases) assertEquals(query.toString(), expected, browseSeriesEpisodes(series, query).map { it.id })
    }

    @Test
    fun watchFilters_useExistingCompletionThresholdAndNotFinishedIncludesInProgress() {
        val cases = mapOf(
            EpisodeWatchFilter.ALL to listOf(22L, 21L, 12L, 11L),
            EpisodeWatchFilter.UNWATCHED to listOf(22L, 12L, 11L),
            EpisodeWatchFilter.IN_PROGRESS to listOf(12L),
            EpisodeWatchFilter.COMPLETED to listOf(21L)
        )
        for ((filter, expected) in cases) assertEquals(filter.name, expected,
            browseSeriesEpisodes(series, EpisodeBrowseQuery(watchFilter = filter)).map { it.id })
    }

    @Test
    fun unknownDuration_isNotAssumedCompleteAndInProgressRequiresMoreThanFiveSeconds() {
        val boundary = episodes.first().copy(watchProgress = 5000, durationSeconds = 100)
        val unknownDuration = episodes.last().copy(watchProgress = 95000, durationSeconds = 0)
        val input = series.copy(seasons = listOf(Season(seasonNumber = 1, episodes = listOf(boundary, unknownDuration))))
        assertEquals(listOf(22L), browseSeriesEpisodes(input,
            EpisodeBrowseQuery(watchFilter = EpisodeWatchFilter.IN_PROGRESS)).map { it.id })
        assertEquals(emptyList<Long>(), browseSeriesEpisodes(input,
            EpisodeBrowseQuery(watchFilter = EpisodeWatchFilter.COMPLETED)).map { it.id })
    }
}
