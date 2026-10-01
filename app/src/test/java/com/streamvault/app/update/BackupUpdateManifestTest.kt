package com.MegaStream.app.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupUpdateManifestTest {
    private val policy = BackupUpdatePolicy("com.megastream.app", 35, AppUpdateChannel.Stable, 31)
    private val hash = "ab".repeat(32)
    private val base = Json.parseToJsonElement("""{
        "versionCode":32,"versionName":"2.1.7","packageName":"com.megastream.app",
        "sha256":"$hash","minSdk":27,"releaseId":"stable-32",
        "releaseUrl":"https://megastrem.megastation.uk/releases/32",
        "downloadUrl":"https://megastrem.megastation.uk/downloads/32.apk"
    }""") as JsonObject

    @Test fun verifiedManifestPreservesAllSecurityMetadata() {
        val release = parse(mapOf(
            "mandatory" to JsonPrimitive(true), "sizeBytes" to JsonPrimitive(4_000_000_000L),
            "signingCertificateSha256" to JsonPrimitive(hash.uppercase()),
            "releaseNotes" to JsonPrimitive("Changes"), "publishedAt" to JsonPrimitive("2026-09-30T00:00:00Z")
        ))
        assertEquals(32, release.versionCode)
        assertEquals("2.1.7", release.versionName)
        assertEquals(hash, release.sha256)
        assertEquals(hash.uppercase(), release.signingCertificateSha256)
        assertEquals("com.megastream.app", release.packageName)
        assertEquals(27, release.minSdk)
        assertEquals("stable-32", release.releaseId)
        assertEquals(4_000_000_000L, release.sizeBytes)
        assertEquals(true, release.mandatory)
        assertEquals(AppUpdateSource.Backup, release.source)
        assertEquals("Changes", release.releaseNotes)
        assertEquals("2026-09-30T00:00:00Z", release.publishedAt)
    }

    @Test fun betaCommitVersionOnlyAcceptedByBetaPolicy() {
        val values = mapOf("versionName" to JsonPrimitive("1.0.11-beta-deadbee"), "channel" to JsonPrimitive("beta"))
        assertEquals("1.0.11-beta-deadbee", parse(values, policy.copy(channel = AppUpdateChannel.Beta)).versionName)
        assertRejected(values)
        assertThrows(IllegalArgumentException::class.java) { parse(emptyMap(), policy.copy(channel = AppUpdateChannel.Beta)) }
    }

    @Test fun absentOrNullCertificateIsAllowedAndNotesAliasWorks() {
        assertNull(parse(emptyMap()).signingCertificateSha256)
        assertNull(parse(mapOf("signingCertificateSha256" to JsonNull)).signingCertificateSha256)
        assertEquals("Alias", parse(mapOf("notes" to JsonPrimitive("Alias"))).releaseNotes)
        assertEquals("Primary", parse(mapOf("notes" to JsonPrimitive("Alias"), "releaseNotes" to JsonPrimitive("Primary"))).releaseNotes)
    }

    @Test fun malformedAndIncompatibleSecurityFieldsAreRejected() {
        val cases = listOf(
            "versionCode" to JsonPrimitive(31), "versionCode" to JsonPrimitive(30),
            "versionCode" to JsonPrimitive(0), "versionCode" to JsonPrimitive(-1),
            "versionCode" to JsonPrimitive(2147483648L), "versionCode" to JsonPrimitive(32.5),
            "versionCode" to JsonPrimitive("32"), "versionCode" to JsonPrimitive(32.0),
            "versionCode" to Json.parseToJsonElement("32e0"), "versionCode" to JsonNull,
            "versionName" to JsonPrimitive(""), "versionName" to JsonPrimitive("garbage"),
            "versionName" to JsonPrimitive("2.1.7-rc.1"), "versionName" to JsonPrimitive(" 2.1.7 "),
            "sha256" to JsonPrimitive("a".repeat(63)), "sha256" to JsonPrimitive("g".repeat(64)),
            "sha256" to JsonPrimitive(""), "sha256" to JsonNull,
            "packageName" to JsonPrimitive("com.foreign.app"),
            "minSdk" to JsonPrimitive(36), "minSdk" to JsonPrimitive(0),
            "minSdk" to JsonPrimitive(27.5), "minSdk" to JsonPrimitive("27"),
            "signingCertificateSha256" to JsonPrimitive("bad"),
            "sizeBytes" to JsonPrimitive(0), "sizeBytes" to JsonPrimitive(-1),
            "sizeBytes" to JsonPrimitive(1.5), "sizeBytes" to JsonPrimitive("5"),
            "sizeBytes" to Json.parseToJsonElement("9223372036854775808"),
            "mandatory" to JsonPrimitive("true"), "mandatory" to JsonPrimitive(1),
            "mandatory" to JsonNull, "releaseId" to JsonPrimitive("  "),
            "releaseId" to JsonNull, "channel" to JsonPrimitive("beta")
        )
        cases.forEach { (key, value) -> assertRejected(mapOf(key to value)) }
        listOf("versionCode", "versionName", "sha256", "packageName", "minSdk", "releaseId", "releaseUrl", "downloadUrl").forEach { key ->
            assertThrows("Missing $key", IllegalArgumentException::class.java) {
                BackupUpdateManifest.parse(JsonObject(base - key).toString(), policy)
            }
        }
    }

    @Test fun bothUrlsEnforceExactHttpsOrigin() {
        val rejected = listOf(
            "http://megastrem.megastation.uk/file", "https://foreign.example/file",
            "https://sub.megastrem.megastation.uk/file", "https://megastrem.megastation.uk./file",
            "https://user@megastrem.megastation.uk/file", "https://megastrem.megastation.uk:444/file",
            "//megastrem.megastation.uk/file", "/file", "https://megastrem.megastation.uk/file#fragment",
            "https://megastrem.megastation.uk:/file", "https://megastrem.megastation.uk\\@evil.example/file",
            "https://megastrem.megastation.uk.evil.example/file"
        )
        listOf("releaseUrl", "downloadUrl").forEach { key ->
            rejected.forEach { assertRejected(mapOf(key to JsonPrimitive(it))) }
            val explicitPort = "https://megastrem.megastation.uk:443/file"
            val parsed = parse(mapOf(key to JsonPrimitive(explicitPort)))
            assertEquals(explicitPort, if (key == "releaseUrl") parsed.releaseUrl else parsed.downloadUrl)
        }
    }

    private fun assertRejected(changes: Map<String, JsonElement>) {
        assertThrows("Rejected $changes", IllegalArgumentException::class.java) { parse(changes) }
    }

    private fun parse(changes: Map<String, JsonElement>, selectedPolicy: BackupUpdatePolicy = policy): GitHubReleaseInfo =
        BackupUpdateManifest.parse(JsonObject(base + changes).toString(), selectedPolicy)
}
