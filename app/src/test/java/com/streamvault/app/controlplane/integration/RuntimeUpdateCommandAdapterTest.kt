package com.MegaStream.app.controlplane.integration

import com.MegaStream.app.controlplane.*
import com.MegaStream.app.update.*
import com.MegaStream.domain.model.Result
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeUpdateCommandAdapterTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun durableReceiptsAreBoundConflictCheckedAndNeverPersistUrlsOrNotes() = runBlocking {
        val f = Fixture(temporaryFolder.newFolder())
        f.adapter().dispatchOnce(INSTALLATION, command())
        f.adapter().dispatchOnce(INSTALLATION, command())
        assertEquals(1, f.downloader.starts)
        assertEquals(RemoteUpdateCommandStatus.Downloading, f.store().get(COMMAND)?.status)
        reject { f.adapter().dispatchOnce(INSTALLATION, command().copy(sha256 = "b".repeat(64))) }
        reject { f.adapter().dispatchOnce(OTHER, command()) }
        reject { DurableUpdateCommandStore(f.directory, OTHER).records() }
        val record = requireNotNull(f.store().get(COMMAND))
        reject { f.store().compareAndSet(record, record.copy(expectedVersionCode = 12)) }
        val disk = File(f.directory, "update-commands.state").readText()
        assertFalse(disk.contains("https://"))
        assertFalse(disk.contains("private-note"))
        assertEquals(1, f.downloader.starts)
    }

    @Test fun nullableServerReleaseNotesDecodeAndDownloadWithoutFabricatedNotes() = runBlocking {
        val fields = Json.encodeToJsonElement(UpdateCommand.serializer(), command()) as JsonObject
        for (payload in listOf(JsonObject(fields + ("notes" to JsonNull)), JsonObject(fields - "notes"))) {
            val decoded = Json.decodeFromJsonElement(UpdateCommand.serializer(), payload)
            assertNull(decoded.notes)
            val f = Fixture(temporaryFolder.newFolder())
            f.adapter().dispatchOnce(INSTALLATION, decoded)
            assertEquals(1, f.downloader.starts)
            assertEquals("", f.downloader.downloadState.value.release?.releaseNotes)
        }
    }

    @Test fun neverInterruptsPlaybackAndReportsUserInstallOnlyWithExactObservedVersionAfterRestart() = runBlocking {
        val f = Fixture(temporaryFolder.newFolder())
        f.adapter().dispatchOnce(INSTALLATION, command())
        f.downloader.complete()
        f.adapter().refresh()
        assertEquals(RemoteUpdateCommandStatus.Downloaded, f.store().get(COMMAND)?.status)
        assertEquals(0, f.downloader.installs)
        f.adapter().refresh()
        assertEquals(0, f.downloader.installs)
        val release = requireNotNull(f.downloader.downloadState.value.release)
        f.adapter().recordUserInstall(release, RemoteUpdateCommandStatus.Downloaded)
        f.adapter().recordUserInstall(release, RemoteUpdateCommandStatus.InstallPrompted)
        assertEquals(RemoteUpdateCommandStatus.InstallPrompted, f.store().get(COMMAND)?.status)
        assertFalse(f.reports.any { it.status == UpdateCommandStatus.INSTALLED })
        f.version = 11
        f.adapter().refresh()
        assertEquals(RemoteUpdateCommandStatus.Installed, f.store().get(COMMAND)?.status)
        assertEquals(11L, f.reports.single { it.status == UpdateCommandStatus.INSTALLED }.observedVersionCode)
        assertTrue(f.store().get(COMMAND)!!.statusReported)
        assertEquals(1, f.downloader.starts)
    }

    @Test fun failedStatusDeliveryRetriesOnHeartbeatWithoutRepeatingDownload() = runBlocking {
        val f = Fixture(temporaryFolder.newFolder())
        f.offline = true
        f.adapter().dispatchOnce(INSTALLATION, command())
        assertFalse(f.store().get(COMMAND)!!.statusReported)
        f.offline = false
        f.adapter().refresh()
        assertTrue(f.store().get(COMMAND)!!.statusReported)
        assertEquals(1, f.downloader.starts)
    }

    @Test fun oldEqualAndAlreadyInstalledCommandsAreRejectedWithoutFabricatedInstalledStatus() = runBlocking {
        for (code in listOf(9L, 10L)) {
            val f = Fixture(temporaryFolder.newFolder())
            f.adapter().dispatchOnce(INSTALLATION, command().copy(versionCode = code))
            f.version = code
            f.adapter().refresh()
            assertEquals(RemoteUpdateCommandStatus.Failed, f.store().get(COMMAND)?.status)
            assertEquals(0, f.downloader.starts)
            assertFalse(f.reports.any { it.status == UpdateCommandStatus.INSTALLED })
        }
    }

    @Test fun nonImmutableOrCredentialBearingUrlsNeverDownloadAndCorruptLedgerFailsClosed() = runBlocking {
        val f = Fixture(temporaryFolder.newFolder())
        for (url in listOf(URL + "?token=SECRET", URL.replace("release.apk", "mutable.apk"),
            URL.replace("https://", "https://user:password@"))) {
            reject { f.adapter().dispatchOnce(INSTALLATION, command().copy(downloadUrl = url)) }
        }
        assertEquals(0, f.downloader.starts)
        f.adapter().dispatchOnce(INSTALLATION, command())
        File(f.directory, "update-commands.state").writeText("corrupt")
        reject { f.adapter().dispatchOnce(INSTALLATION, command()) }
        assertEquals(1, f.downloader.starts)
    }

    @Test fun prePromptReceiptRetriesReportingOnlyRaceButRejectsRealStateChange() = runBlocking {
        checkReceiptRace(RemoteUpdateCommandStatus.Downloaded)
    }

    @Test fun postPromptReceiptRetriesReportingOnlyRaceButRejectsRealStateChange() = runBlocking {
        checkReceiptRace(RemoteUpdateCommandStatus.InstallPrompted)
    }

    private suspend fun checkReceiptRace(status: RemoteUpdateCommandStatus) {
        for (realStateChange in listOf(false, true)) {
            val f = Fixture(temporaryFolder.newFolder())
            f.offline = true
            f.adapter().dispatchOnce(INSTALLATION, command())
            f.downloader.complete()
            f.adapter().refresh()
            val release = requireNotNull(f.downloader.downloadState.value.release)
            if (status == RemoteUpdateCommandStatus.InstallPrompted) {
                f.adapter().recordUserInstall(release, RemoteUpdateCommandStatus.Downloaded)
            }
            val durable = f.store()
            var commits = 0
            val racingStore = object : RemoteUpdateCommandStore by durable {
                override suspend fun compareAndSet(previous: RemoteUpdateCommandRecord, next: RemoteUpdateCommandRecord): Boolean {
                    commits++
                    if (commits == 1) {
                        val concurrent = if (realStateChange) previous.copy(status = RemoteUpdateCommandStatus.Failed,
                            failure = RemoteUpdateFailureCode.DownloadFailed) else previous.copy(statusReported = true)
                        assertTrue(durable.compareAndSet(previous, concurrent))
                    }
                    return durable.compareAndSet(previous, next)
                }
            }
            if (realStateChange) {
                reject { f.adapter(racingStore).recordUserInstall(release, status) }
                assertEquals(1, commits)
                assertEquals(RemoteUpdateCommandStatus.Failed, durable.get(COMMAND)?.status)
                assertEquals(RemoteUpdateFailureCode.DownloadFailed, durable.get(COMMAND)?.failure)
            } else {
                f.adapter(racingStore).recordUserInstall(release, status)
                assertEquals(2, commits)
                val receipt = requireNotNull(durable.get(COMMAND))
                assertEquals(status, receipt.status)
                assertTrue(receipt.installAttempted)
                assertFalse(receipt.statusReported)
                reject { f.adapter().recordUserInstall(release, RemoteUpdateCommandStatus.Downloaded) }
            }
            assertEquals(1, f.downloader.starts)
            assertEquals(0, f.downloader.installs)
            assertFalse(f.reports.any { it.status == UpdateCommandStatus.INSTALLED })
        }
    }

    private suspend fun reject(block: suspend () -> Unit) {
        try { block() } catch (_: IllegalStateException) { return }
        catch (_: IllegalArgumentException) { return }
        fail("Expected rejection")
    }

    private class Fixture(val directory: File) {
        val downloader = Downloader()
        var version = 10L
        var offline = false
        val reports = mutableListOf<UpdateCommandStatusRequest>()
        fun store() = DurableUpdateCommandStore(directory, INSTALLATION)
        fun adapter(store: RemoteUpdateCommandStore = store()) = RuntimeUpdateCommandAdapter(INSTALLATION, "com.megastream.app", store, downloader,
            RemoteUpdateCommandInstalledVersionObserver { version }) { _, report ->
            reports += report
            if (offline) ControlPlaneResult.Failure(ControlPlaneError.local("network_error")) else ControlPlaneResult.Success(Unit)
        }
    }

    private class Downloader : RemoteUpdateCommandDownloader {
        override val downloadState = MutableStateFlow(AppUpdateDownloadState())
        var starts = 0
        var installs = 0
        override suspend fun startDownload(release: GitHubReleaseInfo): Result<Unit> {
            starts++
            downloadState.value = AppUpdateDownloadState(AppUpdateDownloadStatus.Downloading, release.versionName, null, release)
            return Result.success(Unit)
        }
        override suspend fun refreshState() = downloadState.value
        override suspend fun installDownloadedUpdate(expectedSha256: String?, preferManaged: Boolean): Result<Unit> {
            installs++
            assertEquals(HASH, expectedSha256)
            return Result.success(Unit)
        }
        fun complete() { downloadState.value = downloadState.value.copy(status = AppUpdateDownloadStatus.Downloaded) }
    }

    companion object {
        private const val INSTALLATION = "00000000-0000-4000-8000-000000000001"
        private const val OTHER = "00000000-0000-4000-8000-000000000002"
        private const val COMMAND = "00000000-0000-4000-8000-000000000003"
        private const val RELEASE = "00000000-0000-4000-8000-000000000004"
        private const val URL = "https://megastrem.megastation.uk/updates/files/$RELEASE/release.apk"
        private val HASH = "a".repeat(64)
        private fun command() = UpdateCommand(COMMAND, RELEASE, 11L, "3.0.11", false,
            InstallMode.PROMPT, URL, HASH, sizeBytes = 1024L, notes = "private-note")
    }
}
