package com.MegaStream.domain.util

import com.MegaStream.domain.model.Episode
import com.MegaStream.domain.model.Series

enum class EpisodeWatchFilter { ALL, UNWATCHED, IN_PROGRESS, COMPLETED }
enum class EpisodeOrder { NEWEST, OLDEST }

data class EpisodeBrowseQuery(
    val seasonNumber: Int? = null,
    val search: String = "",
    val watchFilter: EpisodeWatchFilter = EpisodeWatchFilter.ALL,
    val order: EpisodeOrder = EpisodeOrder.NEWEST
)

fun browseSeriesEpisodes(series: Series, query: EpisodeBrowseQuery): List<Episode> {
    val search = query.search.trim()
    val episodes = series.seasons.asSequence()
        .filter { query.seasonNumber == null || it.seasonNumber == query.seasonNumber }
        .flatMap { it.episodes.asSequence() }
        .filter { matchesEpisodeWatchFilter(it, query.watchFilter) }
        .filter { search.isEmpty() || it.title.contains(search, ignoreCase = true) ||
            it.plot.orEmpty().contains(search, ignoreCase = true) || it.episodeNumber.toString() == search }
        .toList()
    val newest = compareByDescending<Episode> { it.seasonNumber }
        .thenByDescending { it.episodeNumber }
    return episodes.sortedWith(if (query.order == EpisodeOrder.NEWEST) newest else newest.reversed())
}

private fun matchesEpisodeWatchFilter(episode: Episode, filter: EpisodeWatchFilter): Boolean {
    val complete = isPlaybackComplete(episode.watchProgress, episode.durationSeconds.toLong() * 1000L)
    return when (filter) {
        EpisodeWatchFilter.ALL -> true
        EpisodeWatchFilter.UNWATCHED -> !complete
        EpisodeWatchFilter.IN_PROGRESS -> episode.watchProgress > 5000L && !complete
        EpisodeWatchFilter.COMPLETED -> complete
    }
}
