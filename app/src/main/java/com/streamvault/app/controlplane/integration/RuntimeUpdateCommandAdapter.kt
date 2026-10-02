package com.MegaStream.app.controlplane.integration

import com.MegaStream.app.controlplane.ControlPlaneResult
import com.MegaStream.app.controlplane.InstallMode
import com.MegaStream.app.controlplane.UpdateCommand
import com.MegaStream.app.controlplane.UpdateCommandStatus
import com.MegaStream.app.controlplane.UpdateCommandStatusRequest
import com.MegaStream.app.controlplane.runtime.RuntimeUpdateCommandSink
import com.MegaStream.app.update.*
import java.net.URI
import java.util.UUID

/** Driven only by authenticated heartbeats, not a second scheduler. */
class RuntimeUpdateCommandAdapter(
    private val installationId: String,
    private val packageName: String,
    private val store: RemoteUpdateCommandStore,
    downloader: RemoteUpdateCommandDownloader,
    private val installedVersion: RemoteUpdateCommandInstalledVersionObserver,
    private val reportStatus: (String, UpdateCommandStatusRequest) -> ControlPlaneResult<Unit>,
) : RuntimeUpdateCommandSink {
    private val processor = RemoteUpdateCommandProcessor(store, downloader, installedVersion,
        RemoteUpdateCommandStatusSink { }, installationId)

    override suspend fun dispatchOnce(installationId: String, command: UpdateCommand) {
        check(this.installationId == installationId) { "Installation mismatch" }
        val releaseId = UUID.fromString(command.releaseId).toString()
        val uri = URI(command.downloadUrl)
        require(command.releaseId == releaseId && BackupUpdateManifest.isTrustedUrl(command.downloadUrl) &&
            uri.rawQuery == null && uri.rawPath == "/updates/files/$releaseId/release.apk") { "Invalid immutable update URL" }
        require(command.versionCode in 1..Int.MAX_VALUE.toLong() && command.sizeBytes in 1..209_715_200L) { "Invalid release" }
        require(command.versionName.matches(Regex("[0-9]+(?:\\.[0-9]+)*(?:-beta(?:[.-][0-9a-zA-Z]+)*)?", RegexOption.IGNORE_CASE))) {
            "Invalid update version name"
        }
        val release = GitHubReleaseInfo(command.versionName, command.versionCode.toInt(),
            command.downloadUrl, command.downloadUrl, command.notes.orEmpty(), null,
            sha256 = command.sha256, packageName = packageName,
            signingCertificateSha256 = command.signingCertificateSha256,
            releaseId = command.releaseId, mandatory = command.mandatory,
            sizeBytes = command.sizeBytes, source = AppUpdateSource.Backup)
        report(processor.process(RemoteUpdateCommand(command.commandId, release, command.installMode == InstallMode.MANAGED)))
    }

    suspend fun refresh() {
        // ponytail: bounded retained-ledger scan; index active/unreported receipts if command volume grows.
        for (record in store.records()) {
            if (record.statusReported && record.status in setOf(RemoteUpdateCommandStatus.Installed, RemoteUpdateCommandStatus.Failed)) continue
            report(processor.refresh(record.commandId, allowInstallPrompt = false))
        }
    }

    /** Called only by the shared installer after the user presses the existing Install control. */
    suspend fun recordUserInstall(release: GitHubReleaseInfo, status: RemoteUpdateCommandStatus) {
        val record = store.records().singleOrNull {
            it.status !in setOf(RemoteUpdateCommandStatus.Installed, RemoteUpdateCommandStatus.Failed) &&
                it.downloadFingerprint == remoteUpdateDownloadFingerprint(release)
        } ?: return
        val next = when (status) {
            RemoteUpdateCommandStatus.Downloaded -> {
                check(!record.installAttempted) { "Update installation was already attempted" }
                record.copy(status = status, installAttempted = true, statusReported = false)
            }
            RemoteUpdateCommandStatus.InstallPrompted -> record.copy(status = status, statusReported = false)
            RemoteUpdateCommandStatus.Failed -> record.copy(status = status,
                failure = RemoteUpdateFailureCode.InstallerUnavailable, statusReported = false)
            else -> error("Invalid installer receipt")
        }
        if (!store.compareAndSet(record, next)) {
            val latest = requireNotNull(store.get(record.commandId)) { "Update receipt conflict" }
            // Reporting may acknowledge this snapshot while the installer callback commits.
            check(latest.statusReported != record.statusReported &&
                latest.copy(statusReported = record.statusReported) == record) { "Update receipt conflict" }
            check(store.compareAndSet(latest, next)) { "Update receipt conflict" }
        }
    }

    private suspend fun report(record: RemoteUpdateCommandRecord) {
        if (record.statusReported) return
        if (record.status in setOf(RemoteUpdateCommandStatus.Pending, RemoteUpdateCommandStatus.Acknowledged) ||
            (record.status == RemoteUpdateCommandStatus.Downloading && !record.downloadIdentified)) return
        val observed = installedVersion.installedVersionCode()
        if (observed < 0 || (record.status == RemoteUpdateCommandStatus.Installed && observed != record.expectedVersionCode)) return
        val statuses = if (record.status == RemoteUpdateCommandStatus.Failed) listOf(UpdateCommandStatus.FAILED) else
            listOf(UpdateCommandStatus.PENDING, UpdateCommandStatus.ACKNOWLEDGED, UpdateCommandStatus.DOWNLOADING,
                UpdateCommandStatus.DOWNLOADED, UpdateCommandStatus.INSTALL_PROMPTED, UpdateCommandStatus.INSTALLED)
                .take(record.status.ordinal + 1)
        for (status in statuses) {
            val installed = if (status == UpdateCommandStatus.INSTALLED) installedVersion.installedVersionCode() else null
            if (status == UpdateCommandStatus.INSTALLED && installed != record.expectedVersionCode) return
            val result = reportStatus(record.commandId, UpdateCommandStatusRequest(status,
                record.failure?.code.takeIf { status == UpdateCommandStatus.FAILED },
                installed))
            if (result is ControlPlaneResult.Failure) return
        }
        store.compareAndSet(record, record.copy(statusReported = true))
    }
}
