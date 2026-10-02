package com.MegaStream.app.update

import com.MegaStream.domain.model.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteUpdateCommandProcessorTest {
    @Test fun missingEqualAndOlderNumericVersionsNeverDownload() = runBlocking {
        for (code in listOf(null, 9, 10)) {
            val f = Fixture()
            assertEquals(RemoteUpdateCommandStatus.Failed, f.processor().process(command(code = code)).status)
            assertEquals(0, f.downloader.starts)
        }
    }

    @Test fun invalidOriginSourceAndChecksumNeverDownload() = runBlocking {
        val original = command().release
        val invalid = listOf(
            original.copy(downloadUrl = "http://megastrem.megastation.uk/app.apk"),
            original.copy(downloadUrl = "https://evil.example/app.apk"),
            original.copy(downloadUrl = "https://megastrem.megastation.uk:444/app.apk"),
            original.copy(downloadUrl = "https://user@megastrem.megastation.uk/app.apk"),
            original.copy(downloadUrl = "https://megastrem.megastation.uk/app.apk#x"),
            original.copy(releaseUrl = "https://evil.example/release"),
            original.copy(source = AppUpdateSource.GitHub),
            original.copy(sha256 = null), original.copy(sha256 = "bad"),
            original.copy(versionName = "../escape")
        )
        for (release in invalid) {
            val f = Fixture()
            assertEquals(RemoteUpdateCommandStatus.Failed, f.processor().process(command().copy(release = release)).status)
            assertEquals(0, f.downloader.starts)
        }
    }

    @Test fun unknownInstalledVersionFailsClosed() = runBlocking {
        val f = Fixture()
        f.version = -1
        assertEquals(RemoteUpdateCommandStatus.Failed, f.processor().process(command()).status)
        assertEquals(0, f.downloader.starts)
    }

    @Test fun installSuccessIsPromptedUntilExactVersionObservedAcrossRestart() = runBlocking {
        val f = Fixture()
        val processor = f.processor()
        processor.process(command())
        f.downloader.complete(command().release)
        assertEquals(RemoteUpdateCommandStatus.InstallPrompted, processor.refresh("id").status)
        assertEquals(1, f.downloader.installs)
        assertEquals(HASH, f.downloader.installHash)
        f.version = 11
        assertEquals(RemoteUpdateCommandStatus.Installed, f.processor().observe("id").status)
        expectFailure { f.processor().process(command(code = 99)) }
        f.processor().process(command())
        assertEquals(1, f.downloader.starts)
        assertEquals(1, f.downloader.installs)
        assertEquals(listOf(
            RemoteUpdateCommandStatus.Pending, RemoteUpdateCommandStatus.Acknowledged,
            RemoteUpdateCommandStatus.Downloading, RemoteUpdateCommandStatus.Downloaded,
            RemoteUpdateCommandStatus.InstallPrompted, RemoteUpdateCommandStatus.Installed
        ), f.events.map { it.status }.distinct())
    }

    @Test fun restartCanCompleteNullIdDownloadUsingFullReleaseIdentity() = runBlocking {
        for (hash in listOf(HASH, HASH.uppercase())) {
            val f = Fixture()
            f.downloader.id = null
            f.processor().process(command())
            f.downloader.complete(command().release.copy(sha256 = hash))
            assertEquals(RemoteUpdateCommandStatus.InstallPrompted, f.processor().refresh("id").status)
            assertEquals(1, f.downloader.starts)
            assertEquals(1, f.downloader.installs)
        }
    }

    @Test fun externallyInstalledNewerVersionRetiresCommandWithoutDowngradeOrFalseInstalledStatus() = runBlocking {
        val f = Fixture()
        f.processor().process(command())
        f.version = 12
        assertEquals(RemoteUpdateCommandStatus.Failed, f.processor().observe("id").status)
        assertEquals(RemoteUpdateFailureCode.NotNewer, f.store.get("id")?.failure)
        assertEquals(0, f.downloader.installs)
        assertFalse(f.events.any { it.status == RemoteUpdateCommandStatus.Installed })
    }

    @Test fun sameNameForeignReleaseAndForeignDownloadIdNeverInstall() = runBlocking {
        val original = command().release
        val states = listOf(
            AppUpdateDownloadState(AppUpdateDownloadStatus.Downloaded, original.versionName, null, original.copy(versionCode = 12)),
            AppUpdateDownloadState(AppUpdateDownloadStatus.Downloaded, original.versionName, null, original.copy(downloadUrl = "https://megastrem.megastation.uk/other.apk")),
            AppUpdateDownloadState(AppUpdateDownloadStatus.Downloaded, original.versionName, null, original.copy(sha256 = "b".repeat(64))),
            AppUpdateDownloadState(AppUpdateDownloadStatus.Downloaded, original.versionName, 999, original),
            AppUpdateDownloadState(AppUpdateDownloadStatus.Downloaded, original.versionName, null, null),
            AppUpdateDownloadState(AppUpdateDownloadStatus.Failed, original.versionName, null, original)
        )
        for (state in states) {
            val f = Fixture()
            f.processor().process(command())
            f.downloader.downloadState.value = state
            assertEquals(RemoteUpdateCommandStatus.Failed, f.processor().refresh("id").status)
            assertEquals(0, f.downloader.installs)
        }
    }

    @Test fun existingDownloadIsNotReplacedEvenWhenMarketingNameMatches() = runBlocking {
        val f = Fixture()
        f.downloader.complete(command().release)
        assertEquals(RemoteUpdateCommandStatus.Failed, f.processor().process(command()).status)
        assertEquals(0, f.downloader.starts)
        assertEquals(0, f.downloader.installs)
    }

    @Test fun duplicateAndRefreshDuringSuspendedStartDoNotReplayOrRetireClaim() = runBlocking {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.downloader.startHook = { entered.complete(Unit); release.await() }
        val first = async { f.processor().process(command()) }
        entered.await()
        expectFailure { f.processor().process(command(code = 999)) }
        assertEquals(RemoteUpdateCommandStatus.Downloading, f.processor().process(command()).status)
        assertEquals(RemoteUpdateCommandStatus.Downloading, f.processor().refresh("id").status)
        assertEquals(1, f.downloader.starts)
        release.complete(Unit)
        first.await()
        assertTrue(f.store.get("id")!!.downloadIdentified)
    }

    @Test fun anotherCommandCannotStartWhileOriginalIsActive() = runBlocking {
        val f = Fixture()
        f.processor().process(command())
        expectFailure { f.processor().process(command().copy(commandId = "other")) }
        assertEquals(1, f.downloader.starts)
    }

    @Test fun claimOrTransitionPersistenceFailurePreventsSideEffects() = runBlocking {
        for (failClaim in listOf(true, false)) {
            val f = Fixture()
            f.store.failClaim = failClaim
            f.store.failWrites = !failClaim
            expectFailure { f.processor().process(command()) }
            assertEquals(0, f.downloader.starts)
            assertEquals(0, f.downloader.installs)
        }
    }

    @Test fun installAttemptMustBeDurableBeforeInstallerRuns() = runBlocking {
        val f = Fixture()
        f.processor().process(command())
        f.downloader.complete(command().release)
        f.store.failInstallAttempt = true
        expectFailure { f.processor().refresh("id") }
        assertEquals(0, f.downloader.installs)
    }

    @Test fun cancelledInstallationIsNeverReplayedButCanBeObservedInstalled() = runBlocking {
        val f = Fixture()
        f.processor().process(command())
        f.downloader.complete(command().release)
        f.downloader.installHook = { throw CancellationException("cancelled") }
        try {
            f.processor().refresh("id")
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(1, f.downloader.installs)
        f.processor().refresh("id")
        assertEquals(1, f.downloader.installs)
        f.version = 11
        assertEquals(RemoteUpdateCommandStatus.Installed, f.processor().observe("id").status)
    }

    @Test fun downloadErrorAndThrownErrorBecomeFailedWithoutRetry() = runBlocking {
        for (throws in listOf(false, true)) {
            val f = Fixture()
            if (throws) f.downloader.startHook = { error("transport broke") }
            else f.downloader.startResult = Result.error("transport broke")
            assertEquals(RemoteUpdateCommandStatus.Failed, f.processor().process(command()).status)
            f.processor().process(command())
            assertEquals(1, f.downloader.starts)
            assertEquals(0, f.downloader.installs)
        }
    }

    @Test fun installedSinkFailureDoesNotOverwriteDurableInstalledEvidence() = runBlocking {
        val f = Fixture()
        f.processor().process(command())
        f.version = 11
        val failingSink = RemoteUpdateCommandStatusSink { error("sink offline") }
        expectFailure { f.processor(failingSink).observe("id") }
        assertEquals(RemoteUpdateCommandStatus.Installed, f.store.get("id")!!.status)
    }

    @Test fun promptedManagedFailureIsReportedOnlyForMatchingRelease() = runBlocking {
        val f = Fixture()
        f.processor().process(command())
        f.downloader.complete(command().release)
        f.processor().refresh("id")
        f.downloader.downloadState.value = AppUpdateDownloadState(
            AppUpdateDownloadStatus.Failed, "1.1", null, command().release.copy(versionCode = 12)
        )
        assertEquals(RemoteUpdateCommandStatus.InstallPrompted, f.processor().refresh("id").status)
        f.downloader.downloadState.value = AppUpdateDownloadState(
            AppUpdateDownloadStatus.Failed, "1.1", null, command().release
        )
        assertEquals(RemoteUpdateCommandStatus.Failed, f.processor().refresh("id").status)
        assertEquals(1, f.downloader.installs)
    }

    @Test fun corruptedPersistedIdentityCannotAuthorizeInstall() = runBlocking {
        for (missingHash in listOf(true, false)) {
            val f = Fixture()
            f.processor().process(command())
            val saved = f.store.get("id")!!
            val corrupted = if (missingHash) saved.copy(expectedSha256 = null) else saved.copy(expectedVersionCode = null)
            f.store.compareAndSet(saved, corrupted)
            f.downloader.complete(if (missingHash) command().release.copy(sha256 = null) else command().release.copy(versionCode = null))
            assertEquals(RemoteUpdateCommandStatus.Failed, f.processor().refresh("id").status)
            assertEquals(0, f.downloader.installs)
        }
    }

    @Test fun sensitiveOperationErrorsNeverReachSinkOrLedger() = runBlocking {
        val sensitive = "token=SECRET /data/user/0/private.apk https://user:password@example.test"
        for (install in listOf(false, true)) {
            for (throws in listOf(false, true)) {
                val f = Fixture()
                if (install) {
                    f.processor().process(command())
                    f.downloader.complete(command().release)
                    if (throws) f.downloader.installHook = { error(sensitive) }
                    else f.downloader.installResult = Result.error(sensitive, IllegalStateException(sensitive))
                    f.processor().refresh("id")
                } else {
                    if (throws) f.downloader.startHook = { error(sensitive) }
                    else f.downloader.startResult = Result.error(sensitive, IllegalStateException(sensitive))
                    f.processor().process(command())
                }
                val persisted = f.store.get("id")!!
                val expected = if (install) RemoteUpdateFailureCode.InstallerUnavailable else RemoteUpdateFailureCode.DownloadFailed
                assertEquals(expected, persisted.failure)
                assertEquals(persisted, f.events.last())
                assertFalse(persisted.toString().contains(sensitive))
                assertFalse(f.events.toString().contains(sensitive))
            }
        }
    }

    @Test fun legacyFailureDiagnosticsDecodeToSafeUnknownCode() {
        for (raw in listOf("token=SECRET /private/app.apk", "DOWNLOAD_FAILED", "DownloadFailed", "")) {
            assertEquals(RemoteUpdateFailureCode.Unknown, RemoteUpdateFailureCode.fromCode(raw))
        }
        assertEquals(RemoteUpdateFailureCode.DownloadFailed, RemoteUpdateFailureCode.fromCode("download_failed"))
        assertEquals("unknown", RemoteUpdateFailureCode.fromCode("/private/path").code)
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try { block(); fail("Expected failure") } catch (_: IllegalStateException) { }
    }

    private class Fixture {
        val store = MemoryStore()
        val downloader = FakeDownloader()
        var version = 10L
        val events = mutableListOf<RemoteUpdateCommandRecord>()
        fun processor(sink: RemoteUpdateCommandStatusSink = RemoteUpdateCommandStatusSink { events += it }) =
            RemoteUpdateCommandProcessor(store, downloader, RemoteUpdateCommandInstalledVersionObserver { version }, sink, "installation")
    }

    private class MemoryStore : RemoteUpdateCommandStore {
        private val records = mutableMapOf<String, RemoteUpdateCommandRecord>()
        var failClaim = false
        var failWrites = false
        var failInstallAttempt = false
        override suspend fun claim(record: RemoteUpdateCommandRecord): Boolean = synchronized(records) {
            check(!failClaim)
            if (records.containsKey(record.commandId)) return@synchronized false
            check(records.values.none { it.status != RemoteUpdateCommandStatus.Failed && it.status != RemoteUpdateCommandStatus.Installed })
            records[record.commandId] = record
            true
        }
        override suspend fun get(commandId: String): RemoteUpdateCommandRecord? = synchronized(records) { records[commandId] }
        override suspend fun records(): List<RemoteUpdateCommandRecord> = synchronized(records) { records.values.toList() }
        override suspend fun compareAndSet(previous: RemoteUpdateCommandRecord, next: RemoteUpdateCommandRecord): Boolean = synchronized(records) {
            check(!failWrites && !(failInstallAttempt && next.installAttempted))
            if (records[previous.commandId] != previous) return@synchronized false
            records[next.commandId] = next
            true
        }
    }

    private class FakeDownloader : RemoteUpdateCommandDownloader {
        override val downloadState = MutableStateFlow(AppUpdateDownloadState())
        var id: Long? = 123
        var starts = 0
        var installs = 0
        var installHash: String? = null
        var startHook: suspend () -> Unit = {}
        var installHook: suspend () -> Unit = {}
        var startResult: Result<Unit> = Result.success(Unit)
        var installResult: Result<Unit> = Result.success(Unit)
        override suspend fun startDownload(release: GitHubReleaseInfo): Result<Unit> {
            starts++
            startHook()
            if (startResult is Result.Success) downloadState.value = AppUpdateDownloadState(
                AppUpdateDownloadStatus.Downloading, release.versionName, id, release
            )
            return startResult
        }
        override suspend fun refreshState() = downloadState.value
        override suspend fun installDownloadedUpdate(expectedSha256: String?, preferManaged: Boolean): Result<Unit> {
            installs++
            installHash = expectedSha256
            installHook()
            return installResult
        }
        fun complete(release: GitHubReleaseInfo) {
            downloadState.value = AppUpdateDownloadState(AppUpdateDownloadStatus.Downloaded, release.versionName, null, release)
        }
    }

    private companion object {
        val HASH = "a".repeat(64)
        fun command(code: Int? = 11) = RemoteUpdateCommand("id", GitHubReleaseInfo(
            versionName = "1.1", versionCode = code,
            releaseUrl = "https://megastrem.megastation.uk/release",
            downloadUrl = "https://megastrem.megastation.uk/app.apk",
            releaseNotes = "", publishedAt = null, sha256 = HASH, source = AppUpdateSource.Backup
        ))
    }
}
