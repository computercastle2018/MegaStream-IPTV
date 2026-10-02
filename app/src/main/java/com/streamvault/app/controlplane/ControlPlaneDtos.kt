package com.MegaStream.app.controlplane

import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Transport-only v1 schema. Constructors validate incoming decoded values as well as local values.
 * Diagnostic producers must map application data to safe symbols/closed identifiers: never pass
 * Throwable messages, raw logs, playlist entries, provider credentials or URLs. Length/allowlist
 * checks are defense in depth, not a general-purpose secret detector or sanitizer.
 * Opaque leases require independent signature, claim, and trust-root verification before use.
 */
@Serializable
data class RegistrationRequest(
    val installationId: String,
    val credential: String,
    val appVersionCode: Long,
    val appVersionName: String,
    val manufacturer: String,
    val model: String,
    val androidApi: Int,
    val androidRelease: String,
    val abi: DeviceAbi,
    val locale: String,
    val managedDevice: Boolean,
    val packageName: String,
    val channel: ReleaseChannel,
) {
    init {
        uuid(installationId); credential(credential); nonnegative(appVersionCode)
        text(appVersionName, 64); text(manufacturer, 120); text(model, 120)
        require(androidApi in 27..1000) { "Invalid Android API" }
        text(androidRelease, 64); text(locale, 64); javaClass(packageName, 240)
    }
    override fun toString() = "RegistrationRequest(installationId=$installationId, credential=[REDACTED])"
}

@Serializable
data class RegistrationResponse(val installationId: String, val registeredAt: String) {
    init { uuid(installationId); utc(registeredAt) }
}

@Serializable
data class ActivateRequest(val licenseKey: String) {
    init { text(licenseKey, 512) }
    override fun toString() = "ActivateRequest(licenseKey=[REDACTED])"
}

@Serializable
data class ActivationCodePollRequest(val code: String, val pollToken: String) {
    init { text(code, 64); text(pollToken, 512) }
    override fun toString() = "ActivationCodePollRequest(code=[REDACTED], pollToken=[REDACTED])"
}

@Serializable
data class ActivationCodeResponse(
    val code: String,
    val expiresAt: String,
    val status: ActivationCodeStatus,
    val pollToken: String,
) {
    init { text(code, 64); utc(expiresAt); text(pollToken, 512) }
    override fun toString() = "ActivationCodeResponse(code=[REDACTED], expiresAt=$expiresAt, status=$status, pollToken=[REDACTED])"
}

@Serializable
data class EntitlementDecision(
    val state: EntitlementState,
    val licenseId: String? = null,
    val licenseRevision: Long? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val offlineUntil: String? = null,
) {
    init {
        licenseId?.let { uuid(it) }; licenseRevision?.let { require(it > 0) { "Invalid license revision" } }
        startsAt?.let { utc(it) }; endsAt?.let { utc(it) }; offlineUntil?.let { utc(it) }
        if (state in LICENSE_STATES) {
            require(licenseId != null && licenseRevision != null && startsAt != null && endsAt != null) { "License fields required" }
        }
        require((state == EntitlementState.ALLOWED) == (offlineUntil != null)) { "Offline allowance must match allowed state" }
    }
}

@Serializable
data class EntitlementResponse(
    val decision: EntitlementDecision,
    val serverTime: String,
    val refreshAfterSeconds: Long,
    val lease: String? = null,
    val updateCommand: UpdateCommand? = null,
    val devicePolicy: DevicePolicy? = null,
) {
    init {
        utc(serverTime); nonnegative(refreshAfterSeconds)
        lease?.let { text(it, 16_384) }
        require((decision.state == EntitlementState.ALLOWED) == (lease != null)) { "Lease must match allowed state" }
    }
    override fun toString() = "EntitlementResponse(decision=$decision, serverTime=$serverTime, refreshAfterSeconds=$refreshAfterSeconds, lease=[REDACTED], updateCommand=$updateCommand)"
}

@Serializable
data class DevicePolicy(val kioskMode: KioskMode, val allowLocalExit: Boolean)

@Serializable
enum class KioskMode {
    @SerialName("off") OFF,
    @SerialName("playback") PLAYBACK,
    @SerialName("always") ALWAYS,
}

@Serializable
data class ActivationPendingResponse(
    val status: ActivationCodeStatus,
    val serverTime: String,
    val retryAfterSeconds: Long,
) {
    init { utc(serverTime); nonnegative(retryAfterSeconds) }
}

/** JSON is the contained response object, without a wrapper or polymorphic discriminator. */
@Serializable(with = ActivationStatusResponseSerializer::class)
sealed interface ActivationStatusResponse {
    data class Pending(val response: ActivationPendingResponse) : ActivationStatusResponse
    data class Activated(val response: EntitlementResponse) : ActivationStatusResponse
}

internal object ActivationStatusResponseSerializer : KSerializer<ActivationStatusResponse> {
    override val descriptor = buildClassSerialDescriptor("ActivationStatusResponse")

    override fun serialize(encoder: Encoder, value: ActivationStatusResponse) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("JSON required")
        val element = when (value) {
            is ActivationStatusResponse.Pending -> output.json.encodeToJsonElement(value.response)
            is ActivationStatusResponse.Activated -> output.json.encodeToJsonElement(value.response)
        }
        output.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): ActivationStatusResponse {
        val input = decoder as? JsonDecoder ?: throw SerializationException("JSON required")
        val value = input.decodeJsonElement() as? JsonObject ?: throw SerializationException("Expected response object")
        val hasStatus = "status" in value
        val hasDecision = "decision" in value
        if (hasStatus == hasDecision) throw SerializationException("Expected exactly one response discriminator")
        val json = strict(input.json)
        return if (hasStatus) ActivationStatusResponse.Pending(json.decodeFromJsonElement(value))
        else ActivationStatusResponse.Activated(json.decodeFromJsonElement(value))
    }
}

@Serializable
data class HeartbeatRequest(
    val appSessionId: String,
    val sequence: Long,
    val mode: HeartbeatMode,
    val appVersionCode: Long,
    val appVersionName: String,
    val managedDevice: Boolean,
    val memory: MemorySnapshot? = null,
    val recoveredExit: RecoveredExit? = null,
    val packageName: String,
    val channel: ReleaseChannel,
) {
    init {
        uuid(appSessionId); nonnegative(sequence); nonnegative(appVersionCode); text(appVersionName, 64)
        javaClass(packageName, 240)
    }
}

@Serializable
data class MemorySnapshot(
    val javaUsedBytes: Long,
    val javaMaxBytes: Long,
    val nativeHeapBytes: Long,
    val pssBytes: Long,
    val availableSystemBytes: Long,
    val lowMemory: Boolean,
) {
    init {
        nonnegative(javaUsedBytes); nonnegative(javaMaxBytes); nonnegative(nativeHeapBytes)
        nonnegative(pssBytes); nonnegative(availableSystemBytes)
        val maximumMemoryBytes = 1L shl 40
        require(javaUsedBytes <= javaMaxBytes) { "Java heap usage exceeds maximum" }
        require(listOf(javaUsedBytes, javaMaxBytes, nativeHeapBytes, pssBytes, availableSystemBytes).all { it <= maximumMemoryBytes }) {
            "Memory value exceeds protocol maximum"
        }
    }
}

@Serializable
data class RecoveredExit(val reason: ExitReason, val evidence: ExitEvidence, val occurredAt: String? = null) {
    init { occurredAt?.let { utc(it) } }
}

@Serializable
data class UpdateCommand(
    val commandId: String,
    val releaseId: String,
    val versionCode: Long,
    val versionName: String,
    val mandatory: Boolean,
    val installMode: InstallMode,
    val downloadUrl: String,
    val sha256: String,
    val signingCertificateSha256: String? = null,
    val sizeBytes: Long,
    val notes: String? = null,
) {
    init {
        uuid(commandId); text(releaseId, 128); nonnegative(versionCode); text(versionName, 64)
        downloadUrl(downloadUrl); digest(sha256); signingCertificateSha256?.let { digest(it) }
        nonnegative(sizeBytes); notes?.let { text(it, 4096, allowEmpty = true, allowNewlines = true) }
    }
    override fun toString() = "UpdateCommand(commandId=$commandId, releaseId=$releaseId, versionCode=$versionCode, downloadUrl=[REDACTED], notes=[REDACTED])"
}

@Serializable
data class UpdateCommandStatusRequest(
    val status: UpdateCommandStatus,
    val errorCode: String? = null,
    val observedVersionCode: Long? = null,
) {
    init { errorCode?.let { identifier(it) }; observedVersionCode?.let { nonnegative(it) } }
}

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
data class DiagnosticsBatchRequest(
    @kotlinx.serialization.EncodeDefault val schemaVersion: Int = 1,
    val events: List<DiagnosticEvent>,
) {
    init {
        require(schemaVersion == 1) { "Unsupported diagnostics schema" }
        require(events.size in 1..50) { "Expected 1 to 50 events" }
    }
}

@Serializable(with = DiagnosticEventSerializer::class)
data class DiagnosticEvent(
    val eventId: String,
    val appSessionId: String,
    val sequence: Long,
    val occurredAt: String,
    val payload: DiagnosticPayload,
) {
    init { uuid(eventId); uuid(appSessionId); nonnegative(sequence); utc(occurredAt) }
    val kind: DiagnosticKind get() = when (payload) {
        is AppStartedPayload -> DiagnosticKind.APP_STARTED
        is PlaybackStartedPayload -> DiagnosticKind.PLAYBACK_STARTED
        is PlaybackSamplePayload -> DiagnosticKind.PLAYBACK_SAMPLE
        is PlaybackProblemPayload -> DiagnosticKind.PLAYBACK_PROBLEM
        is MemoryPressurePayload -> DiagnosticKind.MEMORY_PRESSURE
        is CrashPayload -> DiagnosticKind.CRASH
        is AnrPayload -> DiagnosticKind.ANR
        is PlaybackEndedPayload -> DiagnosticKind.PLAYBACK_ENDED
        is AppEndedPayload -> DiagnosticKind.APP_ENDED
    }
}

@Serializable
sealed interface DiagnosticPayload

@Serializable
data class AppStartedPayload(val appVersionCode: Long, val appVersionName: String) : DiagnosticPayload {
    init { nonnegative(appVersionCode); text(appVersionName, 64) }
}

@Serializable
data class PlaybackStartedPayload(
    val playbackSessionId: String,
    val channelName: String? = null,
    val sourceType: SourceType,
    val streamType: StreamType,
    val playbackMode: PlaybackMode,
) : DiagnosticPayload {
    init { uuid(playbackSessionId); channelName?.let { channel(it) } }
}

@Serializable
data class PlaybackSamplePayload(
    val playbackSessionId: String,
    val videoCodec: VideoCodec,
    val audioCodec: AudioCodec,
    val videoDecoder: VideoDecoder,
    val audioDecoder: AudioDecoder,
    val width: Int,
    val height: Int,
    val droppedFrames: Long,
    val rebufferCount: Long,
    val bufferedMs: Long,
    val ttffMs: Long,
    val memory: MemorySnapshot? = null,
) : DiagnosticPayload {
    init {
        uuid(playbackSessionId)
        require(width in 0..65_535 && height in 0..65_535) { "Invalid video dimensions" }
        nonnegative(droppedFrames); nonnegative(rebufferCount); nonnegative(bufferedMs); nonnegative(ttffMs)
    }
}

@Serializable
data class PlaybackProblemPayload(
    val playbackSessionId: String,
    val category: PlaybackProblemCategory,
    val code: String,
    val httpStatus: Int? = null,
    val retryAttempt: Long,
    val memory: MemorySnapshot? = null,
) : DiagnosticPayload {
    init {
        uuid(playbackSessionId); identifier(code)
        require(httpStatus == null || httpStatus in 100..599) { "Invalid HTTP status" }
        require(retryAttempt >= 0) { "Invalid retry attempt" }
    }
}

@Serializable
data class MemoryPressurePayload(val memory: MemorySnapshot, val trimLevel: TrimLevel) : DiagnosticPayload

@Serializable
data class CrashPayload(val exceptionType: String, val frames: List<DiagnosticFrame>) : DiagnosticPayload {
    init { javaClass(exceptionType, 120); frameCount(frames) }
}

@Serializable
data class DiagnosticFrame(val className: String, val methodName: String, val line: Int? = null) {
    init {
        javaClass(className, 160); text(methodName, 120)
        require(JAVA_MEMBER.matches(methodName) || methodName == "<init>" || methodName == "<clinit>") { "Invalid method symbol" }
        require(line == null || line >= 0) { "Invalid source line" }
    }
}

@Serializable
data class AnrPayload(
    val evidence: AnrEvidence,
    val durationMs: Long? = null,
    val frames: List<DiagnosticFrame>,
) : DiagnosticPayload {
    init { durationMs?.let { nonnegative(it) }; frameCount(frames) }
}

@Serializable
data class PlaybackEndedPayload(
    val playbackSessionId: String,
    val reason: PlaybackEndReason,
    val durationMs: Long,
) : DiagnosticPayload {
    init { uuid(playbackSessionId); nonnegative(durationMs) }
}

@Serializable
data class AppEndedPayload(val reason: ExitReason, val evidence: ExitEvidence) : DiagnosticPayload

@Serializable
data class DiagnosticsBatchResponse(
    val acceptedEventIds: List<String>,
    val duplicateEventIds: List<String>,
    val rejected: List<RejectedDiagnosticEvent>,
    val serverTime: String,
) {
    init {
        require(acceptedEventIds.size <= 50 && duplicateEventIds.size <= 50 && rejected.size <= 50) { "Too many diagnostic results" }
        acceptedEventIds.forEach { uuid(it) }; duplicateEventIds.forEach { uuid(it) }; utc(serverTime)
    }
}

@Serializable
data class RejectedDiagnosticEvent(val eventId: String, val code: DiagnosticRejectionCode) {
    init { uuid(eventId) }
}

/** Unknown server rejection text is deliberately discarded rather than logged or retained. */
@Serializable(with = DiagnosticRejectionCodeSerializer::class)
enum class DiagnosticRejectionCode(val wireValue: String) {
    @SerialName("invalid_kind") INVALID_KIND("invalid_kind"),
    @SerialName("invalid_payload") INVALID_PAYLOAD("invalid_payload"),
    @SerialName("invalid_time") INVALID_TIME("invalid_time"),
    @SerialName("invalid_sequence") INVALID_SEQUENCE("invalid_sequence"),
    @SerialName("invalid_session") INVALID_SESSION("invalid_session"),
    @SerialName("duplicate_conflict") DUPLICATE_CONFLICT("duplicate_conflict"),
    @SerialName("unsafe_content") UNSAFE_CONTENT("unsafe_content"),
    @SerialName("payload_too_large") PAYLOAD_TOO_LARGE("payload_too_large"),
    @SerialName("unsupported_schema") UNSUPPORTED_SCHEMA("unsupported_schema"),
    @SerialName("unknown") UNKNOWN("unknown"),
}

internal object DiagnosticRejectionCodeSerializer : KSerializer<DiagnosticRejectionCode> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
        "DiagnosticRejectionCode", kotlinx.serialization.descriptors.PrimitiveKind.STRING,
    )
    override fun serialize(encoder: Encoder, value: DiagnosticRejectionCode) = encoder.encodeString(value.wireValue)
    override fun deserialize(decoder: Decoder): DiagnosticRejectionCode {
        val input = decoder as? JsonDecoder ?: throw SerializationException("JSON required")
        val value = input.decodeJsonElement() as? kotlinx.serialization.json.JsonPrimitive
            ?: throw SerializationException("Expected rejection code string")
        if (!value.isString) throw SerializationException("Expected rejection code string")
        return DiagnosticRejectionCode.entries.firstOrNull { it.wireValue == value.content }
            ?: DiagnosticRejectionCode.UNKNOWN
    }
}

@Serializable
private data class DiagnosticEventWire(
    val eventId: String,
    val appSessionId: String,
    val sequence: Long,
    val occurredAt: String,
    val kind: DiagnosticKind,
    val payload: JsonObject,
)

internal object DiagnosticEventSerializer : KSerializer<DiagnosticEvent> {
    override val descriptor = DiagnosticEventWire.serializer().descriptor

    override fun serialize(encoder: Encoder, value: DiagnosticEvent) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("JSON required")
        val json = output.json
        val payload = when (val p = value.payload) {
            is AppStartedPayload -> json.encodeToJsonElement(p)
            is PlaybackStartedPayload -> json.encodeToJsonElement(p)
            is PlaybackSamplePayload -> json.encodeToJsonElement(p)
            is PlaybackProblemPayload -> json.encodeToJsonElement(p)
            is MemoryPressurePayload -> json.encodeToJsonElement(p)
            is CrashPayload -> json.encodeToJsonElement(p)
            is AnrPayload -> json.encodeToJsonElement(p)
            is PlaybackEndedPayload -> json.encodeToJsonElement(p)
            is AppEndedPayload -> json.encodeToJsonElement(p)
        } as JsonObject
        output.encodeJsonElement(json.encodeToJsonElement(DiagnosticEventWire(
            value.eventId, value.appSessionId, value.sequence, value.occurredAt, value.kind, payload,
        )))
    }

    override fun deserialize(decoder: Decoder): DiagnosticEvent {
        val input = decoder as? JsonDecoder ?: throw SerializationException("JSON required")
        val json = strict(input.json)
        val wire = json.decodeFromJsonElement<DiagnosticEventWire>(input.decodeJsonElement())
        val payload: DiagnosticPayload = when (wire.kind) {
            DiagnosticKind.APP_STARTED -> json.decodeFromJsonElement<AppStartedPayload>(wire.payload)
            DiagnosticKind.PLAYBACK_STARTED -> json.decodeFromJsonElement<PlaybackStartedPayload>(wire.payload)
            DiagnosticKind.PLAYBACK_SAMPLE -> json.decodeFromJsonElement<PlaybackSamplePayload>(wire.payload)
            DiagnosticKind.PLAYBACK_PROBLEM -> json.decodeFromJsonElement<PlaybackProblemPayload>(wire.payload)
            DiagnosticKind.MEMORY_PRESSURE -> json.decodeFromJsonElement<MemoryPressurePayload>(wire.payload)
            DiagnosticKind.CRASH -> json.decodeFromJsonElement<CrashPayload>(wire.payload)
            DiagnosticKind.ANR -> json.decodeFromJsonElement<AnrPayload>(wire.payload)
            DiagnosticKind.PLAYBACK_ENDED -> json.decodeFromJsonElement<PlaybackEndedPayload>(wire.payload)
            DiagnosticKind.APP_ENDED -> json.decodeFromJsonElement<AppEndedPayload>(wire.payload)
        }
        return DiagnosticEvent(wire.eventId, wire.appSessionId, wire.sequence, wire.occurredAt, payload)
    }
}

@Serializable
enum class ReleaseChannel { @SerialName("stable") STABLE, @SerialName("beta") BETA }

@Serializable
enum class DeviceAbi {
    @SerialName("arm64_v8a") ARM64_V8A, @SerialName("armeabi_v7a") ARMEABI_V7A,
    @SerialName("x86_64") X86_64, @SerialName("x86") X86, @SerialName("other") OTHER,
}
@Serializable
enum class ActivationCodeStatus { @SerialName("pending") PENDING }
@Serializable
enum class EntitlementState {
    @SerialName("unlicensed") UNLICENSED,
    @SerialName("allowed") ALLOWED, @SerialName("not_started") NOT_STARTED,
    @SerialName("expired") EXPIRED, @SerialName("suspended") SUSPENDED,
    @SerialName("revoked") REVOKED, @SerialName("installation_disabled") INSTALLATION_DISABLED,
    @SerialName("verification_required") VERIFICATION_REQUIRED,
}
@Serializable
enum class HeartbeatMode {
    @SerialName("foreground") FOREGROUND, @SerialName("background") BACKGROUND, @SerialName("playback") PLAYBACK,
}
@Serializable
enum class ExitReason {
    @SerialName("clean_exit") CLEAN_EXIT, @SerialName("os_exit") OS_EXIT,
    @SerialName("java_crash") JAVA_CRASH, @SerialName("anr") ANR, @SerialName("oom") OOM,
    @SerialName("low_memory_kill") LOW_MEMORY_KILL, @SerialName("native_crash") NATIVE_CRASH,
    @SerialName("signal") SIGNAL, @SerialName("user_requested") USER_REQUESTED,
    @SerialName("system_kill") SYSTEM_KILL, @SerialName("unknown") UNKNOWN,
}
@Serializable
enum class ExitEvidence {
    @SerialName("reported") REPORTED, @SerialName("recovered_os") RECOVERED_OS,
    @SerialName("recovered_marker") RECOVERED_MARKER, @SerialName("inferred") INFERRED,
    @SerialName("watchdog_suspected") WATCHDOG_SUSPECTED,
}
@Serializable
enum class InstallMode { @SerialName("prompt") PROMPT, @SerialName("managed") MANAGED }
@Serializable
enum class UpdateCommandStatus {
    @SerialName("pending") PENDING, @SerialName("acknowledged") ACKNOWLEDGED,
    @SerialName("downloading") DOWNLOADING, @SerialName("downloaded") DOWNLOADED,
    @SerialName("install_prompted") INSTALL_PROMPTED, @SerialName("installed") INSTALLED, @SerialName("failed") FAILED,
}
@Serializable
enum class DiagnosticKind {
    @SerialName("app_started") APP_STARTED, @SerialName("playback_started") PLAYBACK_STARTED,
    @SerialName("playback_sample") PLAYBACK_SAMPLE, @SerialName("playback_problem") PLAYBACK_PROBLEM,
    @SerialName("memory_pressure") MEMORY_PRESSURE, @SerialName("crash") CRASH,
    @SerialName("anr") ANR, @SerialName("playback_ended") PLAYBACK_ENDED, @SerialName("app_ended") APP_ENDED,
}
@Serializable
enum class SourceType {
    @SerialName("xtream_codes") XTREAM_CODES, @SerialName("m3u") M3U,
    @SerialName("stalker_portal") STALKER_PORTAL, @SerialName("local") LOCAL, @SerialName("unknown") UNKNOWN,
}
@Serializable
enum class StreamType {
    @SerialName("hls") HLS, @SerialName("dash") DASH, @SerialName("mpeg_ts") MPEG_TS,
    @SerialName("progressive") PROGRESSIVE, @SerialName("rtsp") RTSP,
    @SerialName("smooth_streaming") SMOOTH_STREAMING, @SerialName("unknown") UNKNOWN,
}
@Serializable
enum class PlaybackMode {
    @SerialName("live") LIVE, @SerialName("vod") VOD, @SerialName("catch_up") CATCH_UP,
    @SerialName("preview") PREVIEW, @SerialName("multiview") MULTIVIEW,
    @SerialName("tv_input") TV_INPUT, @SerialName("recording") RECORDING,
}
@Serializable
enum class VideoCodec {
    @SerialName("unknown") UNKNOWN, @SerialName("h264") H264, @SerialName("hevc") HEVC,
    @SerialName("av1") AV1, @SerialName("vp9") VP9, @SerialName("mpeg2") MPEG2, @SerialName("other") OTHER,
}
@Serializable
enum class AudioCodec {
    @SerialName("unknown") UNKNOWN, @SerialName("aac") AAC, @SerialName("ac3") AC3,
    @SerialName("eac3") EAC3, @SerialName("dts") DTS, @SerialName("mp3") MP3,
    @SerialName("opus") OPUS, @SerialName("other") OTHER,
}
@Serializable
enum class VideoDecoder {
    @SerialName("hardware") HARDWARE, @SerialName("software") SOFTWARE, @SerialName("unknown") UNKNOWN,
}
@Serializable
enum class AudioDecoder {
    @SerialName("platform") PLATFORM, @SerialName("ffmpeg") FFMPEG, @SerialName("unknown") UNKNOWN,
}
@Serializable
enum class PlaybackProblemCategory {
    @SerialName("network") NETWORK, @SerialName("http") HTTP, @SerialName("decoder") DECODER,
    @SerialName("drm") DRM, @SerialName("source") SOURCE, @SerialName("timeout") TIMEOUT,
    @SerialName("stall") STALL, @SerialName("unknown") UNKNOWN,
}
@Serializable
enum class TrimLevel {
    @SerialName("running_moderate") RUNNING_MODERATE, @SerialName("running_low") RUNNING_LOW,
    @SerialName("running_critical") RUNNING_CRITICAL, @SerialName("background") BACKGROUND,
    @SerialName("moderate") MODERATE, @SerialName("complete") COMPLETE,
    @SerialName("low_memory") LOW_MEMORY, @SerialName("unknown") UNKNOWN,
}
@Serializable
enum class AnrEvidence {
    @SerialName("os_exit_reason") OS_EXIT_REASON, @SerialName("watchdog_suspected") WATCHDOG_SUSPECTED,
}
@Serializable
enum class PlaybackEndReason {
    @SerialName("user_stop") USER_STOP, @SerialName("channel_changed") CHANNEL_CHANGED,
    @SerialName("completed") COMPLETED, @SerialName("error") ERROR,
    @SerialName("license_blocked") LICENSE_BLOCKED, @SerialName("background") BACKGROUND,
    @SerialName("sleep_timer") SLEEP_TIMER, @SerialName("unknown") UNKNOWN,
}

private val LICENSE_STATES = setOf(
    EntitlementState.ALLOWED, EntitlementState.NOT_STARTED, EntitlementState.EXPIRED,
    EntitlementState.SUSPENDED, EntitlementState.REVOKED,
)
private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private val UTC_PATTERN = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z")
private val JAVA_MEMBER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
private val JAVA_CLASS = Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
private val IDENTIFIER = Regex("[a-z][a-z0-9_]{0,63}")
private val SECRET_PATTERN = Regex("(?i)([a-z][a-z0-9+.-]*://|www\\.|bearer\\s|(?:token|password|passwd|username|authorization|cookie|secret|api[_-]?key)\\s*[:=]|[A-Za-z0-9_-]{32,})")

private fun uuid(value: String) { require(UUID_PATTERN.matches(value)) { "Invalid canonical UUID" } }
private fun credential(value: String) {
    require(InstallationCredentials.isValidCredential(value)) { "Invalid device credential" }
}
private fun nonnegative(value: Long) { require(value >= 0) { "Expected nonnegative number" } }
private fun text(value: String, max: Int, allowEmpty: Boolean = false, allowNewlines: Boolean = false) {
    require(value.length <= max && (allowEmpty || value.isNotBlank())) { "Invalid text length" }
    require(value.none { it.isISOControl() && !(allowNewlines && (it == '\n' || it == '\r' || it == '\t')) }) { "Invalid text characters" }
}
private fun utc(value: String) {
    require(UTC_PATTERN.matches(value)) { "Invalid UTC timestamp" }
    // Instant.parse normalizes leap seconds; explicitly forbid them for deterministic validation.
    require(value.substring(11, 13).toInt() <= 23 && value.substring(14, 16).toInt() <= 59 &&
        value.substring(17, 19).toInt() <= 59) { "Invalid UTC timestamp" }
    try { Instant.parse(value) } catch (_: DateTimeParseException) { throw IllegalArgumentException("Invalid UTC timestamp") }
}
private fun identifier(value: String) { require(IDENTIFIER.matches(value)) { "Invalid diagnostic identifier" } }
private fun javaClass(value: String, max: Int) {
    text(value, max); require(JAVA_CLASS.matches(value)) { "Invalid Java class symbol" }
}
private fun frameCount(frames: List<DiagnosticFrame>) { require(frames.size <= 32) { "Too many diagnostic frames" } }
private fun channel(value: String) {
    text(value, 120); require(!SECRET_PATTERN.containsMatchIn(value)) { "Unsafe channel name" }
}
private fun digest(value: String) { require(Regex("[0-9a-fA-F]{64}").matches(value)) { "Invalid SHA-256 digest" } }
private fun downloadUrl(value: String) {
    text(value, 2048)
    require(ControlPlaneUrlPolicy.isAllowed(value)) { "Invalid update origin" }
}
private fun strict(json: Json): Json = Json(json) { ignoreUnknownKeys = false; coerceInputValues = false }
