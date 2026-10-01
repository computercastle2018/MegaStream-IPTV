package com.MegaStream.app.controlplane

import java.net.URI
import java.net.URISyntaxException
import java.time.format.DateTimeParseException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject

/** Provider transport JSON is strict independently of a caller's Json configuration. */
val ProviderAssignmentJson: Json = Json {
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
    encodeDefaults = true
}

@Serializable
enum class ProviderAssignmentPolicy {
    @SerialName("optional") OPTIONAL,
    @SerialName("auto_enabled") AUTO_ENABLED,
    @SerialName("required") REQUIRED,
}

@Serializable
enum class ProviderAssignmentType {
    @SerialName("xtream_codes") XTREAM_CODES,
    @SerialName("m3u") M3U,
    @SerialName("stalker_portal") STALKER_PORTAL,
}

@Serializable
enum class ProviderEpgSyncMode {
    @SerialName("upfront") UPFRONT,
    @SerialName("background") BACKGROUND,
    @SerialName("skip") SKIP,
}

@Serializable
enum class ProviderLiveSyncMode {
    @SerialName("auto") AUTO,
    @SerialName("category_by_category") CATEGORY_BY_CATEGORY,
    @SerialName("stream_all") STREAM_ALL,
}

@Serializable
enum class ProviderAssignmentState {
    @SerialName("received") RECEIVED,
    @SerialName("applied") APPLIED,
    @SerialName("disabled_by_user") DISABLED_BY_USER,
    @SerialName("syncing") SYNCING,
    @SerialName("active") ACTIVE,
    @SerialName("expired") EXPIRED,
    @SerialName("error") ERROR,
}

@Serializable
enum class ProviderAssignmentSafeErrorCode {
    @SerialName("invalid_configuration") INVALID_CONFIGURATION,
    @SerialName("authentication_failed") AUTHENTICATION_FAILED,
    @SerialName("subscription_expired") SUBSCRIPTION_EXPIRED,
    @SerialName("network_unavailable") NETWORK_UNAVAILABLE,
    @SerialName("dns_failure") DNS_FAILURE,
    @SerialName("tls_failure") TLS_FAILURE,
    @SerialName("connection_timeout") CONNECTION_TIMEOUT,
    @SerialName("http_401") HTTP_401,
    @SerialName("http_403") HTTP_403,
    @SerialName("http_404") HTTP_404,
    @SerialName("http_429") HTTP_429,
    @SerialName("server_error") SERVER_ERROR,
    @SerialName("parse_failed") PARSE_FAILED,
    @SerialName("sync_failed") SYNC_FAILED,
    @SerialName("unsupported") UNSUPPORTED,
    @SerialName("user_disabled") USER_DISABLED,
    @SerialName("unknown") UNKNOWN,
}

/** No domain models, credential rewriting, or control-plane URL policy apply here. */
sealed class ProviderAssignmentConfiguration {
    abstract val serverUrl: String
    abstract val epgUrl: String?
    abstract val httpUserAgent: String?
    abstract val httpHeaders: Map<String, String>?
    final override fun toString(): String = "ProviderAssignmentConfiguration([REDACTED])"
}

@Serializable(with = XtreamProviderConfigurationSerializer::class)
data class XtreamProviderConfiguration(
    override val serverUrl: String,
    val username: String,
    val password: String,
    val epgSyncMode: ProviderEpgSyncMode,
    val fastSyncEnabled: Boolean,
    val liveSyncMode: ProviderLiveSyncMode,
    override val epgUrl: String? = null,
    override val httpUserAgent: String? = null,
    override val httpHeaders: Map<String, String>? = null,
) : ProviderAssignmentConfiguration() {
    init { providerConfiguration(serverUrl, epgUrl, httpHeaders) }
}

@Serializable(with = M3uProviderConfigurationSerializer::class)
data class M3uProviderConfiguration(
    override val serverUrl: String,
    val m3uUrl: String,
    val epgSyncMode: ProviderEpgSyncMode,
    val vodClassificationEnabled: Boolean,
    override val epgUrl: String? = null,
    override val httpUserAgent: String? = null,
    override val httpHeaders: Map<String, String>? = null,
) : ProviderAssignmentConfiguration() {
    init {
        providerConfiguration(serverUrl, epgUrl, httpHeaders)
        providerUrl(m3uUrl)
    }
}

@Serializable(with = StalkerProviderConfigurationSerializer::class)
data class StalkerProviderConfiguration(
    override val serverUrl: String,
    val portalUrl: String,
    val stalkerMacAddress: String,
    val deviceProfile: String? = null,
    val timezone: String? = null,
    val locale: String? = null,
    override val epgUrl: String? = null,
    override val httpUserAgent: String? = null,
    override val httpHeaders: Map<String, String>? = null,
) : ProviderAssignmentConfiguration() {
    init {
        providerConfiguration(serverUrl, epgUrl, httpHeaders)
        providerUrl(portalUrl)
    }
}

@Serializable(with = ProviderAssignmentSerializer::class)
data class ProviderAssignment(
    val assignmentId: String,
    val profileId: String,
    val assignmentRevision: Long,
    val profileRevision: Long,
    val policy: ProviderAssignmentPolicy,
    val displayName: String,
    val configuration: ProviderAssignmentConfiguration,
) {
    val type: ProviderAssignmentType
        get() = when (configuration) {
            is XtreamProviderConfiguration -> ProviderAssignmentType.XTREAM_CODES
            is M3uProviderConfiguration -> ProviderAssignmentType.M3U
            is StalkerProviderConfiguration -> ProviderAssignmentType.STALKER_PORTAL
        }

    init {
        providerUuid(assignmentId)
        providerUuid(profileId)
        require(assignmentRevision > 0) { "Invalid provider assignment revision" }
        require(profileRevision > 0) { "Invalid provider profile revision" }
    }

    override fun toString(): String = "ProviderAssignment([REDACTED])"
}

@Serializable(with = ProviderAssignmentTombstoneSerializer::class)
data class ProviderAssignmentTombstone(
    val assignmentId: String,
    val assignmentRevision: Long,
    val revokedAt: String,
) {
    init {
        providerUuid(assignmentId)
        require(assignmentRevision > 0) { "Invalid provider assignment revision" }
        providerUtcTimestamp(revokedAt)
    }
}

@Serializable(with = ProviderAssignmentsResponseSerializer::class)
data class ProviderAssignmentsResponse(
    val serverRevision: Long,
    val assignments: List<ProviderAssignment>,
    val tombstones: List<ProviderAssignmentTombstone>,
) {
    init { require(serverRevision >= 0) { "Invalid provider server revision" } }
    override fun toString(): String = "ProviderAssignmentsResponse([REDACTED])"
}

@Serializable(with = ProviderAssignmentStatusRequestSerializer::class)
data class ProviderAssignmentStatusRequest(
    val profileRevision: Long,
    val state: ProviderAssignmentState,
    val safeErrorCode: ProviderAssignmentSafeErrorCode? = null,
    val providerReportedExpiresAt: String? = null,
    val providerReportedMaxConnections: Int? = null,
) {
    init {
        require(profileRevision > 0) { "Invalid provider profile revision" }
        providerReportedExpiresAt?.let(::providerUtcTimestamp)
        require(providerReportedMaxConnections == null || providerReportedMaxConnections >= 0) {
            "Invalid provider connection count"
        }
    }
    override fun toString(): String = "ProviderAssignmentStatusRequest([REDACTED])"
}

// Private wire records are never surfaced to callers or used in diagnostic messages.
private abstract class ProviderAssignmentWireRedacted {
    final override fun toString(): String = "ProviderAssignmentWire([REDACTED])"
}

@Serializable
private class XtreamWire(
    val serverUrl: String, val username: String, val password: String,
    val epgSyncMode: ProviderEpgSyncMode, val fastSyncEnabled: Boolean,
    val liveSyncMode: ProviderLiveSyncMode, val epgUrl: String? = null,
    val httpUserAgent: String? = null, val httpHeaders: Map<String, String>? = null,
) : ProviderAssignmentWireRedacted()

@Serializable
private class M3uWire(
    val serverUrl: String, val m3uUrl: String, val epgSyncMode: ProviderEpgSyncMode,
    val vodClassificationEnabled: Boolean, val epgUrl: String? = null,
    val httpUserAgent: String? = null, val httpHeaders: Map<String, String>? = null,
) : ProviderAssignmentWireRedacted()

@Serializable
private class StalkerWire(
    val serverUrl: String, val portalUrl: String, val stalkerMacAddress: String,
    val deviceProfile: String? = null, val timezone: String? = null, val locale: String? = null,
    val epgUrl: String? = null, val httpUserAgent: String? = null,
    val httpHeaders: Map<String, String>? = null,
) : ProviderAssignmentWireRedacted()

@Serializable
private class AssignmentWire(
    val assignmentId: String, val profileId: String, val assignmentRevision: Long, val profileRevision: Long,
    val policy: ProviderAssignmentPolicy, val type: ProviderAssignmentType,
    val displayName: String, val configuration: JsonObject,
) : ProviderAssignmentWireRedacted()

@Serializable
private class TombstoneWire(val assignmentId: String, val assignmentRevision: Long, val revokedAt: String) : ProviderAssignmentWireRedacted()

@Serializable
private class AssignmentsWire(
    val serverRevision: Long, val assignments: List<ProviderAssignment>,
    val tombstones: List<ProviderAssignmentTombstone>,
) : ProviderAssignmentWireRedacted()

@Serializable
private class AssignmentStatusWire(
    val profileRevision: Long, val state: ProviderAssignmentState,
    val safeErrorCode: ProviderAssignmentSafeErrorCode? = null,
    val providerReportedExpiresAt: String? = null,
    val providerReportedMaxConnections: Int? = null,
) : ProviderAssignmentWireRedacted()

/** JSON-only adapter; deliberately discards caller coercion and unknown-field settings. */
abstract class ProviderAssignmentJsonSerializer<T>(name: String) : KSerializer<T> {
    final override val descriptor = buildClassSerialDescriptor(name)
    protected abstract fun fromJson(element: JsonElement): T
    protected abstract fun toJson(value: T): JsonElement

    final override fun deserialize(decoder: Decoder): T {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("Provider transport requires JSON")
        return try {
            fromJson(jsonDecoder.decodeJsonElement())
        } catch (_: IllegalArgumentException) {
            throw SerializationException("Invalid provider transport payload")
        }
    }

    final override fun serialize(encoder: Encoder, value: T) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("Provider transport requires JSON")
        jsonEncoder.encodeJsonElement(toJson(value))
    }
}

object XtreamProviderConfigurationSerializer : ProviderAssignmentJsonSerializer<XtreamProviderConfiguration>("XtreamProviderConfiguration") {
    override fun fromJson(element: JsonElement): XtreamProviderConfiguration {
        val wire = ProviderAssignmentJson.decodeFromJsonElement(XtreamWire.serializer(), element)
        return XtreamProviderConfiguration(wire.serverUrl, wire.username, wire.password,
            wire.epgSyncMode, wire.fastSyncEnabled, wire.liveSyncMode, wire.epgUrl,
            wire.httpUserAgent, wire.httpHeaders)
    }
    override fun toJson(value: XtreamProviderConfiguration): JsonElement = ProviderAssignmentJson.encodeToJsonElement(
        XtreamWire.serializer(), XtreamWire(value.serverUrl, value.username, value.password,
            value.epgSyncMode, value.fastSyncEnabled, value.liveSyncMode, value.epgUrl,
            value.httpUserAgent, value.httpHeaders))
}

object M3uProviderConfigurationSerializer : ProviderAssignmentJsonSerializer<M3uProviderConfiguration>("M3uProviderConfiguration") {
    override fun fromJson(element: JsonElement): M3uProviderConfiguration {
        val wire = ProviderAssignmentJson.decodeFromJsonElement(M3uWire.serializer(), element)
        return M3uProviderConfiguration(wire.serverUrl, wire.m3uUrl, wire.epgSyncMode,
            wire.vodClassificationEnabled, wire.epgUrl, wire.httpUserAgent, wire.httpHeaders)
    }
    override fun toJson(value: M3uProviderConfiguration): JsonElement = ProviderAssignmentJson.encodeToJsonElement(
        M3uWire.serializer(), M3uWire(value.serverUrl, value.m3uUrl, value.epgSyncMode,
            value.vodClassificationEnabled, value.epgUrl, value.httpUserAgent, value.httpHeaders))
}

object StalkerProviderConfigurationSerializer : ProviderAssignmentJsonSerializer<StalkerProviderConfiguration>("StalkerProviderConfiguration") {
    override fun fromJson(element: JsonElement): StalkerProviderConfiguration {
        val wire = ProviderAssignmentJson.decodeFromJsonElement(StalkerWire.serializer(), element)
        return StalkerProviderConfiguration(wire.serverUrl, wire.portalUrl, wire.stalkerMacAddress,
            wire.deviceProfile, wire.timezone, wire.locale, wire.epgUrl, wire.httpUserAgent, wire.httpHeaders)
    }
    override fun toJson(value: StalkerProviderConfiguration): JsonElement = ProviderAssignmentJson.encodeToJsonElement(
        StalkerWire.serializer(), StalkerWire(value.serverUrl, value.portalUrl, value.stalkerMacAddress,
            value.deviceProfile, value.timezone, value.locale, value.epgUrl, value.httpUserAgent, value.httpHeaders))
}

object ProviderAssignmentSerializer : ProviderAssignmentJsonSerializer<ProviderAssignment>("ProviderAssignment") {
    override fun fromJson(element: JsonElement): ProviderAssignment {
        val wire = ProviderAssignmentJson.decodeFromJsonElement(AssignmentWire.serializer(), element)
        val configuration = when (wire.type) {
            ProviderAssignmentType.XTREAM_CODES -> ProviderAssignmentJson.decodeFromJsonElement(XtreamProviderConfiguration.serializer(), wire.configuration)
            ProviderAssignmentType.M3U -> ProviderAssignmentJson.decodeFromJsonElement(M3uProviderConfiguration.serializer(), wire.configuration)
            ProviderAssignmentType.STALKER_PORTAL -> ProviderAssignmentJson.decodeFromJsonElement(StalkerProviderConfiguration.serializer(), wire.configuration)
        }
        return ProviderAssignment(wire.assignmentId, wire.profileId, wire.assignmentRevision, wire.profileRevision, wire.policy, wire.displayName, configuration)
    }
    override fun toJson(value: ProviderAssignment): JsonElement {
        val configuration = when (val config = value.configuration) {
            is XtreamProviderConfiguration -> ProviderAssignmentJson.encodeToJsonElement(XtreamProviderConfiguration.serializer(), config)
            is M3uProviderConfiguration -> ProviderAssignmentJson.encodeToJsonElement(M3uProviderConfiguration.serializer(), config)
            is StalkerProviderConfiguration -> ProviderAssignmentJson.encodeToJsonElement(StalkerProviderConfiguration.serializer(), config)
        } as JsonObject
        return ProviderAssignmentJson.encodeToJsonElement(AssignmentWire.serializer(), AssignmentWire(
            value.assignmentId, value.profileId, value.assignmentRevision, value.profileRevision, value.policy, value.type, value.displayName, configuration))
    }
}

object ProviderAssignmentTombstoneSerializer : ProviderAssignmentJsonSerializer<ProviderAssignmentTombstone>("ProviderAssignmentTombstone") {
    override fun fromJson(element: JsonElement): ProviderAssignmentTombstone {
        val wire = ProviderAssignmentJson.decodeFromJsonElement(TombstoneWire.serializer(), element)
        return ProviderAssignmentTombstone(wire.assignmentId, wire.assignmentRevision, wire.revokedAt)
    }
    override fun toJson(value: ProviderAssignmentTombstone): JsonElement = ProviderAssignmentJson.encodeToJsonElement(
        TombstoneWire.serializer(), TombstoneWire(value.assignmentId, value.assignmentRevision, value.revokedAt))
}

object ProviderAssignmentsResponseSerializer : ProviderAssignmentJsonSerializer<ProviderAssignmentsResponse>("ProviderAssignmentsResponse") {
    override fun fromJson(element: JsonElement): ProviderAssignmentsResponse {
        val wire = ProviderAssignmentJson.decodeFromJsonElement(AssignmentsWire.serializer(), element)
        return ProviderAssignmentsResponse(wire.serverRevision, wire.assignments, wire.tombstones)
    }
    override fun toJson(value: ProviderAssignmentsResponse): JsonElement = ProviderAssignmentJson.encodeToJsonElement(
        AssignmentsWire.serializer(), AssignmentsWire(value.serverRevision, value.assignments, value.tombstones))
}

object ProviderAssignmentStatusRequestSerializer : ProviderAssignmentJsonSerializer<ProviderAssignmentStatusRequest>("ProviderAssignmentStatusRequest") {
    override fun fromJson(element: JsonElement): ProviderAssignmentStatusRequest {
        val wire = ProviderAssignmentJson.decodeFromJsonElement(AssignmentStatusWire.serializer(), element)
        return ProviderAssignmentStatusRequest(wire.profileRevision, wire.state, wire.safeErrorCode,
            wire.providerReportedExpiresAt, wire.providerReportedMaxConnections)
    }
    override fun toJson(value: ProviderAssignmentStatusRequest): JsonElement = ProviderAssignmentJson.encodeToJsonElement(
        AssignmentStatusWire.serializer(), AssignmentStatusWire(value.profileRevision, value.state, value.safeErrorCode,
            value.providerReportedExpiresAt, value.providerReportedMaxConnections))
}

private fun providerUuid(value: String) {
    require(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(value)) {
        "Invalid provider UUID"
    }
}

private fun providerUrl(value: String) {
    val uri = try { URI(value) } catch (_: URISyntaxException) {
        throw IllegalArgumentException("Invalid provider URL")
    }
    require((uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
        !uri.host.isNullOrBlank()) { "Invalid provider URL" }
}

private fun providerUtcTimestamp(value: String) {
    require(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?Z").matches(value)) {
        "Invalid provider UTC timestamp"
    }
    val parsed = try { OffsetDateTime.parse(value) } catch (_: DateTimeParseException) {
        throw IllegalArgumentException("Invalid provider UTC timestamp")
    }
    require(parsed.offset == ZoneOffset.UTC) { "Invalid provider UTC timestamp" }
}

private fun providerConfiguration(serverUrl: String, epgUrl: String?, headers: Map<String, String>?) {
    providerUrl(serverUrl)
    epgUrl?.let(::providerUrl)
    require(headers == null || headers.size <= 20) { "Invalid provider headers" }
    headers?.forEach { (name, value) ->
        require(name.length in 1..64 && Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+").matches(name)) {
            "Invalid provider header name"
        }
        require(value.length <= 512 && value.none { it == '\r' || it == '\n' || it == '\u0000' }) {
            "Invalid provider header value"
        }
    }
}
