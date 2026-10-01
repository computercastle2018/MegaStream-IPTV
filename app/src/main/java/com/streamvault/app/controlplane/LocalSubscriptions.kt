package com.MegaStream.app.controlplane

import com.MegaStream.domain.model.Provider
import com.MegaStream.domain.repository.ProviderRepository
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

@Serializable
data class LocalSubscriptionsRequest(val subscriptions: List<LocalSubscription>) {
    init {
        require(subscriptions.size <= 100)
        require(subscriptions.map { it.localId }.distinct().size == subscriptions.size)
    }
}

@Serializable
data class LocalSubscription(
    val localId: Long,
    val name: String,
    val type: String,
    val enabled: Boolean,
    val status: String,
    val expiresAt: Long?,
    val maxConnections: Int,
) {
    init {
        require(localId > 0 && name.isNotBlank() && name.length <= 128)
        require(type in setOf("xtream_codes", "m3u", "stalker_portal"))
        require(status in setOf("active", "partial", "expired", "disabled", "error", "unknown"))
        require(expiresAt == null || expiresAt in 0..253402300799999L)
        require(maxConnections > 0)
    }

    override fun toString(): String = "LocalSubscription(id=$localId, [REDACTED])"

    companion object {
        fun from(provider: Provider): LocalSubscription {
            // Labels can themselves contain pasted login URLs or credentials; never send those.
            val label = provider.name.trim()
            val credentials = listOf(provider.username, provider.password, provider.stalkerMacAddress)
            val safe = label.all { it.isLetterOrDigit() || it in " -_()." } &&
                !label.contains("www.", ignoreCase = true) &&
                credentials.none { it.isNotBlank() && label.contains(it, ignoreCase = true) }
            return LocalSubscription(
                provider.id, if (safe) label.take(128) else "Subscription ${provider.id}",
                provider.type.name.lowercase(Locale.ROOT), provider.isActive,
                provider.status.name.lowercase(Locale.ROOT),
                provider.expirationDate?.takeIf { it in 0..253402300799999L }, provider.maxConnections,
            )
        }
    }
}

internal class LocalSubscriptionsUploader(
    private val providers: ProviderRepository,
    private val client: ControlPlaneClient,
    private val credentials: InstallationCredentials,
) {
    suspend fun upload(): ControlPlaneResult<Unit> {
        val snapshot = providers.getProviders().first()
        if (snapshot.size > 100) return ControlPlaneResult.Failure(ControlPlaneError.local("payload_too_large"))
        return client.reportLocalSubscriptions(credentials.credential,
            LocalSubscriptionsRequest(snapshot.map(LocalSubscription::from)))
    }
}
