package com.MegaStream.app.update

import android.os.Build
import com.MegaStream.app.BuildConfig
import com.MegaStream.domain.model.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

private const val GITHUB_RELEASES_LATEST_URL = "https://api.github.com/repos/computercastle2018/MegaStream-Releases/releases/latest"
private const val GITHUB_RELEASES_LIST_URL = "https://api.github.com/repos/computercastle2018/MegaStream-Releases/releases?per_page=20"
private const val BACKUP_UPDATE_URL = "https://megastrem.megastation.uk/api/v1/public/updates/latest"

enum class AppUpdateSource { GitHub, Backup }

@Serializable
data class GitHubReleaseInfo(
    val versionName: String,
    val versionCode: Int?,
    val releaseUrl: String,
    val downloadUrl: String?,
    val releaseNotes: String,
    val publishedAt: String?,
    val sha256: String? = null,
    val packageName: String? = null,
    val signingCertificateSha256: String? = null,
    val releaseId: String? = null,
    val minSdk: Int? = null,
    val mandatory: Boolean = false,
    val sizeBytes: Long? = null,
    val source: AppUpdateSource = AppUpdateSource.GitHub
)

@Singleton
class GitHubReleaseChecker @Inject constructor(okHttpClient: OkHttpClient) {
    // Never allow a metadata endpoint to redirect to an untrusted origin.
    private val client = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private companion object {
        private const val MAX_RESPONSE_BYTES = 512 * 1024L
        private val STRUCTURED_TAG_REGEX = Regex("""^v?(.+?)\+(\d+)$""", RegexOption.IGNORE_CASE)
        private val KNOWN_ABI_NAMES = listOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86_64", "x86")
    }

    suspend fun fetchLatestRelease(): Result<GitHubReleaseInfo?> = withContext(Dispatchers.IO) {
        val channel = AppUpdateChannel.fromCurrentBuild()
        val githubResult = fetchFromGithub(channel)
        if (githubResult is Result.Success) return@withContext githubResult
        val backupResult = fetchFromBackup(channel)
        if (backupResult is Result.Success) backupResult else githubResult
    }

    private fun fetchFromGithub(channel: AppUpdateChannel): Result<GitHubReleaseInfo> = try {
        val body = fetchBody(channel.releaseApiUrl, "application/vnd.github+json")
        val json = selectReleaseJson(body, channel)
            ?: throw IllegalArgumentException("No matching release found")
        val parsedTag = parseTagVersionInfo(json.string("tag_name"))
        require(parsedTag.versionName.isNotBlank()) { "Latest release tag is missing" }
        val releaseUrl = json.string("html_url")
        require(isHttpsUrl(releaseUrl)) { "Latest release URL is not HTTPS" }
        val asset = findApkAsset(json["assets"] as? JsonArray, channel)
        val downloadUrl = asset?.string("browser_download_url")
        val sha256 = parseGitHubAssetSha256(asset?.string("digest"))
        require(isGitHubReleaseVerifiable(parsedTag.versionCode, downloadUrl, sha256)) {
            "GitHub release is missing verifiable APK metadata"
        }
        Result.success(
            GitHubReleaseInfo(
                versionName = parsedTag.versionName,
                versionCode = parsedTag.versionCode,
                releaseUrl = releaseUrl,
                downloadUrl = downloadUrl,
                releaseNotes = json.string("body").trim(),
                publishedAt = json.string("published_at").takeIf { it.isNotBlank() },
                sha256 = sha256,
                packageName = BuildConfig.APPLICATION_ID,
                releaseId = (json["id"] as? JsonPrimitive)?.content?.takeIf { it.toLongOrNull() != null },
                sizeBytes = (asset?.get("size") as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
            )
        )
    } catch (error: java.io.IOException) {
        Result.error("Update check failed: ${error.message}", error)
    } catch (error: IllegalArgumentException) {
        Result.error("Update check failed: ${error.message}", error)
    }

    private fun fetchFromBackup(channel: AppUpdateChannel): Result<GitHubReleaseInfo?> = try {
        val url = BACKUP_UPDATE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("channel", channel.id)
            .addQueryParameter("abi", backupUpdateAbi(Build.SUPPORTED_ABIS.orEmpty().toList()))
            .build()
        val body = fetchBody(url.toString(), "application/json", allowNoRelease = true)
        if (body == null) Result.success(null) else Result.success(
            BackupUpdateManifest.parse(
                body = body,
                policy = BackupUpdatePolicy(
                    expectedPackageName = BuildConfig.APPLICATION_ID,
                    deviceSdk = Build.VERSION.SDK_INT,
                    channel = channel,
                    currentVersionCode = BuildConfig.VERSION_CODE
                )
            )
        )
    } catch (error: java.io.IOException) {
        Result.error("Backup manifest failed: ${error.message}", error)
    } catch (error: IllegalArgumentException) {
        Result.error("Backup manifest failed: ${error.message}", error)
    }

    private fun fetchBody(url: String, accept: String): String {
        return requireNotNull(fetchBody(url, accept, allowNoRelease = false))
    }

    private fun fetchBody(url: String, accept: String, allowNoRelease: Boolean): String? {
        val request = Request.Builder().url(url)
            .header("Accept", accept)
            .header("User-Agent", "MegaStream-Update-Checker")
            .build()
        return client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "HTTP ${response.code}" }
            if (allowNoRelease && response.code == 204) return@use null
            val body = response.body ?: throw IllegalArgumentException("Empty release response")
            readResponseBodyCapped(body).also { require(it.isNotBlank()) { "Empty release response" } }
        }
    }

    private fun selectReleaseJson(body: String, channel: AppUpdateChannel): JsonObject? {
        val root = Json.parseToJsonElement(body)
        return when (channel) {
            AppUpdateChannel.Stable -> (root as? JsonObject)?.takeUnless {
                it.boolean("draft") || it.boolean("prerelease") || it.string("tag_name").contains("-beta", true)
            }
            AppUpdateChannel.Beta -> (root as? JsonArray)?.filterIsInstance<JsonObject>()?.firstOrNull {
                !it.boolean("draft") && it.boolean("prerelease") &&
                    it.string("tag_name").contains("-beta", true) &&
                    findApkAsset(it["assets"] as? JsonArray, channel) != null
            }
        }
    }

    private fun readResponseBodyCapped(body: ResponseBody): String {
        require(body.contentLength() <= MAX_RESPONSE_BYTES) { "Release response exceeded 512 KiB" }
        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        body.byteStream().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                total += count
                require(total <= MAX_RESPONSE_BYTES) { "Release response exceeded 512 KiB" }
                output.write(buffer, 0, count)
            }
        }
        return output.toString(charset.name())
    }

    /** Prefer canonical, universal, matching ABI, then ABI-neutral APKs; never a foreign ABI. */
    private fun findApkAsset(assets: JsonArray?, channel: AppUpdateChannel): JsonObject? {
        val candidates = assets?.filterIsInstance<JsonObject>()?.filter {
            val name = it.string("name")
            isHttpsUrl(it.string("browser_download_url")) && name.endsWith(".apk", true) &&
                name.contains("beta", true) == (channel == AppUpdateChannel.Beta)
        }.orEmpty()
        val canonicalName = if (channel == AppUpdateChannel.Beta) "MegaStream-beta.apk" else "MegaStream.apk"
        candidates.firstOrNull { it.string("name").equals(canonicalName, true) }?.let { return it }
        candidates.firstOrNull { it.string("name").contains("universal", true) }?.let { return it }
        for (abi in Build.SUPPORTED_ABIS.orEmpty()) {
            candidates.firstOrNull { it.string("name").contains(abi, true) }?.let { return it }
        }
        return candidates.firstOrNull { asset ->
            KNOWN_ABI_NAMES.none { asset.string("name").contains(it, true) }
        }
    }

    private fun parseTagVersionInfo(rawTag: String): ParsedTagVersion {
        val normalized = rawTag.trim()
        val match = STRUCTURED_TAG_REGEX.matchEntire(normalized)
        return if (match != null) ParsedTagVersion(match.groupValues[1].trim(), match.groupValues[2].toIntOrNull())
        else ParsedTagVersion(normalized.removePrefix("v").trim(), null)
    }

    private fun isHttpsUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    private fun JsonObject.boolean(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull == true
}

// An older verifiable release remains a valid primary response; version policy decides whether to update.
internal fun isGitHubReleaseVerifiable(versionCode: Int?, downloadUrl: String?, sha256: String?): Boolean =
    versionCode != null && versionCode > 0 && !downloadUrl.isNullOrBlank() &&
        sha256 != null && sha256.matches(Regex("[0-9a-fA-F]{64}"))

internal fun parseGitHubAssetSha256(digest: String?): String? = digest
    ?.takeIf { it.startsWith("sha256:") }
    ?.removePrefix("sha256:")
    ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }

internal fun backupUpdateAbi(supportedAbis: List<String>): String = supportedAbis
    .firstOrNull { it in listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }
    ?.replace('-', '_') ?: "other"

enum class AppUpdateChannel(val id: String, val releaseApiUrl: String) {
    Stable(id = "stable", releaseApiUrl = GITHUB_RELEASES_LATEST_URL),
    Beta(id = "beta", releaseApiUrl = GITHUB_RELEASES_LIST_URL);

    companion object {
        fun fromCurrentBuild(): AppUpdateChannel = fromBuildConfig(BuildConfig.APP_UPDATE_CHANNEL, BuildConfig.VERSION_NAME)

        fun fromBuildConfig(channelId: String?, versionName: String): AppUpdateChannel = when {
            channelId.equals(Beta.id, ignoreCase = true) -> Beta
            versionName.contains("-beta", ignoreCase = true) -> Beta
            else -> Stable
        }
    }
}

private data class ParsedTagVersion(val versionName: String, val versionCode: Int?)
