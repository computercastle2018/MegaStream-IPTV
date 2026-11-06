package com.MegaStream.app.controlplane.integration

import android.content.Context
import com.MegaStream.app.controlplane.*
import com.MegaStream.app.controlplane.runtime.RuntimeProviderAssignmentSink
import com.MegaStream.domain.model.Provider
import com.MegaStream.domain.model.ProviderType
import com.MegaStream.domain.repository.ProviderRepository
import com.MegaStream.domain.model.Result
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Only assignment IDs, local row IDs and revisions are persisted here, never remote credentials. */
@Singleton
class AndroidProviderAssignmentSink internal constructor(
    private val providers: ProviderRepository,
    private val read: (String) -> Long,
    private val write: (String, Long?) -> Unit,
) : RuntimeProviderAssignmentSink {
    @Inject constructor(@ApplicationContext context: Context, providers: ProviderRepository) : this(
        providers,
        { key -> context.getSharedPreferences("provider-assignments-v1", Context.MODE_PRIVATE).getLong(key, 0) },
        { key, value ->
            val edit = context.getSharedPreferences("provider-assignments-v1", Context.MODE_PRIVATE).edit()
            if (value == null) edit.remove(key) else edit.putLong(key, value)
            check(edit.commit()) { "Assignment storage unavailable" }
        },
    )

    private val lane = Mutex()

    override suspend fun apply(installationId: String, response: ProviderAssignmentsResponse) = lane.withLock {
        for (item in response.tombstones) {
            val key = "$installationId/${item.assignmentId}"
            val id = read(key)
            if (id > 0) requireSuccess(providers.deleteProvider(id))
            write(key, null)
            write("$key/revision", null)
        }
        for (item in response.assignments) {
            val key = "$installationId/${item.assignmentId}"
            val existing = read(key).takeIf { it > 0 }?.let { providers.getProvider(it) }
            if (existing != null && read("$key/revision") == item.profileRevision) continue
            val config = item.configuration
            // shortcut: pipe-bearing headers need a structured header format before they can be imported.
            require(config.httpHeaders.orEmpty().none { (name, value) -> '|' in name || '|' in value })
            val base = Provider(
                id = existing?.id ?: 0,
                name = item.displayName.ifBlank { "Subscription" },
                type = ProviderType.valueOf(item.type.name),
                serverUrl = config.serverUrl,
                epgUrl = config.epgUrl.orEmpty(),
                httpUserAgent = config.httpUserAgent.orEmpty(),
                httpHeaders = config.httpHeaders.orEmpty().entries.joinToString("\n") { "${it.key}: ${it.value}" },
                isActive = existing?.isActive ?: false,
                status = existing?.status ?: com.MegaStream.domain.model.ProviderStatus.UNKNOWN,
                lastSyncedAt = existing?.lastSyncedAt ?: 0,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                expirationDate = existing?.expirationDate,
                maxConnections = existing?.maxConnections ?: 1,
                subscriptionStartedAt = existing?.subscriptionStartedAt,
            )
            val provider = when (config) {
                is XtreamProviderConfiguration -> base.copy(
                    username = config.username, password = config.password,
                    epgSyncMode = com.MegaStream.domain.model.ProviderEpgSyncMode.valueOf(config.epgSyncMode.name),
                    xtreamFastSyncEnabled = config.fastSyncEnabled,
                    xtreamLiveSyncMode = com.MegaStream.domain.model.ProviderXtreamLiveSyncMode.valueOf(config.liveSyncMode.name),
                )
                is M3uProviderConfiguration -> base.copy(
                    m3uUrl = config.m3uUrl, m3uVodClassificationEnabled = config.vodClassificationEnabled,
                    epgSyncMode = com.MegaStream.domain.model.ProviderEpgSyncMode.valueOf(config.epgSyncMode.name),
                )
                is StalkerProviderConfiguration -> base.copy(
                    stalkerMacAddress = config.stalkerMacAddress, serverUrl = config.portalUrl,
                    stalkerDeviceProfile = config.deviceProfile.orEmpty(),
                    stalkerDeviceTimezone = config.timezone.orEmpty(), stalkerDeviceLocale = config.locale.orEmpty(),
                )
            }
            val id = if (existing == null) requireSuccess(providers.addProvider(provider))
                else { requireSuccess(providers.updateProvider(provider)); existing.id }
            write(key, id)
            requireSuccess(providers.refreshProviderData(id, force = true))
            if (read("$key/revision") == 0L || existing?.isActive == true)
                requireSuccess(providers.setActiveProvider(id))
            write("$key/revision", item.profileRevision)
        }
    }

    private fun <T> requireSuccess(result: Result<T>): T =
        (result as? Result.Success)?.data ?: error("Provider assignment could not be applied")
}
