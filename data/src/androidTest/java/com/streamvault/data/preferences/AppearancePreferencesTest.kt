package com.MegaStream.data.preferences

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.MegaStream.data.local.MegaStreamDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppearancePreferencesTest {
    @Test
    fun tvProPersistsAndExistingDesignCanBeRestored() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MegaStreamDatabase::class.java).build()
        val repository = PreferencesRepository(context, db.channelPreferenceDao(), db.searchHistoryDao())
        val original = repository.appUiStyle.first()
        try {
            repository.setAppUiStyle("tvpro")
            val reopened = PreferencesRepository(context, db.channelPreferenceDao(), db.searchHistoryDao())
            assertThat(reopened.appUiStyle.first()).isEqualTo("tvpro")
            for (style in listOf("classic", "modern", "studio")) {
                reopened.setAppUiStyle(style)
                assertThat(repository.appUiStyle.first()).isEqualTo(style)
            }
            repository.setAppUiStyle("unknown")
            assertThat(repository.appUiStyle.first()).isEqualTo("classic")
        } finally {
            repository.setAppUiStyle(original)
            db.close()
        }
    }
}
