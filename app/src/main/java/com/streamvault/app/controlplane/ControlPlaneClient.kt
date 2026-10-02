package com.MegaStream.app.controlplane

import java.io.IOException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

internal fun interface CallExecutor {
    fun execute(request: Request): Response
}

/**
 * Blocking transport. Call every operation from an external IO dispatcher, never the UI thread.
 * The dedicated client intentionally shares no interceptors, cookies, authentication or cache.
 * Entitlement payloads are transport data, not proof of a verified offline lease.
 */
class ControlPlaneClient internal constructor(private val executor: CallExecutor) {
    constructor() : this(productionExecutor())

    fun register(request: RegistrationRequest, idempotencyKey: String): ControlPlaneResult<RegistrationResponse> = safely {
        requireUuid(idempotencyKey)
        require(InstallationCredentials.isValidCredential(request.credential))
        val result = perform(buildRequest("/api/v1/installations/register", null,
            encode(request, RegistrationRequest.serializer()), idempotencyKey), RegistrationResponse.serializer())
        if (result is ControlPlaneResult.Success && result.value.installationId != request.installationId) failure("invalid_response") else result
    }

    fun activate(credential: String, licenseKey: String): ControlPlaneResult<EntitlementResponse> = safely {
        post("/api/v1/licenses/activate", credential, encode(ActivateRequest(licenseKey), ActivateRequest.serializer()), EntitlementResponse.serializer())
    }

    fun requestActivationCode(credential: String): ControlPlaneResult<ActivationCodeResponse> = safely {
        post("/api/v1/activation-codes/request", credential, "{}", ActivationCodeResponse.serializer())
    }

    fun pollActivationCode(credential: String, code: String, pollToken: String): ControlPlaneResult<ActivationStatusResponse> = safely {
        post("/api/v1/activation-codes/status", credential,
            encode(ActivationCodePollRequest(code, pollToken), ActivationCodePollRequest.serializer()), ActivationStatusResponse.serializer())
    }

    fun heartbeat(credential: String, request: HeartbeatRequest): ControlPlaneResult<EntitlementResponse> = safely {
        val heartbeatResult = post("/api/v1/devices/heartbeat", credential, encode(request, HeartbeatRequest.serializer()), EntitlementResponse.serializer())
        if (heartbeatResult is ControlPlaneResult.Success && heartbeatResult.value.devicePolicy == null)
            failure("invalid_response") else heartbeatResult
    }

    fun diagnostics(credential: String, request: DiagnosticsBatchRequest): ControlPlaneResult<DiagnosticsBatchResponse> = safely {
        // Encode once and revalidate that exact immutable snapshot: callers may retain mutable lists.
        post("/api/v1/diagnostics/batch", credential, encode(request, DiagnosticsBatchRequest.serializer()), DiagnosticsBatchResponse.serializer())
    }

    fun reportUpdateCommandStatus(credential: String, commandId: String, request: UpdateCommandStatusRequest): ControlPlaneResult<Unit> = safely {
        requireUuid(commandId)
        postUnit("/api/v1/updates/commands/$commandId/status", credential, encode(request, UpdateCommandStatusRequest.serializer()))
    }

    fun getProviderAssignments(credential: String, afterRevision: Long): ControlPlaneResult<ProviderAssignmentsResponse> = safely {
        require(afterRevision >= 0)
        perform(buildRequest("/api/v1/providers/assignments?afterRevision=$afterRevision", credential, null), ProviderAssignmentsResponse.serializer())
    }

    fun reportProviderAssignmentStatus(credential: String, assignmentId: String, request: ProviderAssignmentStatusRequest): ControlPlaneResult<Unit> = safely {
        requireUuid(assignmentId)
        postUnit("/api/v1/providers/assignments/$assignmentId/status", credential, encode(request, ProviderAssignmentStatusRequest.serializer()))
    }

    fun reportLocalSubscriptions(credential: String, request: LocalSubscriptionsRequest): ControlPlaneResult<Unit> = safely {
        postUnit("/api/v1/devices/subscriptions", credential, encode(request, LocalSubscriptionsRequest.serializer()))
    }

    fun deviceExperience(credential: String): ControlPlaneResult<DeviceExperience> = safely {
        perform(buildRequest("/api/v1/devices/experience", credential, null), DeviceExperience.serializer())
    }

    fun reportDeviceMac(credential: String, request: DeviceMacReport): ControlPlaneResult<Unit> = safely {
        postUnit("/api/v1/devices/experience", credential, encode(request, DeviceMacReport.serializer()))
    }

    private fun <T> encode(value: T, serializer: KSerializer<T>): String {
        val encoded = json.encodeToString(serializer, value)
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES)
        json.decodeFromString(serializer, encoded)
        return encoded
    }

    private fun <T> post(path: String, credential: String, body: String, serializer: KSerializer<T>): ControlPlaneResult<T> =
        perform(buildRequest(path, credential, body), serializer)

    private fun postUnit(path: String, credential: String, body: String): ControlPlaneResult<Unit> =
        execute(buildRequest(path, credential, body)) { _, _ -> ControlPlaneResult.Success(Unit) }

    private fun buildRequest(path: String, credential: String?, body: String?, idempotencyKey: String? = null): Request {
        val rawUrl = ControlPlaneUrlPolicy.ORIGIN + path
        require(ControlPlaneUrlPolicy.isAllowed(rawUrl))
        require(allowedEndpoint(path.substringBefore('?'), if (body == null) "GET" else "POST"))
        val builder = Request.Builder().url(rawUrl).header("Accept", "application/json, application/problem+json")
            .header("Cache-Control", "no-store")
        if (credential != null) {
            require(InstallationCredentials.isValidCredential(credential))
            require(path != "/api/v1/installations/register")
            builder.header("Authorization", "Bearer $credential")
        } else require(path == "/api/v1/installations/register")
        idempotencyKey?.let { builder.header("Idempotency-Key", it) }
        if (body != null) builder.post(body.toRequestBody(JSON_MEDIA_TYPE)) else builder.get()
        return builder.build()
    }

    private fun <T> perform(request: Request, serializer: KSerializer<T>): ControlPlaneResult<T> = execute(request) { response, text ->
        val type = response.body?.contentType()
        if (type?.type != "application" || type.subtype != "json") failure("invalid_response")
        else try { ControlPlaneResult.Success(json.decodeFromString(serializer, text)) }
        catch (_: IllegalArgumentException) { failure("invalid_response") }
    }

    private fun <T> execute(request: Request, decode: (Response, String) -> ControlPlaneResult<T>): ControlPlaneResult<T> {
        if (!ControlPlaneUrlPolicy.isAllowed(request.url) || !allowedEndpoint(request.url.encodedPath, request.method)) return failure("invalid_request")
        return try {
            val response = executor.execute(request)
            response.body.use {
                if (response.code in 300..399 || response.priorResponse != null ||
                    !ControlPlaneUrlPolicy.isAllowed(response.request.url) || response.request.url != request.url) {
                    return failure("unsafe_response")
                }
                val text = readBoundedBody(response) ?: return failure("payload_too_large")
                if (!response.isSuccessful) problemFailure(request, response, text) else decode(response, text)
            }
        } catch (_: IOException) { failure("network_error") }
        catch (_: IllegalArgumentException) { failure("invalid_response") }
    }

    /** Null signals an oversized body; absent and empty bodies both yield an empty string. */
    private fun readBoundedBody(response: Response): String? {
        val body = response.body ?: return ""
        if (body.contentLength() > MAX_BODY_BYTES) return null
        val source = body.source()
        val buffer = Buffer()
        // Read at most limit+1 to distinguish exact-limit EOF from chunked overflow.
        while (buffer.size <= MAX_BODY_BYTES) {
            val count = source.read(buffer, minOf(8192L, MAX_BODY_BYTES + 1L - buffer.size))
            if (count == -1L) break
        }
        return if (buffer.size > MAX_BODY_BYTES) null else buffer.readUtf8()
    }

    private fun problemFailure(request: Request, response: Response, text: String): ControlPlaneResult.Failure {
        val type = response.body?.contentType()
        val error = if (type?.type == "application" && type.subtype == "problem+json")
            ControlPlaneError.fromProblem(text, response.code, sensitiveValues(request)) else ControlPlaneError.unknown(response.code)
        return ControlPlaneResult.Failure(error)
    }

    private fun sensitiveValues(request: Request): List<String> {
        val values = mutableListOf<String>()
        request.header("Authorization")?.removePrefix("Bearer ")?.let(values::add)
        request.body?.let { body ->
            val buffer = Buffer()
            body.writeTo(buffer)
            val fields = json.parseToJsonElement(buffer.readUtf8()) as? JsonObject
            fields?.values?.filterIsInstance<JsonPrimitive>()?.filter { it.isString }?.forEach { values.add(it.content) }
        }
        return values
    }

    private inline fun <T> safely(block: () -> ControlPlaneResult<T>): ControlPlaneResult<T> =
        try { block() } catch (_: IllegalArgumentException) { failure("invalid_request") }

    private fun requireUuid(value: String) { require(UUID_PATTERN.matches(value)) }
    private fun failure(code: String): ControlPlaneResult.Failure = ControlPlaneResult.Failure(ControlPlaneError.local(code))

    private companion object {
        const val MAX_BODY_BYTES = 1_048_576
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val STATUS_PATH = Regex("/api/v1/(?:updates/commands|providers/assignments)/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/status")
        val POST_PATHS = setOf("/api/v1/installations/register", "/api/v1/licenses/activate", "/api/v1/activation-codes/request",
            "/api/v1/activation-codes/status", "/api/v1/devices/heartbeat", "/api/v1/diagnostics/batch", "/api/v1/devices/subscriptions", "/api/v1/devices/experience")
        val json = Json { ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false; encodeDefaults = true }
        fun allowedEndpoint(path: String, method: String): Boolean =
            (method == "GET" && path in setOf("/api/v1/providers/assignments", "/api/v1/devices/experience")) ||
                (method == "POST" && (path in POST_PATHS || STATUS_PATH.matches(path)))

        fun productionExecutor(): CallExecutor {
            val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .cache(null).cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE).callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
            return CallExecutor { request -> client.newCall(request).execute() }
        }
    }
}
