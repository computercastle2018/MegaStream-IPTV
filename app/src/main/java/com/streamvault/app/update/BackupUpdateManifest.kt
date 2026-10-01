package com.MegaStream.app.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI

data class BackupUpdatePolicy(
    val expectedPackageName: String,
    val deviceSdk: Int,
    val channel: AppUpdateChannel,
    val currentVersionCode: Int
)

/** Strict, Android-independent decoder for the backup update service. */
object BackupUpdateManifest {
    private const val HOST = "megastrem.megastation.uk"
    private val hexDigest = Regex("[0-9a-fA-F]{64}")
    private val version = Regex("[0-9]+(?:\\.[0-9]+)*(?:-beta(?:[.-][0-9a-zA-Z]+)*)?", RegexOption.IGNORE_CASE)

    fun parse(body: String, policy: BackupUpdatePolicy): GitHubReleaseInfo {
        val (expectedPackageName, deviceSdk, channel, currentVersionCode) = policy
        val json = Json.parseToJsonElement(body) as? JsonObject
            ?: throw IllegalArgumentException("Manifest must be an object")
        val versionName = json.requiredString("versionName")
        require(version.matches(versionName)) { "Invalid versionName" }
        require(versionName.contains("-beta", ignoreCase = true) == (channel == AppUpdateChannel.Beta)) {
            "Manifest does not match update channel"
        }
        json.optionalString("channel")?.let { require(it == channel.id) { "Invalid channel" } }
        val versionCode = json.number("versionCode").intOrNull
        require(versionCode != null && versionCode > 0 && versionCode > currentVersionCode) { "Invalid versionCode" }
        val minSdk = json.number("minSdk").intOrNull
        require(minSdk != null && minSdk > 0 && minSdk <= deviceSdk) { "Unsupported minSdk" }
        val packageName = json.requiredString("packageName")
        require(packageName == expectedPackageName && packageName.isNotBlank()) { "Unexpected packageName" }
        val sha256 = json.requiredString("sha256")
        require(hexDigest.matches(sha256)) { "Invalid sha256" }
        val certificate = json.optionalString("signingCertificateSha256")
        require(certificate == null || hexDigest.matches(certificate)) { "Invalid signingCertificateSha256" }
        val sizeBytes = if (json["sizeBytes"] == null || json["sizeBytes"] == JsonNull) null else {
            json.number("sizeBytes").longOrNull.also { require(it != null && it > 0) { "Invalid sizeBytes" } }
        }
        val mandatory = when (val value = json["mandatory"]) {
            null -> false
            is JsonPrimitive -> {
                require(!value.isString && value.booleanOrNull != null) { "Invalid mandatory" }
                value.booleanOrNull!!
            }
            else -> throw IllegalArgumentException("Invalid mandatory")
        }
        val releaseId = json.requiredString("releaseId")
        require(releaseId.isNotBlank()) { "Invalid releaseId" }
        return GitHubReleaseInfo(
            versionName = versionName,
            versionCode = versionCode,
            releaseUrl = json.requiredString("releaseUrl").also(::requireTrustedUrl),
            downloadUrl = json.requiredString("downloadUrl").also(::requireTrustedUrl),
            releaseNotes = (json.optionalString("releaseNotes") ?: json.optionalString("notes")).orEmpty(),
            publishedAt = json.optionalString("publishedAt"),
            sha256 = sha256,
            packageName = packageName,
            signingCertificateSha256 = certificate,
            releaseId = releaseId,
            minSdk = minSdk,
            mandatory = mandatory,
            sizeBytes = sizeBytes,
            source = AppUpdateSource.Backup
        )
    }

    private fun requireTrustedUrl(value: String) {
        require(isTrustedUrl(value)) { "Untrusted update URL" }
    }

    fun isTrustedUrl(value: String): Boolean {
        val uri = try { URI(value) } catch (_: java.net.URISyntaxException) { return false }
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(HOST, ignoreCase = true) && uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) && uri.rawFragment == null &&
            (uri.rawAuthority.equals(HOST, ignoreCase = true) ||
                uri.rawAuthority.equals("$HOST:443", ignoreCase = true))
    }

    private fun JsonObject.requiredString(name: String): String =
        optionalString(name) ?: throw IllegalArgumentException("Missing $name")

    private fun JsonObject.optionalString(name: String): String? {
        val value = this[name] ?: return null
        if (value == JsonNull) return null
        require(value is JsonPrimitive && value.isString) { "Invalid $name" }
        return value.content
    }

    private fun JsonObject.number(name: String): JsonPrimitive {
        val value = this[name]
        require(value is JsonPrimitive && !value.isString && value != JsonNull &&
            value.content.matches(Regex("-?(0|[1-9][0-9]*)"))) { "Invalid $name" }
        return value
    }
}
