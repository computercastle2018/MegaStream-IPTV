package com.MegaStream.app.ui.screens.settings

import com.MegaStream.app.R
import com.MegaStream.app.update.AppUpdateDownloadStatus
import com.MegaStream.app.update.AppUpdateSource
import com.MegaStream.app.update.GitHubReleaseInfo
import java.net.URI
import java.net.URISyntaxException
import java.text.DateFormat

internal fun formatLatestReleaseLabel(update: AppUpdateUiModel, context: android.content.Context): String {
    val versionName = update.latestVersionName ?: return context.getString(R.string.settings_update_not_checked)
    val versionCodeSuffix = update.latestVersionCode?.let { " ($it)" }.orEmpty()
    val sourceSuffix = formatAppUpdateSourceLabel(update.source, update.downloadUrl, update.releaseUrl)
        ?.let { " · $it" }.orEmpty()
    return "$versionName$versionCodeSuffix$sourceSuffix"
}

internal fun formatAppUpdateSourceLabel(
    source: AppUpdateSource,
    downloadUrl: String?,
    releaseUrl: String?
): String? {
    if (source != AppUpdateSource.Backup) return null
    val domain = sequenceOf(downloadUrl, releaseUrl)
        .mapNotNull { url ->
            try {
                url?.let { URI(it).host }
            } catch (_: URISyntaxException) {
                null
            }
        }
        .firstOrNull { it.isNotBlank() }
    return domain?.let { "Backup ($it)" } ?: "Backup"
}

/** Preferences keep only legacy release fields, so retain the source in cached notes too. */
internal fun GitHubReleaseInfo.releaseNotesForCache(): String {
    val sourceLabel = formatAppUpdateSourceLabel(source, downloadUrl, releaseUrl) ?: return releaseNotes
    return if (releaseNotes.isBlank()) sourceLabel else "$sourceLabel\n\n$releaseNotes"
}

internal fun formatUpdateStatusLabel(update: AppUpdateUiModel, context: android.content.Context): String {
    val downloadedReleaseMatchesLatest = update.downloadedVersionName != null &&
        (update.latestVersionName == null || update.downloadedVersionName == update.latestVersionName)
    return when {
        update.errorMessage != null -> context.getString(R.string.settings_update_status_check_failed)
        update.downloadStatus == AppUpdateDownloadStatus.Downloading -> context.getString(R.string.settings_update_status_downloading)
        update.downloadStatus == AppUpdateDownloadStatus.Downloaded && downloadedReleaseMatchesLatest -> context.getString(R.string.settings_update_status_ready_to_install)
        update.latestVersionName == null -> context.getString(R.string.settings_update_not_checked)
        update.isUpdateAvailable -> context.getString(R.string.settings_update_status_available)
        else -> context.getString(R.string.settings_update_status_current)
    }
}

internal fun formatUpdateCheckTimeLabel(timestamp: Long?, context: android.content.Context): String {
    if (timestamp == null || timestamp <= 0L) {
        return context.getString(R.string.settings_update_not_checked)
    }
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(java.util.Date(timestamp))
}

internal fun shouldShowUpdateDownloadAction(update: AppUpdateUiModel): Boolean {
    val downloadedReleaseMatchesLatest = update.downloadedVersionName != null &&
        (update.latestVersionName == null || update.downloadedVersionName == update.latestVersionName)
    return when (update.downloadStatus) {
        AppUpdateDownloadStatus.Downloading,
        AppUpdateDownloadStatus.Downloaded -> downloadedReleaseMatchesLatest || (update.isUpdateAvailable && !update.downloadUrl.isNullOrBlank())
        else -> update.isUpdateAvailable && !update.downloadUrl.isNullOrBlank()
    }
}

internal fun formatUpdateDownloadLabel(update: AppUpdateUiModel, context: android.content.Context): String {
    val downloadedReleaseMatchesLatest = update.downloadedVersionName != null &&
        (update.latestVersionName == null || update.downloadedVersionName == update.latestVersionName)
    return when (update.downloadStatus) {
        AppUpdateDownloadStatus.Downloading -> context.getString(R.string.settings_update_download_in_progress)
        AppUpdateDownloadStatus.Downloaded -> {
            if (downloadedReleaseMatchesLatest) {
                context.getString(R.string.settings_update_install_action)
            } else {
                context.getString(R.string.settings_update_download_action)
            }
        }
        else -> context.getString(R.string.settings_update_download_action)
    }
}