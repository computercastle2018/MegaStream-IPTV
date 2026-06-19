package com.MegaStream.app.ui.screens.settings

import com.MegaStream.app.update.AppUpdateDownloadState
import com.MegaStream.app.update.AppUpdateDownloadStatus
import com.MegaStream.app.update.AppUpdateChannel
import com.MegaStream.app.update.GitHubReleaseInfo
import com.MegaStream.app.update.isRemoteAppVersionNewer
import com.MegaStream.app.update.isRemoteAppVersionNewerForBuild

data class AppUpdateUiModel(
    val latestVersionName: String? = null,
    val latestVersionCode: Int? = null,
    val releaseUrl: String? = null,
    val downloadUrl: String? = null,
    val releaseNotes: String = "",
    val publishedAt: String? = null,
    val isUpdateAvailable: Boolean = false,
    val lastCheckedAt: Long? = null,
    val errorMessage: String? = null,
    val downloadStatus: AppUpdateDownloadStatus = AppUpdateDownloadStatus.Idle,
    val downloadedVersionName: String? = null
)

internal fun AppUpdateUiModel.toReleaseInfoOrNull(): GitHubReleaseInfo? {
    val versionName = latestVersionName ?: return null
    val releaseUrl = releaseUrl ?: return null
    return GitHubReleaseInfo(
        versionName = versionName,
        versionCode = latestVersionCode,
        releaseUrl = releaseUrl,
        downloadUrl = downloadUrl,
        releaseNotes = releaseNotes,
        publishedAt = publishedAt
    )
}

internal fun AppUpdateUiModel.withDownloadState(downloadState: AppUpdateDownloadState): AppUpdateUiModel {
    return copy(
        downloadStatus = downloadState.status,
        downloadedVersionName = downloadState.versionName
    )
}

internal fun AppUpdateUiModel.toDownloadState(): AppUpdateDownloadState {
    return AppUpdateDownloadState(
        status = downloadStatus,
        versionName = downloadedVersionName
    )
}

internal fun SettingsPreferenceSnapshot.toCachedAppUpdateUiModel(): AppUpdateUiModel {
    val versionName = cachedAppUpdateVersionName
    return AppUpdateUiModel(
        latestVersionName = versionName,
        latestVersionCode = cachedAppUpdateVersionCode,
        releaseUrl = cachedAppUpdateReleaseUrl,
        downloadUrl = cachedAppUpdateDownloadUrl,
        releaseNotes = cachedAppUpdateReleaseNotes,
        publishedAt = cachedAppUpdatePublishedAt,
        isUpdateAvailable = versionName?.let {
            isRemoteVersionNewer(cachedAppUpdateVersionCode, it, cachedAppUpdatePublishedAt)
        } ?: false,
        lastCheckedAt = lastAppUpdateCheckAt
    )
}

internal fun isRemoteVersionNewer(
    remoteVersionCode: Int?,
    remoteVersionName: String,
    remotePublishedAt: String? = null
): Boolean {
    return isRemoteAppVersionNewer(remoteVersionCode, remoteVersionName, remotePublishedAt)
}

internal fun isRemoteVersionNewerForBuild(
    remoteVersionCode: Int?,
    remoteVersionName: String,
    remotePublishedAt: String?,
    currentVersionCode: Int,
    currentVersionName: String,
    currentBuildTimestampUtc: Long,
    currentChannel: AppUpdateChannel
): Boolean {
    return isRemoteAppVersionNewerForBuild(
        remoteVersionCode = remoteVersionCode,
        remoteVersionName = remoteVersionName,
        remotePublishedAt = remotePublishedAt,
        currentVersionCode = currentVersionCode,
        currentVersionName = currentVersionName,
        currentBuildTimestampUtc = currentBuildTimestampUtc,
        currentChannel = currentChannel
    )
}
