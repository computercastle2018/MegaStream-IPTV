package com.MegaStream.app.update

import android.os.Build
import com.MegaStream.app.BuildConfig
import com.MegaStream.domain.model.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

private const val GITHUB_RELEASES_LATEST_URL = "https://api.github.com/repos/computercastle2018/MegaStream-IPTV/releases/latest"
private const val GITHUB_RELEASES_LIST_URL = "https://api.github.com/repos/computercastle2018/MegaStream-IPTV/releases?per_page=20"

/**
 * Optional Google-Drive-hosted update manifest used as a **fallback** when
 * the GitHub Releases API fails (network down, GitHub blocked, rate-limited,
 * 404, etc.). When this URL points to a publicly shared JSON file with the
 * schema below, the checker tries GitHub first and only consults Drive when
 * GitHub returns an error.
 *
 *   {
 *     "versionName": "1.0.12",
 *     "versionCode": 13,
 *     "downloadUrl": "https://drive.google.com/uc?export=download&id=APK_FILE_ID",
 *     "releaseUrl":  "https://drive.google.com/file/d/APK_FILE_ID/view",
 *     "releaseNotes": "Release notes here...",
 *     "publishedAt": "2026-05-23T12:00:00Z"
 *   }
 *
 * To enable: upload `MegaStream.apk` to Drive (share = Anyone with link),
 * fill the manifest JSON with the APK's file id, upload the manifest to
 * Drive (share = Anyone with link), and paste its direct-download URL here.
 * URL form: https://drive.google.com/uc?export=download&id=MANIFEST_FILE_ID
 */
private const val DRIVE_UPDATE_MANIFEST_URL = ""

data class GitHubReleaseInfo(
    val versionName: String,
    val versionCode: Int?,
    val releaseUrl: String,
    val downloadUrl: String?,
    val releaseNotes: String,
    val publishedAt: String?
)

@Singleton
class GitHubReleaseChecker @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private companion object {
        private const val MAX_RESPONSE_BYTES = 512 * 1024L
        private val STRUCTURED_TAG_REGEX = Regex("""^v?(.+?)\+(\d+)$""", RegexOption.IGNORE_CASE)
        /** ABI markers that may appear in a per-architecture APK asset name. */
        private val KNOWN_ABI_NAMES = listOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86_64", "x86")
    }

    suspend fun fetchLatestRelease(): Result<GitHubReleaseInfo> = withContext(Dispatchers.IO) {
        // GitHub Releases is the primary source. If it fails (network down,
        // 404, rate-limited, etc.) and a Drive manifest URL is configured,
        // fall back to the Drive-hosted manifest so users behind GitHub-blocked
        // networks or during outages can still receive updates.
        val githubResult = fetchFromGithub()
        if (githubResult is Result.Success) {
            return@withContext githubResult
        }
        if (DRIVE_UPDATE_MANIFEST_URL.isNotBlank()) {
            val driveResult = fetchFromDriveManifest()
            if (driveResult is Result.Success) {
                return@withContext driveResult
            }
        }
        // Return the original GitHub error (more informative than Drive's).
        return@withContext githubResult
    }

    private fun fetchFromGithub(): Result<GitHubReleaseInfo> {
        return try {
            val updateChannel = AppUpdateChannel.fromCurrentBuild()
            val request = Request.Builder()
                .url(updateChannel.releaseApiUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "MegaStream-Update-Checker")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.error("Update check failed: HTTP ${response.code}")
                }

                val body = when (val bodyResult = response.body?.let(::readResponseBodyCapped)) {
                    is Result.Success -> bodyResult.data
                    is Result.Error -> return Result.error(bodyResult.message, bodyResult.exception)
                    null,
                    Result.Loading -> ""
                }
                if (body.isBlank()) {
                    return Result.error("Update check failed: empty GitHub release response")
                }

                val json = selectReleaseJson(body, updateChannel)
                    ?: return Result.error(
                        if (updateChannel == AppUpdateChannel.Beta) {
                            "Update check failed: no beta release found"
                        } else {
                            "Update check failed: latest release response was invalid"
                        }
                    )
                val parsedTag = parseTagVersionInfo(json.optString("tag_name"))
                if (parsedTag.versionName.isBlank()) {
                    return Result.error("Update check failed: latest release tag is missing")
                }

                val notes = json.optString("body").trim()
                val assets = json.optJSONArray("assets")
                val releaseUrl = json.optString("html_url").takeIf(::isHttpsUrl).orEmpty()
                if (releaseUrl.isBlank()) {
                    return Result.error("Update check failed: latest release URL is not HTTPS")
                }
                val downloadUrl = findApkAssetUrl(assets, updateChannel)

                Result.success(
                    GitHubReleaseInfo(
                        versionName = parsedTag.versionName,
                        versionCode = parsedTag.versionCode,
                        releaseUrl = releaseUrl,
                        downloadUrl = downloadUrl,
                        releaseNotes = notes,
                        publishedAt = json.optString("published_at").takeIf { it.isNotBlank() }
                    )
                )
            }
        } catch (error: IOException) {
            Result.error("Update check failed: network error", error)
        } catch (error: Exception) {
            Result.error("Update check failed: ${error.message}", error)
        }
    }

    /**
     * Fetches the simple Drive-hosted manifest and maps it into the same
     * GitHubReleaseInfo shape the rest of the app already understands.
     */
    private fun fetchFromDriveManifest(): Result<GitHubReleaseInfo> {
        return try {
            val request = Request.Builder()
                .url(DRIVE_UPDATE_MANIFEST_URL)
                .header("Accept", "application/json")
                .header("User-Agent", "MegaStream-Update-Checker")
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.error("Drive manifest fetch failed: HTTP ${response.code}")
                }
                val body = when (val bodyResult = response.body?.let(::readResponseBodyCapped)) {
                    is Result.Success -> bodyResult.data
                    is Result.Error -> return Result.error(bodyResult.message, bodyResult.exception)
                    null,
                    Result.Loading -> ""
                }
                if (body.isBlank()) {
                    return Result.error("Drive manifest fetch failed: empty body")
                }
                val json = JSONObject(body)
                val versionName = json.optString("versionName").trim()
                if (versionName.isBlank()) {
                    return Result.error("Drive manifest missing versionName")
                }
                val releaseUrl = json.optString("releaseUrl").takeIf(::isHttpsUrl).orEmpty()
                if (releaseUrl.isBlank()) {
                    return Result.error("Drive manifest releaseUrl must be HTTPS")
                }
                val downloadUrl = json.optString("downloadUrl").takeIf { isHttpsUrl(it) }
                Result.success(
                    GitHubReleaseInfo(
                        versionName = versionName,
                        versionCode = json.optInt("versionCode").takeIf { it > 0 },
                        releaseUrl = releaseUrl,
                        downloadUrl = downloadUrl,
                        releaseNotes = json.optString("releaseNotes").trim(),
                        publishedAt = json.optString("publishedAt").takeIf { it.isNotBlank() }
                    )
                )
            }
        } catch (error: IOException) {
            Result.error("Drive manifest network error", error)
        } catch (error: Exception) {
            Result.error("Drive manifest parse failed: ${error.message}", error)
        }
    }

    private fun selectReleaseJson(body: String, updateChannel: AppUpdateChannel): JSONObject? {
        return when (updateChannel) {
            AppUpdateChannel.Stable -> JSONObject(body)
            AppUpdateChannel.Beta -> {
                val releases = org.json.JSONArray(body)
                for (index in 0 until releases.length()) {
                    val release = releases.optJSONObject(index) ?: continue
                    if (release.optBoolean("draft")) continue
                    if (!release.optBoolean("prerelease")) continue
                    val tagName = release.optString("tag_name")
                    if (!tagName.contains("-beta", ignoreCase = true)) continue
                    val downloadUrl = findApkAssetUrl(release.optJSONArray("assets"), updateChannel)
                    if (downloadUrl != null) {
                        return release
                    }
                }
                null
            }
        }
    }

    private fun readResponseBodyCapped(body: ResponseBody): Result<String> {
        val contentLength = body.contentLength()
        if (contentLength > MAX_RESPONSE_BYTES) {
            return Result.error("Update check failed: GitHub release response exceeded 512 KB")
        }

        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var totalBytesRead = 0L

        body.byteStream().use { input ->
            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) break

                totalBytesRead += bytesRead
                if (totalBytesRead > MAX_RESPONSE_BYTES) {
                    return Result.error("Update check failed: GitHub release response exceeded 512 KB")
                }

                output.write(buffer, 0, bytesRead)
            }
        }

        return Result.success(output.toString(charset.name()))
    }

    /**
     * Picks the APK asset to download for this device.
     *
     * Preference order:
     *  1. the canonical name (MegaStream.apk / MegaStream-beta.apk),
     *  2. an explicitly universal build,
     *  3. an ABI-specific build matching one of this device's supported ABIs,
     *  4. any APK that doesn't advertise an ABI in its name.
     *
     * Crucially it never falls back to an APK built for a *foreign* ABI: doing
     * so downloads fine and then fails to install, which looks to the user like
     * the update silently breaks. Returning null instead surfaces a proper
     * "download unavailable" error.
     */
    private fun findApkAssetUrl(assets: org.json.JSONArray?, updateChannel: AppUpdateChannel): String? {
        if (assets == null) return null

        val candidates = mutableListOf<Pair<String, String>>() // name to url
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url").takeIf { it.isNotBlank() } ?: continue
            if (!isHttpsUrl(url)) continue
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val isBetaAsset = name.contains("beta", ignoreCase = true)
            val matchesChannel = when (updateChannel) {
                AppUpdateChannel.Stable -> !isBetaAsset
                AppUpdateChannel.Beta -> isBetaAsset
            }
            if (!matchesChannel) continue
            candidates += name to url
        }
        if (candidates.isEmpty()) return null

        val canonicalName = when (updateChannel) {
            AppUpdateChannel.Stable -> "MegaStream.apk"
            AppUpdateChannel.Beta -> "MegaStream-beta.apk"
        }
        candidates.firstOrNull { (name, _) -> name.equals(canonicalName, ignoreCase = true) }
            ?.let { return it.second }

        candidates.firstOrNull { (name, _) -> name.contains("universal", ignoreCase = true) }
            ?.let { return it.second }

        for (abi in Build.SUPPORTED_ABIS.orEmpty()) {
            candidates.firstOrNull { (name, _) -> name.contains(abi, ignoreCase = true) }
                ?.let { return it.second }
        }

        return candidates.firstOrNull { (name, _) ->
            KNOWN_ABI_NAMES.none { abi -> name.contains(abi, ignoreCase = true) }
        }?.second
    }

    private fun parseTagVersionInfo(rawTagName: String): ParsedTagVersion {
        val normalizedTag = rawTagName.trim()
        val structuredMatch = STRUCTURED_TAG_REGEX.matchEntire(normalizedTag)
        if (structuredMatch != null) {
            return ParsedTagVersion(
                versionName = structuredMatch.groupValues[1].trim(),
                versionCode = structuredMatch.groupValues[2].toIntOrNull()
            )
        }

        return ParsedTagVersion(
            versionName = normalizedTag.removePrefix("v").trim(),
            versionCode = null
        )
    }

    private fun isHttpsUrl(url: String): Boolean {
        val normalized = url.trim()
        if (normalized.isBlank()) return false
        return runCatching {
            val parsed = URI(normalized)
            parsed.scheme.equals("https", ignoreCase = true) && !parsed.host.isNullOrBlank()
        }.getOrDefault(false)
    }
}

enum class AppUpdateChannel(val id: String, val releaseApiUrl: String) {
    Stable(id = "stable", releaseApiUrl = GITHUB_RELEASES_LATEST_URL),
    Beta(id = "beta", releaseApiUrl = GITHUB_RELEASES_LIST_URL);

    companion object {
        fun fromCurrentBuild(): AppUpdateChannel {
            return fromBuildConfig(BuildConfig.APP_UPDATE_CHANNEL, BuildConfig.VERSION_NAME)
        }

        fun fromBuildConfig(channelId: String?, versionName: String): AppUpdateChannel {
            return when {
                channelId.equals(Beta.id, ignoreCase = true) -> Beta
                versionName.contains("-beta", ignoreCase = true) -> Beta
                else -> Stable
            }
        }
    }
}

private data class ParsedTagVersion(
    val versionName: String,
    val versionCode: Int?
)
