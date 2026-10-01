package com.MegaStream.app.controlplane

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Closed outcomes contain neither exceptions nor raw transport data. */
sealed interface ControlPlaneResult<out T> {
    data class Success<T>(val value: T) : ControlPlaneResult<T> {
        override fun toString(): String = "ControlPlaneResult.Success([REDACTED])"
    }
    data class Failure(val error: ControlPlaneError) : ControlPlaneResult<Nothing>
}

/** Only locally defined vocabulary and validated correlation identifiers may leave transport. */
class ControlPlaneError private constructor(
    val title: String,
    val status: Int?,
    val code: String,
    val traceId: String?,
) {
    override fun toString(): String = "ControlPlaneError(title=$title, status=$status, code=$code, traceId=$traceId)"

    internal companion object {
        private val titles = mapOf(
            "invalid_request" to "Invalid request",
            "unauthorized" to "Unauthorized",
            "forbidden" to "Forbidden",
            "not_found" to "Not found",
            "conflict" to "Conflict",
            "rate_limited" to "Too many requests",
            "internal_error" to "Internal server error",
            "service_unavailable" to "Service unavailable",
        )
        private val localTitles = mapOf(
            "invalid_request" to "Invalid request",
            "invalid_response" to "Invalid response",
            "network_error" to "Network error",
            "unsafe_response" to "Unsafe response",
            "payload_too_large" to "Payload too large",
        )
        private val trace = Regex("(?:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|[0-9a-fA-F]{32}|00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2})")
        private val json = Json { ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }

        fun local(code: String): ControlPlaneError = ControlPlaneError(
            localTitles[code] ?: "Request failed", null,
            if (code in localTitles) code else "unknown", null,
        )

        fun unknown(status: Int): ControlPlaneError = ControlPlaneError("Request failed", status.takeIf { it in 400..599 }, "unknown", null)

        fun fromProblem(body: String, status: Int, sensitiveValues: Collection<String> = emptyList()): ControlPlaneError {
            val problem = try { json.decodeFromString(Problem.serializer(), body) } catch (_: IllegalArgumentException) { return unknown(status) }
            if (problem.status != status || status !in 400..599) return unknown(status)
            val title = titles[problem.code]
            val known = title != null && problem.title == title
            return ControlPlaneError(if (known) title!! else "Request failed", status,
                if (known) problem.code else "unknown", problem.traceId.takeIf { candidate -> trace.matches(candidate) && sensitiveValues.none { it.isNotEmpty() && candidate.contains(it, ignoreCase = true) } })
        }
    }
}

@Serializable
private class Problem(val title: String, val status: Int, val code: String, val traceId: String) {
    override fun toString(): String = "Problem([REDACTED])"
}
