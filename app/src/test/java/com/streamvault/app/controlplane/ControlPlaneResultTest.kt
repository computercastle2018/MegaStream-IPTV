package com.MegaStream.app.controlplane

import org.junit.Assert.*
import org.junit.Test

class ControlPlaneResultTest {
    @Test fun successDoesNotInvokeSensitiveValueToString() {
        val secret = object { override fun toString(): String = error("Secret toString must never run") }
        assertEquals("ControlPlaneResult.Success([REDACTED])", ControlPlaneResult.Success(secret).toString())
    }

    @Test fun onlyKnownProblemVocabularyAndBoundedTraceSurvive() {
        val error = ControlPlaneError.fromProblem(problem("Invalid request", "invalid_request", TRACE), 400)
        assertEquals("invalid_request", error.code)
        assertEquals("Invalid request", error.title)
        assertEquals(400, error.status)
        assertEquals(TRACE, error.traceId)
        listOf("https://provider.example/secret", "token=secret", "f".repeat(33)).forEach {
            assertNull(ControlPlaneError.fromProblem(problem("Invalid request", "invalid_request", it), 400).traceId)
        }
    }

    @Test fun maliciousTitlesCodesAndExtraPropertiesDoNotEscape() {
        listOf(problem("password=secret", "invalid_request", TRACE), problem("Invalid request", "secret_code", TRACE),
            """{"title":"Invalid request","status":400,"code":"invalid_request","traceId":"$TRACE","body":"password=secret"}""",
            "not json secret", problem("Invalid request", "invalid_request", TRACE).replace("400", "401")
        ).forEach {
            val error = ControlPlaneError.fromProblem(it, 400)
            assertEquals("unknown", error.code)
            assertEquals("Request failed", error.title)
            assertFalse(error.toString().contains("secret"))
        }
    }

    @Test fun validTraceThatEchoesKnownSecretIsSuppressedCaseInsensitively() {
        val error = ControlPlaneError.fromProblem(problem("Invalid request", "invalid_request", TRACE.uppercase()), 400, listOf(TRACE))
        assertNull(error.traceId)
        assertFalse(error.toString().contains(TRACE, ignoreCase = true))
    }

    private fun problem(title: String, code: String, trace: String) =
        """{"title":"$title","status":400,"code":"$code","traceId":"$trace"}"""
    private companion object { const val TRACE = "abcdef12-3456-4789-abcd-0123456789ab" }
}
