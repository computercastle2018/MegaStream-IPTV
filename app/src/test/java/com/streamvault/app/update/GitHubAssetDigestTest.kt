package com.MegaStream.app.update

import com.MegaStream.domain.model.Result
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubAssetDigestTest {
    @Test fun privateGithubAndEmptyBackupReportNoRelease() = runBlocking {
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request.url.toString())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (request.url.host == "api.github.com") 404 else 204)
                .message("test").body("".toResponseBody()).build()
        }.build()
        val result = GitHubReleaseChecker(client).fetchLatestRelease()
        assertEquals(Result.success<GitHubReleaseInfo?>(null), result)
        assertEquals(2, requests.size)
        assertEquals(true, requests.last().contains("abi=other"))
    }

    @Test fun backupAbiUsesServerNamesAndSupportedFallback() {
        assertEquals("arm64_v8a", backupUpdateAbi(listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("armeabi_v7a", backupUpdateAbi(listOf("armeabi-v7a")))
        assertEquals("x86_64", backupUpdateAbi(listOf("x86_64", "x86")))
        assertEquals("x86", backupUpdateAbi(listOf("unknown", "x86")))
        assertEquals("other", backupUpdateAbi(emptyList()))
    }
    @Test fun onlyVerifiableReleasesRemainPrimaryRegardlessOfAge() {
        val hash = "ab".repeat(32)
        val url = "https://github.com/example/releases/download/v2/MegaStream.apk"
        listOf(1, 31, 32).forEach { code ->
            assertEquals("Positive version code $code", true, isGitHubReleaseVerifiable(code, url, hash))
        }
        listOf(null, 0, -1).forEach { code ->
            assertEquals("Invalid version code $code", false, isGitHubReleaseVerifiable(code, url, hash))
        }
        listOf(null, "", " ").forEach { missingUrl ->
            assertEquals("Missing APK URL", false, isGitHubReleaseVerifiable(32, missingUrl, hash))
        }
        listOf(null, "", "a".repeat(63), "g".repeat(64)).forEach { invalidHash ->
            assertEquals("Invalid digest", false, isGitHubReleaseVerifiable(32, url, invalidHash))
        }
    }

    @Test fun onlyCompleteSha256AssetDigestsAreAccepted() {
        val hash = "aB".repeat(32)
        assertEquals(hash, parseGitHubAssetSha256("sha256:$hash"))
        listOf(null, "", hash, "sha1:$hash", "sha256:${"a".repeat(63)}", "sha256:${"g".repeat(64)}", "sha256:$hash ")
            .forEach { assertNull("Rejected digest: $it", parseGitHubAssetSha256(it)) }
    }
}
