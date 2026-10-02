package com.MegaStream.app.controlplane

import com.MegaStream.domain.model.Provider
import com.MegaStream.domain.model.ProviderType
import com.MegaStream.domain.manager.ProviderCredentials
import com.MegaStream.domain.repository.ProviderRepository
import com.MegaStream.data.remote.http.toGenericRequestProfile
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val localSubscriptionsJson = Json { encodeDefaults = true }

@Serializable
data class LocalSubscriptionsRequest(val subscriptions: List<LocalSubscription>) {
    init {
        require(subscriptions.size <= 100)
        require(subscriptions.map { it.localId }.distinct().size == subscriptions.size)
        require(localSubscriptionsJson.encodeToString(this).toByteArray(Charsets.UTF_8).size <= 65_536) {
            "Subscription report exceeds transport limit"
        }
    }
    override fun toString(): String = "LocalSubscriptionsRequest([REDACTED])"
}

@Serializable
class LocalSubscriptionCredentials(
    val serverUrl: String? = null,
    val xtream: LocalXtreamCredentials? = null,
    val m3u: LocalM3uCredentials? = null,
    val stalker: LocalStalkerCredentials? = null,
    val epgUrl: String? = null,
    val httpUserAgent: String? = null,
    val httpHeaders: Map<String, String>? = null,
) {
    init {
        require(listOf(xtream, m3u, stalker).count { it != null } == 1)
        serverUrl?.let(::subscriptionUrl)
        epgUrl?.let(::subscriptionUrl)
        subscriptionText(httpUserAgent, 512)
        require(httpHeaders == null || httpHeaders.size <= 20)
        require(httpHeaders == null || httpHeaders.keys.map { it.lowercase(Locale.ROOT) }.distinct().size == httpHeaders.size)
        httpHeaders?.forEach { (name, value) ->
            require(name.length in 1..64 && name.matches(Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+")))
            require(name.lowercase(Locale.ROOT) !in setOf("host", "content-length", "connection", "transfer-encoding"))
            subscriptionText(value, 512)
        }
        if (xtream != null) subscriptionUrl(requireNotNull(serverUrl))
        require(localSubscriptionsJson.encodeToString(this).toByteArray(Charsets.UTF_8).size <= 24_576)
    }
    override fun toString(): String = "LocalSubscriptionCredentials([REDACTED])"
}

@Serializable
class LocalXtreamCredentials(val username: String, val password: String) {
    init {
        require(username.isNotBlank() && password.isNotBlank())
        subscriptionText(username, 256); subscriptionText(password, 1024)
    }
    override fun toString(): String = "LocalXtreamCredentials([REDACTED])"
}

@Serializable
class LocalM3uCredentials(val m3uUrl: String) {
    init { subscriptionUrl(m3uUrl) }
    override fun toString(): String = "LocalM3uCredentials([REDACTED])"
}

@Serializable
class LocalStalkerCredentials(
    val portalUrl: String,
    val stalkerMacAddress: String,
    val deviceProfile: String? = null,
    val timezone: String? = null,
    val locale: String? = null,
) {
    init {
        subscriptionUrl(portalUrl)
        require(stalkerMacAddress.matches(Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")))
        subscriptionText(deviceProfile, 256); subscriptionText(timezone, 64); subscriptionText(locale, 32)
    }
    override fun toString(): String = "LocalStalkerCredentials([REDACTED])"
}

private fun subscriptionText(value: String?, limit: Int) {
    require(value == null || (value.length <= limit && value.none { it.isISOControl() }))
}

private fun subscriptionUrl(value: String) {
    require(value.length <= 4096 && value.none { it.isISOControl() || it.isWhitespace() || it == '\\' })
    val uri = URI(value)
    require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true))
    require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null)
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
    val startedAt: Long? = null,
    val credentials: LocalSubscriptionCredentials? = null,
) {
    init {
        require(localId > 0 && name.isNotBlank() && name.length <= 128)
        require(type in setOf("xtream_codes", "m3u", "stalker_portal"))
        require(status in setOf("active", "partial", "expired", "disabled", "error", "unknown"))
        require(expiresAt == null || expiresAt in 0..253402300799999L)
        require(maxConnections > 0)
        require(startedAt == null || startedAt in 0..253402300799999L)
        credentials?.let {
            require(when (type) {
                "xtream_codes" -> it.xtream != null
                "m3u" -> it.m3u != null
                else -> it.stalker != null
            })
        }
    }

    override fun toString(): String = "LocalSubscription(id=$localId, [REDACTED])"

    companion object {
        fun from(provider: Provider, decrypted: ProviderCredentials? = null): LocalSubscription {
            // Labels can themselves contain pasted login URLs or credentials; never send those.
            val label = provider.name.trim()
            val secrets = listOf(provider.username, provider.password, provider.stalkerMacAddress, decrypted?.password.orEmpty())
            val safe = label.all { it.isLetterOrDigit() || it in " -_()." } &&
                !label.contains("www.", ignoreCase = true) &&
                secrets.none { it.isNotBlank() && label.contains(it, ignoreCase = true) }
            return LocalSubscription(
                provider.id, if (safe) label.take(128) else "Subscription ${provider.id}",
                provider.type.name.lowercase(Locale.ROOT), provider.isActive,
                provider.status.name.lowercase(Locale.ROOT),
                provider.expirationDate?.takeIf { it in 0..253402300799999L }, provider.maxConnections,
                provider.subscriptionStartedAt,
                configuration(provider, decrypted),
            )
        }

        private fun configuration(provider: Provider, decrypted: ProviderCredentials?): LocalSubscriptionCredentials? {
            return try {
                val profile = provider.toGenericRequestProfile("local-subscription-report")
                val xtream = if (provider.type == ProviderType.XTREAM_CODES) {
                    if (decrypted == null || decrypted.serverUrl != provider.serverUrl || decrypted.username != provider.username) return null
                    LocalXtreamCredentials(decrypted.username, decrypted.password)
                } else null
                LocalSubscriptionCredentials(
                    serverUrl = provider.serverUrl.takeIf { provider.type == ProviderType.XTREAM_CODES },
                    xtream = xtream,
                    m3u = if (provider.type == ProviderType.M3U) LocalM3uCredentials(provider.m3uUrl) else null,
                    stalker = if (provider.type == ProviderType.STALKER_PORTAL) LocalStalkerCredentials(provider.serverUrl,
                        provider.stalkerMacAddress, provider.stalkerDeviceProfile.takeIf(String::isNotBlank),
                        provider.stalkerDeviceTimezone.takeIf(String::isNotBlank), provider.stalkerDeviceLocale.takeIf(String::isNotBlank)) else null,
                    epgUrl = provider.epgUrl.takeIf(String::isNotBlank), httpUserAgent = profile.userAgent,
                    httpHeaders = profile.headers.takeIf { it.isNotEmpty() },
                )
            } catch (_: IllegalArgumentException) { null }
            catch (_: java.net.URISyntaxException) { null }
        }
    }
}

internal class LocalSubscriptionsUploader(
    private val providers: ProviderRepository,
    private val client: ControlPlaneClient,
    private val credentials: InstallationCredentials,
) {
    suspend fun upload(): ControlPlaneResult<Unit> = try {
        val snapshot = providers.getProviders().first()
        if (snapshot.size > 100) {
            ControlPlaneResult.Failure(ControlPlaneError.local("payload_too_large"))
        } else {
            val rows = snapshot.map { provider ->
                val decrypted = if (provider.type == ProviderType.XTREAM_CODES) {
                    try { providers.getProviderCredentials(provider.id) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                } else null
                LocalSubscription.from(provider, decrypted)
            }
            when (val result = client.reportLocalSubscriptions(credentials.credential, LocalSubscriptionsRequest(rows))) {
                is ControlPlaneResult.Success -> result
                // Nested secrets must not escape through echoed server correlation identifiers.
                is ControlPlaneResult.Failure -> ControlPlaneResult.Failure(ControlPlaneError.local("network_error"))
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { ControlPlaneResult.Failure(ControlPlaneError.local("invalid_request")) }
}
