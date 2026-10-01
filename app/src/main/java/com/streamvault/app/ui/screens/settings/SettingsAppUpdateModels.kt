package com.MegaStream.app.ui.screens.settings

import com.MegaStream.app.update.AppUpdateDownloadState
import com.MegaStream.app.update.AppUpdateDownloadStatus
import com.MegaStream.app.update.AppUpdateChannel
import com.MegaStream.app.update.AppUpdateSource
import com.MegaStream.app.update.BackupUpdateManifest
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
    val downloadedVersionName: String? = null,
    val sha256: String? = null,
    val packageName: String? = null,
    val signingCertificateSha256: String? = null,
    val releaseId: String? = null,
    val minSdk: Int? = null,
    val sizeBytes: Long? = null,
    val mandatory: Boolean = false,
    val source: AppUpdateSource = AppUpdateSource.GitHub
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
        publishedAt = publishedAt,
        sha256 = sha256,
        packageName = packageName,
        signingCertificateSha256 = signingCertificateSha256,
        releaseId = releaseId,
        minSdk = minSdk,
        sizeBytes = sizeBytes,
        mandatory = mandatory,
        source = source
    )
}

/** Backup downloads cannot fall back to the lossy legacy preferences cache. */
internal fun GitHubReleaseInfo.withStoredMetadataForDownloadOrNull(
    storedRelease: GitHubReleaseInfo?
): GitHubReleaseInfo? {
    val isBackup = source == AppUpdateSource.Backup || storedRelease?.source == AppUpdateSource.Backup ||
        downloadUrl?.let(BackupUpdateManifest::isTrustedUrl) == true
    if (isBackup && storedRelease?.sha256?.matches(Regex("[a-fA-F0-9]{64}")) != true) return null
    return storedRelease ?: this
}

internal fun AppUpdateUiModel.withReleaseInfo(release: GitHubReleaseInfo): AppUpdateUiModel = copy(
    latestVersionName = release.versionName,
    latestVersionCode = release.versionCode,
    releaseUrl = release.releaseUrl,
    downloadUrl = release.downloadUrl,
    releaseNotes = release.releaseNotes,
    publishedAt = release.publishedAt,
    sha256 = release.sha256,
    packageName = release.packageName,
    signingCertificateSha256 = release.signingCertificateSha256,
    releaseId = release.releaseId,
    minSdk = release.minSdk,
    sizeBytes = release.sizeBytes,
    mandatory = release.mandatory,
    source = release.source
)

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
