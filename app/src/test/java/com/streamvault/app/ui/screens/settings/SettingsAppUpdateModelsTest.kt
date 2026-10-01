package com.MegaStream.app.ui.screens.settings

import com.google.common.truth.Truth.assertThat
import com.MegaStream.app.update.AppUpdateChannel
import com.MegaStream.app.update.AppUpdateDownloadState
import com.MegaStream.app.update.AppUpdateDownloadStatus
import com.MegaStream.app.update.AppUpdateSource
import com.MegaStream.app.update.GitHubReleaseInfo
import org.junit.Test

class SettingsAppUpdateModelsTest {

    @Test
    fun backupReleaseRetainsValidationMetadataAcrossUiAndDownloadStateRoundtrip() {
        val release = backupRelease()
        val update = AppUpdateUiModel(isUpdateAvailable = true, lastCheckedAt = 123L)
            .withReleaseInfo(release)
            .withDownloadState(AppUpdateDownloadState(AppUpdateDownloadStatus.Downloaded, "2.0.0"))

        assertThat(update.toReleaseInfoOrNull()).isEqualTo(release)
        assertThat(update.isUpdateAvailable).isTrue()
        assertThat(update.lastCheckedAt).isEqualTo(123L)
        assertThat(update.downloadedVersionName).isEqualTo("2.0.0")
        assertThat(update.downloadStatus).isEqualTo(AppUpdateDownloadStatus.Downloaded)
    }

    @Test
    fun replacingBackupReleaseWithLegacyReleaseClearsOldValidationMetadata() {
        val legacy = GitHubReleaseInfo(
            versionName = "3.0.0",
            versionCode = 30,
            releaseUrl = "https://github.com/example/app/releases/tag/v3",
            downloadUrl = null,
            releaseNotes = "Legacy release",
            publishedAt = null
        )
        val update = AppUpdateUiModel().withReleaseInfo(backupRelease()).withReleaseInfo(legacy)

        assertThat(update.toReleaseInfoOrNull()).isEqualTo(legacy)
    }

    @Test
    fun backupSourceUsesDownloadDomainWithoutCredentialsPathOrQuery() {
        val label = formatAppUpdateSourceLabel(
            AppUpdateSource.Backup,
            "https://user:secret@cdn.example.com/app.apk?token=private",
            "https://releases.example.com/latest"
        )

        assertThat(label).isEqualTo("Backup (cdn.example.com)")
        assertThat(formatAppUpdateSourceLabel(AppUpdateSource.GitHub, null, null)).isNull()
    }

    @Test
    fun backupSourceFallsBackSafelyForMissingOrMalformedDownloadUrl() {
        assertThat(formatAppUpdateSourceLabel(AppUpdateSource.Backup, "not a url", "https://releases.example.com/latest"))
            .isEqualTo("Backup (releases.example.com)")
        assertThat(formatAppUpdateSourceLabel(AppUpdateSource.Backup, null, null)).isEqualTo("Backup")
    }

    @Test
    fun cachedBackupNotesKeepSourceWhileReleaseRoundtripKeepsOriginalNotes() {
        val release = backupRelease()

        assertThat(release.releaseNotesForCache()).isEqualTo("Backup (backup.example.com)\n\nSecurity update")
        assertThat(AppUpdateUiModel().withReleaseInfo(release).toReleaseInfoOrNull()?.releaseNotes)
            .isEqualTo("Security update")
        assertThat(release.copy(source = AppUpdateSource.GitHub).releaseNotesForCache())
            .isEqualTo("Security update")
    }

    @Test
    fun backupDownloadsRequireStoredVerificationEvenWhenCacheLostSource() {
        val explicitBackup = backupRelease()
        val cachedBackup = explicitBackup.copy(
            source = AppUpdateSource.GitHub,
            sha256 = null,
            downloadUrl = "https://megastrem.megastation.uk/app.apk"
        )
        for (cachedRelease in listOf(explicitBackup, cachedBackup)) {
            assertThat(cachedRelease.withStoredMetadataForDownloadOrNull(null)).isNull()
            for (invalidSha in listOf(null, "", "abc123", "zz".repeat(32))) {
                assertThat(cachedRelease.withStoredMetadataForDownloadOrNull(cachedRelease.copy(sha256 = invalidSha)))
                    .isNull()
            }
            val storedRelease = cachedRelease.copy(source = AppUpdateSource.Backup, sha256 = "ab".repeat(32))
            assertThat(cachedRelease.withStoredMetadataForDownloadOrNull(storedRelease))
                .isEqualTo(storedRelease)
        }
    }

    @Test
    fun legacyGithubDownloadStillWorksWithoutStoredVerification() {
        val legacyRelease = backupRelease().copy(
            source = AppUpdateSource.GitHub,
            downloadUrl = "https://github.com/example/app/releases/download/v2/app.apk",
            sha256 = null
        )

        assertThat(legacyRelease.withStoredMetadataForDownloadOrNull(null)).isEqualTo(legacyRelease)
    }

    private fun backupRelease() = GitHubReleaseInfo(
        versionName = "2.0.0",
        versionCode = 20,
        releaseUrl = "https://backup.example.com/releases/20",
        downloadUrl = "https://backup.example.com/app.apk",
        releaseNotes = "Security update",
        publishedAt = "2026-09-30T00:00:00Z",
        sha256 = "ab".repeat(32),
        packageName = "com.MegaStream.app",
        signingCertificateSha256 = "cd".repeat(32),
        releaseId = "release-20",
        minSdk = 26,
        sizeBytes = 42_000_000L,
        mandatory = true,
        source = AppUpdateSource.Backup
    )

    @Test
    fun stableBuildIgnoresBetaRelease() {
        val result = isRemoteVersionNewerForBuild(
            remoteVersionCode = 12,
            remoteVersionName = "1.0.11-beta-deadbee",
            remotePublishedAt = "2026-05-14T10:00:00Z",
            currentVersionCode = 12,
            currentVersionName = "1.0.11",
            currentBuildTimestampUtc = 1_747_216_000_000L,
            currentChannel = AppUpdateChannel.Stable
        )

        assertThat(result).isFalse()
    }

    @Test
    fun betaBuildIgnoresStableRelease() {
        val result = isRemoteVersionNewerForBuild(
            remoteVersionCode = 12,
            remoteVersionName = "1.0.11",
            remotePublishedAt = "2026-05-14T10:00:00Z",
            currentVersionCode = 12,
            currentVersionName = "1.0.11-beta",
            currentBuildTimestampUtc = 1_747_216_000_000L,
            currentChannel = AppUpdateChannel.Beta
        )

        assertThat(result).isFalse()
    }

    @Test
    fun betaBuildAcceptsNewerBaseVersionBetaRelease() {
        val result = isRemoteVersionNewerForBuild(
            remoteVersionCode = 13,
            remoteVersionName = "1.0.12-beta-cafebad",
            remotePublishedAt = "2026-05-14T10:00:00Z",
            currentVersionCode = 12,
            currentVersionName = "1.0.11-beta",
            currentBuildTimestampUtc = 1_747_216_000_000L,
            currentChannel = AppUpdateChannel.Beta
        )

        assertThat(result).isTrue()
    }

    @Test
    fun betaBuildAcceptsSameVersionBetaReleaseWhenPublishedLater() {
        val result = isRemoteVersionNewerForBuild(
            remoteVersionCode = 12,
            remoteVersionName = "1.0.11-beta-deadbee",
            remotePublishedAt = "2026-05-14T12:30:00Z",
            currentVersionCode = 12,
            currentVersionName = "1.0.11-beta",
            currentBuildTimestampUtc = 0L,
            currentChannel = AppUpdateChannel.Beta
        )

        assertThat(result).isTrue()
    }

    @Test
    fun betaBuildRejectsSameVersionBetaReleaseWhenNotPublishedLater() {
        val result = isRemoteVersionNewerForBuild(
            remoteVersionCode = 12,
            remoteVersionName = "1.0.11-beta-deadbee",
            remotePublishedAt = "2026-05-14T08:00:00Z",
            currentVersionCode = 12,
            currentVersionName = "1.0.11-beta",
            currentBuildTimestampUtc = Long.MAX_VALUE,
            currentChannel = AppUpdateChannel.Beta
        )

        assertThat(result).isFalse()
    }

    @Test
    fun stableBuildRejectsOlderVersionCodeEvenWhenVersionNameLooksNewer() {
        val result = isRemoteVersionNewerForBuild(
            remoteVersionCode = 19,
            remoteVersionName = "9.9.9",
            remotePublishedAt = "2026-05-14T10:00:00Z",
            currentVersionCode = 20,
            currentVersionName = "1.0.19",
            currentBuildTimestampUtc = 1_747_216_000_000L,
            currentChannel = AppUpdateChannel.Stable
        )

        assertThat(result).isFalse()
    }
}
