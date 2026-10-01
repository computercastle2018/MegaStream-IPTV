package com.MegaStream.app.update

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/** Keeps full verification metadata keyed by the exact release identity, never by version alone. */
class UpdateMetadataStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("update_metadata", Context.MODE_PRIVATE)
    private val json = Json { encodeDefaults = true }

    /** Caller must use an IO dispatcher: verification metadata is durable before this returns. */
    fun save(release: GitHubReleaseInfo): Unit = synchronized(writeLock) {
        val existing = find(release)
        val merged = if (existing == null) release else release.copy(
            sha256 = retainVerified("sha256", existing.sha256, release.sha256),
            signingCertificateSha256 = retainVerified("signingCertificateSha256", existing.signingCertificateSha256, release.signingCertificateSha256),
            packageName = retainVerified("packageName", existing.packageName, release.packageName),
            minSdk = retainVerified("minSdk", existing.minSdk, release.minSdk),
            sizeBytes = retainVerified("sizeBytes", existing.sizeBytes, release.sizeBytes),
            releaseId = retainVerified("releaseId", existing.releaseId, release.releaseId),
            mandatory = existing.mandatory || release.mandatory,
            source = if (existing.source == AppUpdateSource.Backup) AppUpdateSource.Backup else release.source
        )
        if (!preferences.edit().putString(identityKey(merged), json.encodeToString(merged)).commit()) {
            throw java.io.IOException("Could not persist update metadata")
        }
    }

    private fun <T> retainVerified(field: String, previous: T?, incoming: T?): T? {
        val equivalent = previous == incoming ||
            (field.endsWith("sha256", ignoreCase = true) && previous is String && incoming is String && previous.equals(incoming, true))
        require(previous == null || incoming == null || equivalent) { "Conflicting update metadata: $field" }
        return previous ?: incoming
    }

    fun find(release: GitHubReleaseInfo): GitHubReleaseInfo? {
        val stored = decode(preferences.getString(identityKey(release), null)) ?: return null
        return stored.takeIf {
            it.versionCode == release.versionCode && it.versionName == release.versionName &&
                it.downloadUrl == release.downloadUrl
        }
    }

    /** A version name shared by multiple release identities is deliberately ambiguous. */
    fun findByVersion(versionName: String): GitHubReleaseInfo? = preferences.all
        .asSequence()
        .filter { it.key.startsWith(KEY_PREFIX) }
        .mapNotNull { (key, value) -> decode(value as? String)?.takeIf { identityKey(it) == key } }
        .filter { it.versionName == versionName }
        .take(2)
        .toList()
        .singleOrNull()

    /** Corrupt or incompatible local metadata must never authorize a download. */
    private fun decode(value: String?): GitHubReleaseInfo? = value?.let {
        try { json.decodeFromString<GitHubReleaseInfo>(it) } catch (_: IllegalArgumentException) { null }
    }

    private fun identityKey(release: GitHubReleaseInfo): String {
        val identity = JsonArray(listOf(
            release.versionCode?.let(::JsonPrimitive) ?: JsonNull,
            JsonPrimitive(release.versionName),
            release.downloadUrl?.let(::JsonPrimitive) ?: JsonNull
        )).toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return KEY_PREFIX + hash
    }

    private companion object {
        // Separate store wrappers share one read/merge/commit critical section.
        val writeLock = Any()
        const val KEY_PREFIX = "release_"
    }
}
