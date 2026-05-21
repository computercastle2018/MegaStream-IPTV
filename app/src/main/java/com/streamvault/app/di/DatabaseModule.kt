package com.MegaStream.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.MegaStream.app.BuildConfig
import com.MegaStream.data.local.MegaStreamDatabase
import com.MegaStream.data.local.dao.*
import com.MegaStream.data.local.dao.ChannelPreferenceDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    private const val DEBUG_SLOW_QUERY_THRESHOLD_MS = 100L

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MegaStreamDatabase =
        Room.databaseBuilder(
            context,
            MegaStreamDatabase::class.java,
            "MegaStream.db"
        )
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .openHelperFactory(
                if (BuildConfig.DEBUG) {
                    SlowQueryLoggingOpenHelperFactory(
                        delegate = FrameworkSQLiteOpenHelperFactory(),
                        slowQueryThresholdMs = DEBUG_SLOW_QUERY_THRESHOLD_MS
                    )
                } else {
                    FrameworkSQLiteOpenHelperFactory()
                }
            )
            .addMigrations(
                MegaStreamDatabase.MIGRATION_1_2,
                MegaStreamDatabase.MIGRATION_2_3,
                MegaStreamDatabase.MIGRATION_3_4,
                MegaStreamDatabase.MIGRATION_4_5,
                MegaStreamDatabase.MIGRATION_5_6,
                MegaStreamDatabase.MIGRATION_6_7,
                MegaStreamDatabase.MIGRATION_7_8,
                MegaStreamDatabase.MIGRATION_8_9,
                MegaStreamDatabase.MIGRATION_9_10,
                MegaStreamDatabase.MIGRATION_10_11,
                MegaStreamDatabase.MIGRATION_11_12,
                MegaStreamDatabase.MIGRATION_12_13,
                MegaStreamDatabase.MIGRATION_13_14,
                MegaStreamDatabase.MIGRATION_14_15,
                MegaStreamDatabase.MIGRATION_15_16,
                MegaStreamDatabase.MIGRATION_16_17,
                MegaStreamDatabase.MIGRATION_17_18,
                MegaStreamDatabase.MIGRATION_18_19,
                MegaStreamDatabase.MIGRATION_19_20,
                MegaStreamDatabase.MIGRATION_20_21,
                MegaStreamDatabase.MIGRATION_21_22,
                MegaStreamDatabase.MIGRATION_22_23,
                MegaStreamDatabase.MIGRATION_23_24,
                MegaStreamDatabase.MIGRATION_24_25,
                MegaStreamDatabase.MIGRATION_25_26,
                MegaStreamDatabase.MIGRATION_26_27,
                MegaStreamDatabase.MIGRATION_27_28,
                MegaStreamDatabase.MIGRATION_28_29,
                MegaStreamDatabase.MIGRATION_29_30,
                MegaStreamDatabase.MIGRATION_30_31,
                MegaStreamDatabase.MIGRATION_31_32,
                MegaStreamDatabase.MIGRATION_32_33,
                MegaStreamDatabase.MIGRATION_33_34,
                MegaStreamDatabase.MIGRATION_34_35,
                MegaStreamDatabase.MIGRATION_35_36,
                MegaStreamDatabase.MIGRATION_36_37,
                MegaStreamDatabase.MIGRATION_37_38,
                MegaStreamDatabase.MIGRATION_38_39,
                MegaStreamDatabase.MIGRATION_39_40,
                MegaStreamDatabase.MIGRATION_40_41,
                MegaStreamDatabase.MIGRATION_41_42,
                MegaStreamDatabase.MIGRATION_42_43,
                MegaStreamDatabase.MIGRATION_43_44,
                MegaStreamDatabase.MIGRATION_44_45,
                MegaStreamDatabase.MIGRATION_45_46,
                MegaStreamDatabase.MIGRATION_46_47,
                MegaStreamDatabase.MIGRATION_47_48,
                MegaStreamDatabase.MIGRATION_48_49,
                MegaStreamDatabase.MIGRATION_49_50,
                MegaStreamDatabase.MIGRATION_50_51,
                MegaStreamDatabase.MIGRATION_51_52
            )
            // NOTE: fallbackToDestructiveMigration() intentionally removed.
            // All future schema changes MUST add a corresponding Migration in MegaStreamDatabase.
            .build()

    @Provides fun provideProviderDao(db: MegaStreamDatabase): ProviderDao = db.providerDao()
    @Provides fun provideChannelDao(db: MegaStreamDatabase): ChannelDao = db.channelDao()
    @Provides fun provideChannelPreferenceDao(db: MegaStreamDatabase): ChannelPreferenceDao = db.channelPreferenceDao()
    @Provides fun provideMovieDao(db: MegaStreamDatabase): MovieDao = db.movieDao()
    @Provides fun provideSeriesDao(db: MegaStreamDatabase): SeriesDao = db.seriesDao()
    @Provides fun provideEpisodeDao(db: MegaStreamDatabase): EpisodeDao = db.episodeDao()
    @Provides fun provideCategoryDao(db: MegaStreamDatabase): CategoryDao = db.categoryDao()
    @Provides fun provideCatalogSyncDao(db: MegaStreamDatabase): CatalogSyncDao = db.catalogSyncDao()
    @Provides fun provideProgramDao(db: MegaStreamDatabase): ProgramDao = db.programDao()
    @Provides fun provideFavoriteDao(db: MegaStreamDatabase): FavoriteDao = db.favoriteDao()
    @Provides fun provideVirtualGroupDao(db: MegaStreamDatabase): VirtualGroupDao = db.virtualGroupDao()
    @Provides fun providePlaybackHistoryDao(db: MegaStreamDatabase): PlaybackHistoryDao = db.playbackHistoryDao()
    @Provides fun provideTmdbIdentityDao(db: MegaStreamDatabase): TmdbIdentityDao = db.tmdbIdentityDao()
    @Provides fun provideSearchHistoryDao(db: MegaStreamDatabase): SearchHistoryDao = db.searchHistoryDao()
    @Provides fun provideSearchDao(db: MegaStreamDatabase): SearchDao = db.searchDao()
    @Provides fun provideSyncMetadataDao(db: MegaStreamDatabase): SyncMetadataDao = db.syncMetadataDao()
    @Provides fun provideMovieCategoryHydrationDao(db: MegaStreamDatabase): MovieCategoryHydrationDao = db.movieCategoryHydrationDao()
    @Provides fun provideSeriesCategoryHydrationDao(db: MegaStreamDatabase): SeriesCategoryHydrationDao = db.seriesCategoryHydrationDao()
    @Provides fun provideEpgSourceDao(db: MegaStreamDatabase): EpgSourceDao = db.epgSourceDao()
    @Provides fun provideProviderEpgSourceDao(db: MegaStreamDatabase): ProviderEpgSourceDao = db.providerEpgSourceDao()
    @Provides fun provideEpgChannelDao(db: MegaStreamDatabase): EpgChannelDao = db.epgChannelDao()
    @Provides fun provideEpgProgrammeDao(db: MegaStreamDatabase): EpgProgrammeDao = db.epgProgrammeDao()
    @Provides fun provideChannelEpgMappingDao(db: MegaStreamDatabase): ChannelEpgMappingDao = db.channelEpgMappingDao()
    @Provides fun provideCombinedM3uProfileDao(db: MegaStreamDatabase): CombinedM3uProfileDao = db.combinedM3uProfileDao()
    @Provides fun provideCombinedM3uProfileMemberDao(db: MegaStreamDatabase): CombinedM3uProfileMemberDao = db.combinedM3uProfileMemberDao()
    @Provides fun provideRecordingScheduleDao(db: MegaStreamDatabase): RecordingScheduleDao = db.recordingScheduleDao()
    @Provides fun provideRecordingRunDao(db: MegaStreamDatabase): RecordingRunDao = db.recordingRunDao()
    @Provides fun provideProgramReminderDao(db: MegaStreamDatabase): ProgramReminderDao = db.programReminderDao()
    @Provides fun provideRecordingStorageDao(db: MegaStreamDatabase): RecordingStorageDao = db.recordingStorageDao()
    @Provides fun providePlaybackCompatibilityDao(db: MegaStreamDatabase): PlaybackCompatibilityDao = db.playbackCompatibilityDao()
    @Provides fun provideXtreamContentIndexDao(db: MegaStreamDatabase): XtreamContentIndexDao = db.xtreamContentIndexDao()
    @Provides fun provideXtreamIndexJobDao(db: MegaStreamDatabase): XtreamIndexJobDao = db.xtreamIndexJobDao()
    @Provides fun provideXtreamLiveOnboardingDao(db: MegaStreamDatabase): XtreamLiveOnboardingDao = db.xtreamLiveOnboardingDao()
}
