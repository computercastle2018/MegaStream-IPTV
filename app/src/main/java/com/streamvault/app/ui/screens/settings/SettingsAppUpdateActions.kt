package com.MegaStream.app.ui.screens.settings

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.MegaStream.app.R
import com.MegaStream.app.update.AppUpdateDownloadStatus
import com.MegaStream.app.update.AppUpdateInstaller
import com.MegaStream.app.update.GitHubReleaseChecker
import com.MegaStream.app.update.UpdateMetadataStore
import com.MegaStream.data.preferences.PreferencesRepository
import com.MegaStream.domain.model.Result
import java.io.IOException

internal class SettingsAppUpdateActions(
    private val appContext: Application,
    private val preferencesRepository: PreferencesRepository,
    private val gitHubReleaseChecker: GitHubReleaseChecker,
    private val appUpdateInstaller: AppUpdateInstaller,
    private val uiState: MutableStateFlow<SettingsUiState>
) {
    private val updateMetadataStore = UpdateMetadataStore(appContext)
    private var updateCheckInFlight = false

    fun shouldAutoCheckForUpdates(lastCheckedAt: Long?): Boolean {
        val now = System.currentTimeMillis()
        val checkIntervalMs = 24L * 60L * 60L * 1000L
        return lastCheckedAt == null || now - lastCheckedAt >= checkIntervalMs
    }

    fun checkForAppUpdates(
        scope: CoroutineScope,
        manual: Boolean,
        isRemoteVersionNewer: (Int?, String) -> Boolean,
        autoDownload: Boolean = false
    ) {
        if (updateCheckInFlight) return
        updateCheckInFlight = true
        scope.launch {
            try {
                val checkedAt = System.currentTimeMillis()
                uiState.update {
                    it.copy(
                        isCheckingForUpdates = true,
                        appUpdate = it.appUpdate.copy(errorMessage = null)
                    )
                }
                preferencesRepository.setLastAppUpdateCheckTimestamp(checkedAt)
                when (val result = gitHubReleaseChecker.fetchLatestRelease()) {
                    is Result.Error -> {
                        uiState.update {
                            it.copy(
                                isCheckingForUpdates = false,
                                userMessage = if (manual) result.message else it.userMessage,
                                appUpdate = it.appUpdate.copy(
                                    lastCheckedAt = checkedAt,
                                    errorMessage = result.message
                                )
                            )
                        }
                    }
                    is Result.Success -> {
                        val release = result.data
                        if (release == null) {
                            preferencesRepository.setCachedAppUpdateRelease(
                                versionName = null, versionCode = null, releaseUrl = null,
                                downloadUrl = null, releaseNotes = "", publishedAt = null
                            )
                            uiState.update {
                                it.copy(
                                    userMessage = if (manual) appContext.getString(R.string.settings_update_current_message) else it.userMessage,
                                    appUpdate = AppUpdateUiModel(lastCheckedAt = checkedAt)
                                        .withDownloadState(it.appUpdate.toDownloadState())
                                )
                            }
                            return@launch
                        }
                        val metadataFailure = try {
                            withContext(Dispatchers.IO) { updateMetadataStore.save(release) }
                            null
                        } catch (failure: IOException) {
                            failure
                        } catch (failure: IllegalArgumentException) {
                            failure
                        }
                        if (metadataFailure != null) {
                            val message = metadataFailure.message
                                ?: "Unable to save update verification metadata."
                            uiState.update {
                                it.copy(
                                    userMessage = message,
                                    appUpdate = it.appUpdate.copy(
                                        lastCheckedAt = checkedAt,
                                        errorMessage = message
                                    )
                                )
                            }
                            return@launch
                        }
                        val updateAvailable = isRemoteVersionNewer(
                            release.versionCode,
                            release.versionName
                        )
                        if (updateAvailable) {
                            preferencesRepository.setCachedAppUpdateRelease(
                                versionName = release.versionName,
                                versionCode = release.versionCode,
                                releaseUrl = release.releaseUrl,
                                downloadUrl = release.downloadUrl,
                                releaseNotes = release.releaseNotesForCache(),
                                publishedAt = release.publishedAt
                            )
                        } else {
                            preferencesRepository.setCachedAppUpdateRelease(
                                versionName = null,
                                versionCode = null,
                                releaseUrl = null,
                                downloadUrl = null,
                                releaseNotes = "",
                                publishedAt = null
                            )
                        }
                        uiState.update {
                            it.copy(
                                isCheckingForUpdates = false,
                                userMessage = if (manual) {
                                    if (updateAvailable) {
                                        appContext.getString(R.string.settings_update_available_message, release.versionName)
                                    } else {
                                        appContext.getString(R.string.settings_update_current_message)
                                    }
                                } else {
                                    it.userMessage
                                },
                                appUpdate = AppUpdateUiModel(
                                    isUpdateAvailable = updateAvailable,
                                    lastCheckedAt = checkedAt,
                                    errorMessage = null
                                ).withReleaseInfo(release)
                                    .withDownloadState(it.appUpdate.toDownloadState())
                            )
                        }
                        appUpdateInstaller.refreshState()
                        if (autoDownload && updateAvailable) {
                            val currentDownloadStatus = appUpdateInstaller.downloadState.value.status
                            if (currentDownloadStatus != AppUpdateDownloadStatus.Downloading &&
                                currentDownloadStatus != AppUpdateDownloadStatus.Downloaded
                            ) {
                                downloadLatestUpdate(scope)
                            }
                        }
                    }
                    Result.Loading -> {
                        uiState.update { it.copy(isCheckingForUpdates = false) }
                    }
                }
            } finally {
                updateCheckInFlight = false
                uiState.update { it.copy(isCheckingForUpdates = false) }
            }
        }
    }

    fun downloadLatestUpdate(scope: CoroutineScope) {
        val latestRelease = uiState.value.appUpdate.toReleaseInfoOrNull() ?: run {
            uiState.update {
                it.copy(userMessage = appContext.getString(R.string.settings_update_download_unavailable))
            }
            return
        }

        scope.launch {
            // Preference snapshots contain legacy fields only; restore validation metadata
            // for this exact release identity, not merely its version name.
            val storedRelease = withContext(Dispatchers.IO) {
                updateMetadataStore.find(latestRelease)
            }
            val hydratedRelease = latestRelease.withStoredMetadataForDownloadOrNull(storedRelease)
            if (hydratedRelease == null) {
                val message = appContext.getString(R.string.settings_update_download_unavailable)
                uiState.update {
                    it.copy(userMessage = message, appUpdate = it.appUpdate.copy(errorMessage = message))
                }
                return@launch
            }
            uiState.update {
                if (it.appUpdate.toReleaseInfoOrNull() == latestRelease) {
                    it.copy(appUpdate = it.appUpdate.withReleaseInfo(hydratedRelease))
                } else {
                    it
                }
            }
            when (val result = appUpdateInstaller.startDownload(hydratedRelease)) {
                is Result.Error -> uiState.update { it.copy(userMessage = result.message) }
                is Result.Success -> uiState.update {
                    it.copy(userMessage = appContext.getString(R.string.settings_update_download_started))
                }
                Result.Loading -> Unit
            }
        }
    }

    fun installDownloadedUpdate(scope: CoroutineScope) {
        scope.launch {
            // A newer check may have replaced the UI release while an older APK is ready.
            val downloadState = appUpdateInstaller.downloadState.value
            val downloadedRelease = downloadState.release ?: withContext(Dispatchers.IO) {
                downloadState.versionName?.let(updateMetadataStore::findByVersion)
            }
            when (val result = appUpdateInstaller.installDownloadedUpdate(
                expectedSha256 = downloadedRelease?.sha256
            )) {
                is Result.Error -> uiState.update { it.copy(userMessage = result.message) }
                is Result.Success -> uiState.update {
                    it.copy(userMessage = appContext.getString(R.string.settings_update_install_started))
                }
                Result.Loading -> Unit
            }
        }
    }

    /**
     * Localized "Update downloaded to: <path>" message, or null if no completed
     * download is present. Shown once a download finishes so the user can find
     * (and manually install) the APK if the in-app install is ever blocked.
     */
    fun downloadedPathMessage(): String? {
        val path = appUpdateInstaller.downloadedApkPath() ?: return null
        return appContext.getString(R.string.settings_update_saved_path, path)
    }
}
