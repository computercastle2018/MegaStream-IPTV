package com.MegaStream.app.ui.screens.settings

import android.app.Application
import com.MegaStream.app.update.AppUpdateInstaller
import com.MegaStream.data.preferences.PreferencesRepository
import com.MegaStream.domain.manager.RecordingManager
import com.MegaStream.domain.model.RecordingItem
import com.MegaStream.domain.repository.CategoryRepository
import com.MegaStream.domain.repository.ChannelRepository
import com.MegaStream.domain.repository.CombinedM3uRepository
import com.MegaStream.domain.repository.EpgSourceRepository
import com.MegaStream.domain.repository.MovieRepository
import com.MegaStream.domain.repository.SeriesRepository
import com.MegaStream.domain.repository.SyncMetadataRepository
import com.MegaStream.domain.repository.ProviderRepository
import com.MegaStream.domain.usecase.GetCustomCategories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun registerSettingsAppUpdateObservers(
    scope: CoroutineScope,
    preferencesRepository: PreferencesRepository,
    appUpdateActions: SettingsAppUpdateActions,
    appUpdateInstaller: AppUpdateInstaller,
    uiState: MutableStateFlow<SettingsUiState>
) {
    scope.launch {
        combine(
            preferencesRepository.autoCheckAppUpdates,
            preferencesRepository.lastAppUpdateCheckTimestamp,
            preferencesRepository.autoDownloadAppUpdates
        ) { autoCheckEnabled, lastCheckedAt, autoDownload ->
            Triple(autoCheckEnabled, lastCheckedAt, autoDownload)
        }.distinctUntilChanged().collect { (autoCheckEnabled, lastCheckedAt, autoDownload) ->
            if (autoCheckEnabled && appUpdateActions.shouldAutoCheckForUpdates(lastCheckedAt)) {
                appUpdateActions.checkForAppUpdates(
                    scope = scope,
                    manual = false,
                    isRemoteVersionNewer = ::isRemoteVersionNewer,
                    autoDownload = autoDownload
                )
            }
        }
    }

    scope.launch {
        var lastStatus: com.MegaStream.app.update.AppUpdateDownloadStatus? = null
        appUpdateInstaller.downloadState.collect { downloadState ->
            // Surface the saved file path once, right after a download finishes,
            // so the user can locate (or manually install) the APK.
            val justCompleted = lastStatus != com.MegaStream.app.update.AppUpdateDownloadStatus.Downloaded &&
                downloadState.status == com.MegaStream.app.update.AppUpdateDownloadStatus.Downloaded
            lastStatus = downloadState.status
            val pathMessage = if (justCompleted) appUpdateActions.downloadedPathMessage() else null
            uiState.update {
                it.copy(
                    appUpdate = it.appUpdate.withDownloadState(downloadState),
                    userMessage = pathMessage ?: it.userMessage
                )
            }
        }
    }

    scope.launch {
        appUpdateInstaller.refreshState()
    }
}

internal fun registerCombinedProfileObservers(
    scope: CoroutineScope,
    combinedM3uRepository: CombinedM3uRepository,
    uiState: MutableStateFlow<SettingsUiState>
) {
    scope.launch {
        combinedM3uRepository.getProfiles().collect { profiles ->
            uiState.update { it.copy(combinedProfiles = profiles) }
        }
    }

    scope.launch {
        combinedM3uRepository.getAvailableM3uProviders().collect { providers ->
            uiState.update { it.copy(availableM3uProviders = providers) }
        }
    }

    scope.launch {
        combinedM3uRepository.getActiveLiveSource().collect { activeSource ->
            uiState.update { it.copy(activeLiveSource = activeSource) }
        }
    }
}

internal fun registerRecordingObservers(
    scope: CoroutineScope,
    recordingManager: RecordingManager,
    preferencesRepository: PreferencesRepository,
    uiState: MutableStateFlow<SettingsUiState>
) {
    scope.launch {
        recordingManager.observeRecordingItems().collect { items ->
            uiState.update { it.copy(recordingItems = items.sortedByDescending(RecordingItem::scheduledStartMs)) }
        }
    }

    scope.launch {
        recordingManager.observeStorageState().collect { storage ->
            uiState.update { it.copy(recordingStorageState = storage) }
        }
    }

    scope.launch {
        preferencesRepository.recordingWifiOnly.collect { wifiOnly ->
            uiState.update { it.copy(wifiOnlyRecording = wifiOnly) }
        }
    }

    scope.launch {
        preferencesRepository.recordingPaddingBeforeMinutes.collect { minutes ->
            uiState.update { it.copy(recordingPaddingBeforeMinutes = minutes) }
        }
    }

    scope.launch {
        preferencesRepository.recordingPaddingAfterMinutes.collect { minutes ->
            uiState.update { it.copy(recordingPaddingAfterMinutes = minutes) }
        }
    }
}

internal fun registerEpgObservers(
    scope: CoroutineScope,
    epgSourceRepository: EpgSourceRepository,
    uiState: MutableStateFlow<SettingsUiState>
) {
    scope.launch {
        epgSourceRepository.getAllSources().collect { sources ->
            uiState.update { it.copy(epgSources = sources) }
        }
    }
}

internal fun registerDerivedStateObservers(
    scope: CoroutineScope,
    providerRepository: ProviderRepository,
    syncMetadataRepository: SyncMetadataRepository,
    movieRepository: MovieRepository,
    seriesRepository: SeriesRepository,
    application: Application,
    preferencesRepository: PreferencesRepository,
    activeProviderIdFlow: Flow<Long?>,
    categoryRepository: CategoryRepository,
    combinedM3uRepository: CombinedM3uRepository,
    channelRepository: ChannelRepository,
    getCustomCategories: GetCustomCategories,
    uiState: MutableStateFlow<SettingsUiState>
) {
    scope.launch {
        observeProviderDiagnostics(
            providerRepository = providerRepository,
            syncMetadataRepository = syncMetadataRepository,
            movieRepository = movieRepository,
            seriesRepository = seriesRepository,
            application = application
        ).collect { diagnosticsByProvider ->
            uiState.update { it.copy(diagnosticsByProvider = diagnosticsByProvider) }
        }
    }

    scope.launch {
        preferencesRepository.lastMaintenanceSnapshot.collect { snapshot ->
            uiState.update { it.copy(databaseMaintenance = snapshot?.toUiModel()) }
        }
    }

    scope.launch {
        observeCategoryManagement(
            activeProviderIdFlow = activeProviderIdFlow,
            preferencesRepository = preferencesRepository,
            categoryRepository = categoryRepository
        ).collect { snapshot ->
            uiState.update {
                it.copy(
                    categorySortModes = snapshot.categorySortModes,
                    hiddenCategories = snapshot.hiddenCategories
                )
            }
        }
    }

    scope.launch {
        observeGuideDefaultCategoryOptions(
            combinedM3uRepository = combinedM3uRepository,
            channelRepository = channelRepository,
            preferencesRepository = preferencesRepository,
            getCustomCategories = getCustomCategories
        ).collect { categories ->
            uiState.update { it.copy(guideDefaultCategoryOptions = categories) }
        }
    }
}