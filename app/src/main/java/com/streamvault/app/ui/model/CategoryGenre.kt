package com.MegaStream.app.ui.model

import com.MegaStream.app.R
import com.MegaStream.domain.model.Category
import com.MegaStream.domain.repository.ChannelRepository
import java.text.Normalizer
import java.util.Locale

enum class CategoryGenre(val labelResId: Int, private val pattern: Regex) {
    NEWS(R.string.category_genre_news, Regex("\\bnews\\b|اخبار|اخباري")),
    SPORTS(R.string.category_genre_sports, Regex("\\bsports?\\b|\\bppv\\b|\\bnba\\b|\\bcricket\\b|thamanyah|رياض|كره|ثمانيه")),
    SERIES(R.string.nav_series, Regex("\\bseries\\b|مسلسل")),
    MOVIES(R.string.nav_movies, Regex("\\bmovies?\\b|\\bcinema\\b|\\bfilms?\\b|افلام")),
    KIDS(R.string.category_genre_kids, Regex("\\bkids?\\b|\\bchildren\\b|\\bcartoons?\\b|اطفال|كرتون")),
    DOCUMENTARY(R.string.category_genre_documentary, Regex("\\bdocumentar\\w*\\b|\\bnature\\b|وثايق|طبيعه")),
    RELIGION(R.string.category_genre_religion, Regex("\\bislamic\\b|\\breligio\\w*\\b|\\bquran\\b|اسلام|دين|قران")),
    MUSIC(R.string.category_genre_music, Regex("\\bmusic\\b|موسيق|اغاني")),
    OTHER(R.string.category_genre_other, Regex(""));

    companion object {
        fun fromName(name: String): CategoryGenre {
            val normalized = Normalizer.normalize(name, Normalizer.Form.NFKD)
                .replace(Regex("[\\p{M}ـ]"), "")
                .replace(Regex("[آأإٱ]"), "ا").replace('ى', 'ي').replace('ة', 'ه')
                .replace('ئ', 'ي').replace('ؤ', 'و').replace("ء", "")
                .lowercase(Locale.ROOT)
            return entries.first { it.pattern.containsMatchIn(normalized) }
        }
    }
}

fun Category.matchesGenre(genre: CategoryGenre?): Boolean = genre == null ||
    (!isVirtual && id != ChannelRepository.ALL_CHANNELS_ID && CategoryGenre.fromName(name) == genre)
