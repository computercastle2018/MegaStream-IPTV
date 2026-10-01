package com.MegaStream.app.update

import com.MegaStream.domain.model.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** Transport DTO: the only supported operation is an application update. */
@Serializable
data class RemoteUpdateCommand(
    val commandId: String,
    val release: GitHubReleaseInfo,
    val preferManaged: Boolean = false,
    val action: RemoteUpdateCommandAction = RemoteUpdateCommandAction.Update
)

@Serializable
enum class RemoteUpdateCommandAction { Update }
enum class RemoteUpdateCommandStatus {
    Pending, Acknowledged, Downloading, Downloaded, InstallPrompted, Installed, Failed
}

/** Safe public diagnostics: backend/installer messages must never enter the ledger or sink. */
enum class RemoteUpdateFailureCode(val code: String) {
    InvalidRelease("invalid_release"),
    NotNewer("not_newer"),
    DownloadConflict("download_conflict"),
    DownloadFailed("download_failed"),
    IntegrityFailed("integrity_failed"),
    PackageMismatch("package_mismatch"),
    SignatureMismatch("signature_mismatch"),
    InstallDenied("install_denied"),
    InstallerUnavailable("installer_unavailable"),
    StateConflict("state_conflict"),
    Unknown("unknown");

    companion object {
        fun fromCode(code: String): RemoteUpdateFailureCode =
            entries.firstOrNull { it.code == code }
                ?: Unknown
    }
}

data class RemoteUpdateCommandRecord(
    val commandId: String,
    val expectedVersionCode: Long?,
    val versionName: String,
    val expectedSha256: String?,
    val downloadUrl: String?,
    val preferManaged: Boolean,
    val status: RemoteUpdateCommandStatus = RemoteUpdateCommandStatus.Pending,
    val downloadId: Long? = null,
    val downloadIdentified: Boolean = false,
    val installAttempted: Boolean = false,
    val failure: RemoteUpdateFailureCode? = null
)

/** Implementations must durably and atomically commit before returning true.
 * Throw on persistence failure; never treat an unreadable store as empty. claim must
 * reject a new ID while another nonterminal command exists (throw, do not return false).
 * false from claim means this exact ID already exists. CAS must be atomic across instances.
 */
interface RemoteUpdateCommandStore {
    suspend fun claim(record: RemoteUpdateCommandRecord): Boolean
    suspend fun get(commandId: String): RemoteUpdateCommandRecord?
    suspend fun compareAndSet(previous: RemoteUpdateCommandRecord, next: RemoteUpdateCommandRecord): Boolean
}

fun interface RemoteUpdateCommandStatusSink {
    suspend fun report(record: RemoteUpdateCommandRecord)
}

fun interface RemoteUpdateCommandInstalledVersionObserver {
    suspend fun installedVersionCode(): Long
}

interface RemoteUpdateCommandDownloader {
    val downloadState: StateFlow<AppUpdateDownloadState>
    suspend fun startDownload(release: GitHubReleaseInfo): Result<Unit>
    suspend fun refreshState(): AppUpdateDownloadState
    suspend fun installDownloadedUpdate(expectedSha256: String? = null, preferManaged: Boolean = false): Result<Unit>
}

/** No jobs, polling, networking or autonomous retries. The caller explicitly drives progress.
 * A claim is never removed, including on failure/cancellation. A crash in the claim/start gap
 * sacrifices execution rather than risking a repeated download. A duplicate process call only
 * observes installation; refresh may finish an already identified download after a restart.
 */
class RemoteUpdateCommandProcessor(
    private val store: RemoteUpdateCommandStore,
    private val downloader: RemoteUpdateCommandDownloader,
    private val installedVersion: RemoteUpdateCommandInstalledVersionObserver,
    private val sink: RemoteUpdateCommandStatusSink
) {
    suspend fun process(command: RemoteUpdateCommand): RemoteUpdateCommandRecord {
        require(command.commandId.isNotBlank()) { "Command ID is required" }
        val initial = RemoteUpdateCommandRecord(
            commandId = command.commandId,
            expectedVersionCode = command.release.versionCode?.toLong(),
            versionName = command.release.versionName,
            expectedSha256 = command.release.sha256,
            downloadUrl = command.release.downloadUrl,
            preferManaged = command.preferManaged
        )
        // Persistence exceptions deliberately escape: no side effect is safe without a durable claim.
        if (!store.claim(initial)) return observe(command.commandId)
        return run {
            sink.report(initial)
            val currentVersion = installedVersion.installedVersionCode()
            val expected = initial.expectedVersionCode
            if (expected == null || expected <= currentVersion || currentVersion < 0) {
                return@run fail(initial, RemoteUpdateFailureCode.NotNewer)
            }
            if (!validRelease(command.release)) return@run fail(initial, RemoteUpdateFailureCode.InvalidRelease)
            val acknowledged = transition(initial, initial.copy(status = RemoteUpdateCommandStatus.Acknowledged))
                ?: return@run observe(initial.commandId)
            val state = downloader.refreshState()
            if (state.status != AppUpdateDownloadStatus.Idle || state.downloadId != null || state.versionName != null || state.release != null) {
                return@run fail(acknowledged, RemoteUpdateFailureCode.DownloadConflict)
            }
            // Recheck immediately before download, not a version-name/date policy.
            val beforeDownload = installedVersion.installedVersionCode()
            if (beforeDownload < 0 || expected <= beforeDownload) {
                return@run fail(acknowledged, RemoteUpdateFailureCode.NotNewer)
            }
            val downloading = transition(acknowledged, acknowledged.copy(status = RemoteUpdateCommandStatus.Downloading))
                ?: return@run observe(initial.commandId)
            when (downloadAction { downloader.startDownload(command.release) }) {
                is Result.Error -> fail(downloading, RemoteUpdateFailureCode.DownloadFailed)
                Result.Loading -> fail(downloading, RemoteUpdateFailureCode.DownloadFailed)
                is Result.Success -> {
                    val started = downloader.downloadState.value
                    if (started.status !in setOf(AppUpdateDownloadStatus.Downloading, AppUpdateDownloadStatus.Downloaded) ||
                        !matchesRelease(started, downloading)) {
                        fail(downloading, RemoteUpdateFailureCode.StateConflict)
                    } else {
                        transition(downloading, downloading.copy(downloadId = started.downloadId, downloadIdentified = true))
                            ?: observe(initial.commandId)
                    }
                }
            }
        }
    }

    /** Reports exact installed-code evidence only; never downloads or prompts an installer. */
    suspend fun observe(commandId: String): RemoteUpdateCommandRecord = run {
        val record = requireRecord(commandId)
        val observed = installedVersion.installedVersionCode()
        // Invalid/rejected commands must not later become Installed by coincidence.
        if (record.status != RemoteUpdateCommandStatus.Pending &&
            record.status != RemoteUpdateCommandStatus.Failed &&
            record.expectedVersionCode != null && observed == record.expectedVersionCode &&
            record.status != RemoteUpdateCommandStatus.Installed) {
            transition(record, record.copy(status = RemoteUpdateCommandStatus.Installed)) ?: requireRecord(commandId)
        } else {
            sink.report(record)
            record
        }
    }

    suspend fun refresh(commandId: String): RemoteUpdateCommandRecord = run {
        val record = observe(commandId)
        if (record.status == RemoteUpdateCommandStatus.InstallPrompted) {
            val promptedState = downloader.refreshState()
            return@run if (promptedState.status == AppUpdateDownloadStatus.Failed && matchesRelease(promptedState, record)) {
                fail(record, RemoteUpdateFailureCode.InstallerUnavailable)
            } else record
        }
        if (record.status !in setOf(RemoteUpdateCommandStatus.Downloading, RemoteUpdateCommandStatus.Downloaded) ||
            record.installAttempted) return@run record
        if (!record.downloadIdentified) return@run record
        val state = downloader.refreshState()
        if (state.status == AppUpdateDownloadStatus.Failed) return@run fail(record, RemoteUpdateFailureCode.DownloadFailed)
        if (!matchesRelease(state, record) ||
            (state.downloadId != null && record.downloadId != null && state.downloadId != record.downloadId)) {
            return@run fail(record, RemoteUpdateFailureCode.StateConflict)
        }
        when (state.status) {
            AppUpdateDownloadStatus.Downloading -> record
            AppUpdateDownloadStatus.Idle -> fail(record, RemoteUpdateFailureCode.StateConflict)
            AppUpdateDownloadStatus.Failed -> fail(record, RemoteUpdateFailureCode.DownloadFailed)
            AppUpdateDownloadStatus.Downloaded -> {
                val downloaded = if (record.status == RemoteUpdateCommandStatus.Downloaded) record else
                    transition(record, record.copy(status = RemoteUpdateCommandStatus.Downloaded))
                        ?: return@run requireRecord(commandId)
                val installed = installedVersion.installedVersionCode()
                if (installed == downloaded.expectedVersionCode) return@run observe(commandId)
                if (installed < 0 || installed > (downloaded.expectedVersionCode ?: -1)) {
                    return@run fail(downloaded, RemoteUpdateFailureCode.NotNewer)
                }
                // Persist the attempt BEFORE calling the installer. An interrupted attempt is never replayed.
                val attempted = transition(downloaded, downloaded.copy(installAttempted = true))
                    ?: return@run requireRecord(commandId)
                when (downloadAction { downloader.installDownloadedUpdate(attempted.expectedSha256, attempted.preferManaged) }) {
                    is Result.Success -> transition(attempted, attempted.copy(status = RemoteUpdateCommandStatus.InstallPrompted))
                        ?: requireRecord(commandId)
                    is Result.Error -> fail(attempted, RemoteUpdateFailureCode.InstallerUnavailable)
                    Result.Loading -> fail(attempted, RemoteUpdateFailureCode.InstallerUnavailable)
                }
            }
        }
    }

    private suspend fun requireRecord(id: String) = requireNotNull(store.get(id)) { "Unknown command ID" }

    private suspend fun transition(previous: RemoteUpdateCommandRecord, next: RemoteUpdateCommandRecord): RemoteUpdateCommandRecord? {
        if (!store.compareAndSet(previous, next)) return null
        sink.report(next)
        return next
    }

    private suspend fun fail(record: RemoteUpdateCommandRecord, failure: RemoteUpdateFailureCode): RemoteUpdateCommandRecord =
        transition(record, record.copy(status = RemoteUpdateCommandStatus.Failed, failure = failure))
            ?: requireRecord(record.commandId)

    // Adapter exceptions are terminal operation failures, not retries. Persistence and sink
    // exceptions are deliberately outside this boundary; cancellation remains caller-owned.
    private suspend fun downloadAction(block: suspend () -> Result<Unit>): Result<Unit> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Result.error("Update operation failed")
    }

    private fun matchesRelease(state: AppUpdateDownloadState, record: RemoteUpdateCommandRecord): Boolean {
        val release = state.release ?: return false
        if (!validRelease(release) || release.versionCode == null || release.versionCode <= 0 ||
            record.expectedSha256 == null || !record.expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) return false
        return state.versionName == record.versionName && release.versionName == record.versionName &&
            release.versionCode.toLong() == record.expectedVersionCode &&
            release.downloadUrl == record.downloadUrl && release.sha256.equals(record.expectedSha256, ignoreCase = true)
    }

    private fun validRelease(release: GitHubReleaseInfo): Boolean {
        if (release.versionName.isBlank() || release.versionName.any { it == '/' || it == '\\' } ||
            release.versionName == "." || release.versionName == "..") return false
        if (release.sha256 == null || !release.sha256.matches(Regex("[a-fA-F0-9]{64}"))) return false
        return release.source == AppUpdateSource.Backup &&
            BackupUpdateManifest.isTrustedUrl(release.releaseUrl) &&
            BackupUpdateManifest.isTrustedUrl(release.downloadUrl ?: return false)
    }
}
