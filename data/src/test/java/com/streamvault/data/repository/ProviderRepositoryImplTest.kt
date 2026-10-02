package com.MegaStream.data.repository

import com.google.common.truth.Truth.assertThat
import com.MegaStream.data.local.DatabaseTransactionRunner
import com.MegaStream.data.local.dao.ChannelDao
import com.MegaStream.data.local.dao.ProgramDao
import com.MegaStream.data.local.dao.ProgramReminderDao
import com.MegaStream.data.local.dao.ProviderDao
import com.MegaStream.data.local.dao.RecordingRunDao
import com.MegaStream.data.local.entity.ProviderEntity
import com.MegaStream.data.manager.recording.RecordingAlarmScheduler
import com.MegaStream.data.manager.reminder.ProgramReminderAlarmScheduler
import com.MegaStream.data.preferences.PreferencesRepository
import com.MegaStream.data.remote.stalker.StalkerApiService
import com.MegaStream.data.remote.xtream.XtreamApiService
import com.MegaStream.data.remote.dto.XtreamAuthResponse
import com.MegaStream.data.remote.dto.XtreamServerInfo
import com.MegaStream.data.remote.dto.XtreamUserInfo
import com.MegaStream.data.security.CredentialCrypto
import com.MegaStream.data.security.CredentialDecryptionException
import com.MegaStream.data.sync.SyncManager
import com.MegaStream.domain.model.ProviderEpgSyncMode
import com.MegaStream.domain.model.ProviderSavedWithSyncErrorException
import com.MegaStream.domain.model.Result
import com.MegaStream.domain.model.SyncState
import com.MegaStream.domain.model.ProviderStatus
import com.MegaStream.domain.model.ProviderType
import com.MegaStream.domain.model.ProviderXtreamLiveSyncMode
import com.MegaStream.domain.model.SyncMetadata
import com.MegaStream.domain.repository.SyncMetadataRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ProviderRepositoryImplTest {

    private val providerDao: ProviderDao = mock()
    private val channelDao: ChannelDao = mock()
    private val programDao: ProgramDao = mock()
    private val recordingRunDao: RecordingRunDao = mock()
    private val programReminderDao: ProgramReminderDao = mock()
    private val stalkerApiService: StalkerApiService = mock()
    private val xtreamApiService: XtreamApiService = mock()
    private val credentialCrypto: CredentialCrypto = mock()
    private val preferencesRepository: PreferencesRepository = mock()
    private val syncManager: SyncManager = mock()
    private val syncMetadataRepository: SyncMetadataRepository = mock()
    private val recordingAlarmScheduler: RecordingAlarmScheduler = mock()
    private val programReminderAlarmScheduler: ProgramReminderAlarmScheduler = mock()
    private val transactionRunner = object : DatabaseTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    private fun createRepository(
        transactionRunner: DatabaseTransactionRunner = this.transactionRunner
    ) = ProviderRepositoryImpl(
        providerDao = providerDao,
        channelDao = channelDao,
        programDao = programDao,
        recordingRunDao = recordingRunDao,
        programReminderDao = programReminderDao,
        stalkerApiService = stalkerApiService,
        xtreamApiService = xtreamApiService,
        credentialCrypto = credentialCrypto,
        preferencesRepository = preferencesRepository,
        syncManager = syncManager,
        syncMetadataRepository = syncMetadataRepository,
        transactionRunner = transactionRunner,
        recordingAlarmScheduler = recordingAlarmScheduler,
        programReminderAlarmScheduler = programReminderAlarmScheduler
    )

    private val repository = createRepository()

    init {
        whenever(preferencesRepository.xtreamBase64TextCompatibility).thenReturn(flowOf(false))
    }

    @Test
    fun `getProviderCredentials decrypts exact IDs even when account keys are duplicated`() = runTest {
        val first = ProviderEntity(id = 7L, name = "First", type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.com", username = "user", password = "enc:v1:first", isActive = false)
        whenever(providerDao.getById(7L)).thenReturn(first)
        whenever(providerDao.getById(8L)).thenReturn(first.copy(id = 8L, password = "enc:v1:second"))
        whenever(credentialCrypto.decryptIfNeeded("enc:v1:first")).thenReturn("first-secret")
        whenever(credentialCrypto.decryptIfNeeded("enc:v1:second")).thenReturn("second-secret")

        assertThat(repository.getProviderCredentials(7L)?.password).isEqualTo("first-secret")
        assertThat(repository.getProviderCredentials(8L)?.password).isEqualTo("second-secret")
        assertThat(repository.getProvider(7L)?.password).isEmpty()
        verify(credentialCrypto).decryptIfNeeded("enc:v1:first")
        verify(credentialCrypto).decryptIfNeeded("enc:v1:second")
        verify(providerDao, never()).getAllSync()
        verify(credentialCrypto, never()).encryptIfNeeded(any())
        verify(providerDao, never()).update(any())
    }

    @Test
    fun `getProviderCredentials returns unknown for missing or blank credentials without decrypting`() = runTest {
        val blank = ProviderEntity(id = 7L, name = "Blank", type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.com", username = "", password = "enc:v1:unused")
        whenever(providerDao.getById(7L)).thenReturn(blank)
        whenever(providerDao.getById(8L)).thenReturn(blank.copy(id = 8L, username = "user", password = ""))
        whenever(providerDao.getById(9L)).thenReturn(null)

        for (id in listOf(0L, 7L, 8L, 9L)) assertThat(repository.getProviderCredentials(id)).isNull()
        verify(providerDao, never()).getById(0L)
        verify(credentialCrypto, never()).decryptIfNeeded(any())
    }

    @Test
    fun `getProviderCredentials never returns unreadable ciphertext or empty decrypted password`() = runTest {
        val stored = ProviderEntity(id = 7L, name = "Unreadable", type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.com", username = "user", password = "enc:v1:broken")
        whenever(providerDao.getById(7L)).thenReturn(stored)
        whenever(credentialCrypto.decryptIfNeeded("enc:v1:broken")).thenThrow(CredentialDecryptionException())
        assertThat(repository.getProviderCredentials(7L)).isNull()
        whenever(providerDao.getById(8L)).thenReturn(stored.copy(id = 8L, password = "enc:v1:empty"))
        whenever(credentialCrypto.decryptIfNeeded("enc:v1:empty")).thenReturn("")
        assertThat(repository.getProviderCredentials(8L)).isNull()
        verify(providerDao, never()).update(any())
    }

    @Test
    fun `getProviderCredentials propagates lookup cancellation`() = runTest {
        whenever(providerDao.getById(7L)).thenThrow(CancellationException("cancelled"))
        var cancelled = false
        try { repository.getProviderCredentials(7L) }
        catch (_: CancellationException) { cancelled = true }
        assertThat(cancelled).isTrue()
        verify(credentialCrypto, never()).decryptIfNeeded(any())
    }

    @Test
    fun `deleteProvider cancels recording and reminder alarms before deleting provider rows`() = runTest {
        whenever(recordingRunDao.getIdsByProvider(7L)).thenReturn(listOf("run-1", "run-2"))
        whenever(programReminderDao.getIdsByProvider(7L)).thenReturn(listOf(11L, 12L))

        val result = repository.deleteProvider(7L)

        assertThat(result.isSuccess).isTrue()
        val inOrder = inOrder(recordingAlarmScheduler, programReminderAlarmScheduler, programDao, providerDao, syncManager)
        inOrder.verify(programDao).deleteByProvider(7L)
        inOrder.verify(providerDao).delete(7L)
        inOrder.verify(recordingAlarmScheduler).cancel("run-1")
        inOrder.verify(recordingAlarmScheduler).cancel("run-2")
        inOrder.verify(programReminderAlarmScheduler).cancel(11L)
        inOrder.verify(programReminderAlarmScheduler).cancel(12L)
        inOrder.verify(syncManager).onProviderDeleted(7L)
        verify(recordingRunDao).getIdsByProvider(7L)
        verify(programReminderDao).getIdsByProvider(7L)
    }

    @Test
    fun `deleteProvider commits database cleanup before sync side effects`() = runTest {
        val events = mutableListOf<String>()
        val trackedTransactionRunner = object : DatabaseTransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T {
                events += "transaction:start"
                return try {
                    block()
                } finally {
                    events += "transaction:end"
                }
            }
        }
        val trackedRepository = createRepository(transactionRunner = trackedTransactionRunner)

        whenever(recordingRunDao.getIdsByProvider(7L)).thenReturn(emptyList())
        whenever(programReminderDao.getIdsByProvider(7L)).thenReturn(emptyList())
        doAnswer {
            events += "programs:delete"
            Unit
        }.whenever(programDao).deleteByProvider(7L)
        doAnswer {
            events += "provider:delete"
            Unit
        }.whenever(providerDao).delete(7L)
        doAnswer {
            events += "sync:cleanup"
            Unit
        }.whenever(syncManager).onProviderDeleted(7L)

        val result = trackedRepository.deleteProvider(7L)

        assertThat(result.isSuccess).isTrue()
        assertThat(events).containsExactly(
            "transaction:start",
            "programs:delete",
            "provider:delete",
            "transaction:end",
            "sync:cleanup"
        ).inOrder()
    }

    @Test
    fun `deleteProvider keeps success after post commit cleanup failure`() = runTest {
        whenever(recordingRunDao.getIdsByProvider(7L)).thenReturn(listOf("run-1"))
        whenever(programReminderDao.getIdsByProvider(7L)).thenReturn(listOf(11L))
        doAnswer { throw IllegalStateException("sync cleanup failed") }
            .whenever(syncManager).onProviderDeleted(7L)

        val result = repository.deleteProvider(7L)

        assertThat(result.isSuccess).isTrue()
        verify(programDao).deleteByProvider(7L)
        verify(providerDao).delete(7L)
        verify(recordingAlarmScheduler).cancel("run-1")
        verify(programReminderAlarmScheduler).cancel(11L)
        verify(syncManager).onProviderDeleted(7L)
    }

    @Test
    fun `validateM3u marks provider active only after successful onboarding`() = runTest {
        val existingProvider = ProviderEntity(
            id = 5L,
            name = "Playlist",
            type = ProviderType.M3U,
            serverUrl = "https://example.com/list.m3u",
            m3uUrl = "https://example.com/list.m3u",
            status = ProviderStatus.UNKNOWN
        )

        whenever(providerDao.getByUrlAndUser("https://example.com/list.m3u", "", "")).thenReturn(existingProvider)
        whenever(providerDao.getById(5L)).thenReturn(existingProvider)
        whenever(syncManager.sync(5L, false, null)).thenReturn(Result.success(Unit))
        whenever(syncManager.currentSyncState(5L)).thenReturn(SyncState.Success(123L))

        val result = repository.validateM3u(
            url = "https://example.com/list.m3u",
            name = "Playlist",
            epgSyncMode = ProviderEpgSyncMode.UPFRONT,
            m3uVodClassificationEnabled = false,
            onProgress = null,
            id = null
        )

        assertThat(result.isSuccess).isTrue()
        verify(providerDao).setActive(5L)
        verify(providerDao, never()).deactivateAll()
        verify(providerDao, never()).activate(5L)
    }

    @Test
    fun `validateM3u preserves subscription start only for the same URL and M3U type`() = runTest {
        val url = "https://example.com/list.m3u"
        val knownStart = 1710801000000L
        whenever(syncManager.sync(eq(5L), eq(false), anyOrNull(), anyOrNull(), anyOrNull(), eq(false)))
            .thenReturn(Result.error("Catalog unavailable"))

        listOf(
            Triple(ProviderType.M3U, url, knownStart),
            Triple(ProviderType.M3U, "https://example.com/other.m3u", null),
            Triple(ProviderType.XTREAM_CODES, url, null)
        ).forEach { (type, previousUrl, expectedStart) ->
            var stored = ProviderEntity(id = 5L, name = "Playlist", type = type,
                serverUrl = previousUrl, m3uUrl = previousUrl, createdAt = 123L,
                subscriptionStartedAt = knownStart)
            whenever(providerDao.getById(5L)).thenAnswer { stored }
            doAnswer { invocation ->
                stored = invocation.getArgument<ProviderEntity>(0)
                Unit
            }.whenever(providerDao).update(any())

            val result = repository.validateM3u(url = url, name = "Playlist",
                epgSyncMode = ProviderEpgSyncMode.UPFRONT, m3uVodClassificationEnabled = false, id = 5L)
            val saved = ((result as Result.Error).exception as ProviderSavedWithSyncErrorException).provider
            assertThat(saved.subscriptionStartedAt).isEqualTo(expectedStart)
            assertThat(stored.subscriptionStartedAt).isEqualTo(expectedStart)
            assertThat(stored.createdAt).isEqualTo(123L)
            assertThat(stored.type).isEqualTo(type)
        }
    }

    @Test
    fun `validateM3u returns saved provider sync error exception when initial sync fails after save`() = runTest {
        whenever(providerDao.getByUrlAndUser("https://example.com/list.m3u", "", "")).thenReturn(null)
        whenever(credentialCrypto.encryptIfNeeded("")).thenReturn("")
        whenever(providerDao.insert(any())).thenReturn(9L)
        whenever(providerDao.getById(9L)).thenReturn(
            ProviderEntity(
                id = 9L,
                name = "Playlist",
                type = ProviderType.M3U,
                serverUrl = "https://example.com/list.m3u",
                m3uUrl = "https://example.com/list.m3u",
                isActive = false,
                status = ProviderStatus.PARTIAL
            )
        )
        whenever(syncManager.sync(eq(9L), eq(false), anyOrNull(), anyOrNull(), anyOrNull(), eq(false)))
            .thenReturn(Result.error("timeout"))

        val result = repository.validateM3u(
            url = "https://example.com/list.m3u",
            name = "Playlist",
            epgSyncMode = ProviderEpgSyncMode.UPFRONT,
            m3uVodClassificationEnabled = false,
            onProgress = {},
            id = null
        )

        assertThat(result.isError).isTrue()
        val failure = (result as Result.Error).exception as ProviderSavedWithSyncErrorException
        assertThat(failure.provider.id).isEqualTo(9L)
        assertThat(failure.provider.status).isEqualTo(ProviderStatus.PARTIAL)
        assertThat(failure.provider.isActive).isFalse()
        assertThat(failure.message).contains("Playlist saved, but initial sync failed")
        verify(providerDao, never()).setActive(9L)
        verify(syncManager).scheduleProviderSyncResume(9L)
    }

    @Test
    fun `validateM3u persists new provider inactive until onboarding succeeds`() = runTest {
        whenever(providerDao.getByUrlAndUser("https://example.com/list.m3u", "", "")).thenReturn(null)
        whenever(credentialCrypto.encryptIfNeeded("")).thenReturn("")
        whenever(providerDao.insert(any())).thenReturn(9L)
        whenever(providerDao.getById(9L)).thenReturn(
            ProviderEntity(
                id = 9L,
                name = "Playlist",
                type = ProviderType.M3U,
                serverUrl = "https://example.com/list.m3u",
                m3uUrl = "https://example.com/list.m3u",
                isActive = false,
                status = ProviderStatus.PARTIAL
            )
        )
        whenever(syncManager.sync(9L, false, null)).thenReturn(Result.success(Unit))
        whenever(syncManager.currentSyncState(9L)).thenReturn(SyncState.Success(123L))

        val result = repository.validateM3u(
            url = "https://example.com/list.m3u",
            name = "Playlist",
            epgSyncMode = ProviderEpgSyncMode.UPFRONT,
            m3uVodClassificationEnabled = false,
            onProgress = null,
            id = null
        )

        assertThat(result.isSuccess).isTrue()
        val insertedProviders = argumentCaptor<ProviderEntity>()
        verify(providerDao).insert(insertedProviders.capture())
        assertThat(insertedProviders.firstValue.isActive).isFalse()
        verify(providerDao).setActive(9L)
    }

    @Test
    fun `refreshProviderData leaves xtream provider inactive partial when sync commits no live channels`() = runTest {
        whenever(providerDao.getById(9L)).thenReturn(
            ProviderEntity(
                id = 9L,
                name = "Xtream",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "https://example.com",
                username = "user",
                isActive = false,
                status = ProviderStatus.PARTIAL,
                lastSyncedAt = 0L
            )
        )
        whenever(syncManager.sync(9L, false, null, null, null)).thenReturn(Result.success(Unit))
        whenever(syncManager.currentSyncState(9L)).thenReturn(SyncState.Success(123L))
        whenever(channelDao.getCount(9L)).thenReturn(flowOf(0))

        val result = repository.refreshProviderData(
            providerId = 9L,
            force = false,
            movieFastSyncOverride = null,
            epgSyncModeOverride = null,
            onProgress = null
        )

        assertThat(result.isSuccess).isTrue()
        val updatedProvider = argumentCaptor<ProviderEntity>()
        verify(providerDao).update(updatedProvider.capture())
        assertThat(updatedProvider.firstValue.isActive).isFalse()
        assertThat(updatedProvider.firstValue.status).isEqualTo(ProviderStatus.PARTIAL)
        assertThat(updatedProvider.firstValue.lastSyncedAt).isGreaterThan(0L)
        verify(syncManager).scheduleProviderSyncResume(9L)
        verify(providerDao, never()).setActive(9L)
    }

    @Test
    fun `validateM3u edit path rejects update when new URL already belongs to a different provider`() = runTest {
        val editTarget = ProviderEntity(
            id = 5L,
            name = "Playlist A",
            type = ProviderType.M3U,
            serverUrl = "https://example.com/a.m3u",
            m3uUrl = "https://example.com/a.m3u",
            status = ProviderStatus.ACTIVE
        )
        val collision = ProviderEntity(
            id = 9L,
            name = "Playlist B",
            type = ProviderType.M3U,
            serverUrl = "https://example.com/b.m3u",
            m3uUrl = "https://example.com/b.m3u",
            status = ProviderStatus.ACTIVE
        )
        // Provider 9 already owns the URL we want to move provider 5 to.
        whenever(providerDao.getByUrlAndUser("https://example.com/b.m3u", "", "")).thenReturn(collision)

        val result = repository.validateM3u(
            url = "https://example.com/b.m3u",
            name = "Playlist A",
            epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
            m3uVodClassificationEnabled = false,
            onProgress = null,
            id = editTarget.id
        )

        assertThat(result.isError).isTrue()
        assertThat((result as Result.Error).message).contains("already exists")
        verify(providerDao, never()).insert(any())
        verify(providerDao, never()).update(any())
    }

    @Test
    fun `validateM3u edit path allows update when URL belongs to the same provider being edited`() = runTest {
        val editTarget = ProviderEntity(
            id = 5L,
            name = "Playlist A",
            type = ProviderType.M3U,
            serverUrl = "https://example.com/a.m3u",
            m3uUrl = "https://example.com/a.m3u",
            status = ProviderStatus.ACTIVE
        )
        // The collision query returns the same provider being edited — that is not a conflict.
        whenever(providerDao.getByUrlAndUser("https://example.com/a.m3u", "", "")).thenReturn(editTarget)
        whenever(providerDao.getById(5L)).thenReturn(editTarget)
        whenever(syncManager.sync(5L, false, null)).thenReturn(Result.success(Unit))
        whenever(syncManager.currentSyncState(5L)).thenReturn(SyncState.Success(123L))

        val result = repository.validateM3u(
            url = "https://example.com/a.m3u",
            name = "Playlist A renamed",
            epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
            m3uVodClassificationEnabled = false,
            onProgress = null,
            id = editTarget.id
        )

        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `setActiveProvider uses transactional activation api`() = runTest {
        whenever(providerDao.getById(9L)).thenReturn(
            ProviderEntity(
                id = 9L,
                name = "Playlist",
                type = ProviderType.M3U,
                serverUrl = "https://example.com/list.m3u",
                m3uUrl = "https://example.com/list.m3u"
            )
        )

        val result = repository.setActiveProvider(9L)

        assertThat(result.isSuccess).isTrue()
        verify(providerDao).setActive(9L)
        verify(providerDao, never()).deactivateAll()
        verify(providerDao, never()).activate(9L)
    }

    @Test
    fun `setActiveProvider rejects xtream provider while live onboarding is incomplete`() = runTest {
        whenever(providerDao.getById(9L)).thenReturn(
            ProviderEntity(
                id = 9L,
                name = "Xtream",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "https://example.com",
                username = "user",
                isActive = false,
                status = ProviderStatus.PARTIAL
            )
        )
        whenever(channelDao.getCount(9L)).thenReturn(flowOf(0))

        val result = repository.setActiveProvider(9L)

        assertThat(result.isError).isTrue()
        assertThat((result as Result.Error).message).contains("no content has been committed yet")
        verify(providerDao, never()).setActive(9L)
        verify(syncManager).scheduleProviderSyncResume(9L)
    }

    @Test
    fun `setActiveProvider allows xtream provider with committed live channels`() = runTest {
        whenever(providerDao.getById(9L)).thenReturn(
            ProviderEntity(
                id = 9L,
                name = "Xtream",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "https://example.com",
                username = "user",
                status = ProviderStatus.PARTIAL
            )
        )
        whenever(channelDao.getCount(9L)).thenReturn(flowOf(12))

        val result = repository.setActiveProvider(9L)

        assertThat(result.isSuccess).isTrue()
        verify(providerDao).setActive(9L)
        verify(syncManager, never()).scheduleProviderSyncResume(9L)
    }

    @Test
    fun `setActiveProvider allows xtream provider with no live but committed vod`() = runTest {
        whenever(providerDao.getById(9L)).thenReturn(
            ProviderEntity(
                id = 9L,
                name = "Xtream",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "https://example.com",
                username = "user",
                status = ProviderStatus.PARTIAL
            )
        )
        whenever(channelDao.getCount(9L)).thenReturn(flowOf(0))
        whenever(syncMetadataRepository.getMetadata(9L)).thenReturn(
            SyncMetadata(providerId = 9L, movieCount = 7)
        )

        val result = repository.setActiveProvider(9L)

        assertThat(result.isSuccess).isTrue()
        verify(providerDao).setActive(9L)
        verify(syncManager, never()).scheduleProviderSyncResume(9L)
    }

    @Test
    fun `loginXtream does not fail onboarding when provider has no live but committed vod`() = runTest {
        whenever(providerDao.getByUrlAndUser("https://example.com", "user")).thenReturn(null)
        whenever(credentialCrypto.encryptIfNeeded("pass")).thenReturn("pass")
        whenever(providerDao.insert(any())).thenReturn(9L)
        whenever(syncManager.sync(eq(9L), eq(false), anyOrNull(), anyOrNull(), anyOrNull(), eq(true)))
            .thenReturn(Result.success(Unit))
        whenever(syncManager.currentSyncState(9L)).thenReturn(SyncState.Success(123L))
        whenever(channelDao.getCount(9L)).thenReturn(flowOf(0))
        whenever(syncMetadataRepository.getMetadata(9L)).thenReturn(
            SyncMetadata(providerId = 9L, movieCount = 3)
        )
        whenever(xtreamApiService.authenticate(any(), any())).thenReturn(
            XtreamAuthResponse(
                userInfo = XtreamUserInfo(
                    username = "user",
                    password = "pass",
                    auth = 1,
                    status = "Active",
                    createdAt = "1710801000"
                ),
                serverInfo = XtreamServerInfo(
                    url = "example.com",
                    port = "80",
                    serverProtocol = "http"
                )
            )
        )

        val result = repository.loginXtream(
            serverUrl = "https://example.com",
            username = "user",
            password = "pass",
            name = "Xtream",
            httpUserAgent = "",
            httpHeaders = "",
            xtreamFastSyncEnabled = false,
            epgSyncMode = ProviderEpgSyncMode.UPFRONT,
            xtreamLiveSyncMode = ProviderXtreamLiveSyncMode.AUTO,
            onProgress = null,
            id = null
        )

        assertThat(result.isSuccess).isTrue()
        val inserted = argumentCaptor<ProviderEntity>()
        verify(providerDao).insert(inserted.capture())
        assertThat(inserted.firstValue.subscriptionStartedAt).isEqualTo(1710801000000L)
        verify(providerDao).setActive(9L)
        verify(syncManager, never()).scheduleProviderSyncResume(9L)
    }

    @Test
    fun `loginXtream preserves known start only for the same account when source is missing`() = runTest {
        val existing = ProviderEntity(id = 9L, name = "Xtream", type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.com", username = "user", password = "pass",
            createdAt = 123L, subscriptionStartedAt = 1710801000000L)
        whenever(providerDao.getByUrlAndUser("https://example.com", "user")).thenReturn(existing)
        whenever(providerDao.getById(9L)).thenReturn(existing)
        whenever(credentialCrypto.encryptIfNeeded("pass")).thenReturn("pass")
        whenever(xtreamApiService.authenticate(any(), any())).thenReturn(
            XtreamAuthResponse(XtreamUserInfo(auth = 1, status = "Active"), XtreamServerInfo())
        )
        whenever(syncManager.sync(eq(9L), eq(false), anyOrNull(), anyOrNull(), anyOrNull(), eq(true)))
            .thenReturn(Result.error("Catalog unavailable"))

        val saved = repository.loginXtream("https://example.com", "user", "pass", "Xtream", "", "",
            false, ProviderEpgSyncMode.UPFRONT, ProviderXtreamLiveSyncMode.AUTO, null, 9L)
        val provider = ((saved as Result.Error).exception as ProviderSavedWithSyncErrorException).provider
        assertThat(provider.subscriptionStartedAt).isEqualTo(existing.subscriptionStartedAt)
        assertThat(provider.createdAt).isEqualTo(123L)

        val changed = repository.loginXtream("https://example.com", "different", "pass", "Xtream", "", "",
            false, ProviderEpgSyncMode.UPFRONT, ProviderXtreamLiveSyncMode.AUTO, null, 9L)
        val changedProvider = ((changed as Result.Error).exception as ProviderSavedWithSyncErrorException).provider
        assertThat(changedProvider.subscriptionStartedAt).isNull()
    }
}
