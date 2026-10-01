package com.MegaStream.app.controlplane

import java.net.URI
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Exact control-plane origin; never apply this policy to provider playback URLs. */
object ControlPlaneUrlPolicy {
    const val ORIGIN = "https://megastrem.megastation.uk"
    private const val HOST = "megastrem.megastation.uk"

    /** Inspect raw syntax before OkHttp can erase empty userinfo or noncanonical ports. */
    fun isAllowed(value: String): Boolean {
        if (value.any { it.isWhitespace() || it.isISOControl() || it == '\\' } || '#' in value) return false
        val uri = try { URI(value) } catch (_: java.net.URISyntaxException) { return false }
        if (uri.scheme != "https" || uri.rawAuthority !in setOf(HOST, "$HOST:443") || uri.host != HOST ||
            (uri.port != -1 && uri.port != 443) || uri.rawUserInfo != null || uri.rawFragment != null) return false
        return value.toHttpUrlOrNull()?.let(::isAllowed) == true
    }

    /** HttpUrl has already canonicalized explicit default ports and empty userinfo. */
    fun isAllowed(value: HttpUrl): Boolean = value.scheme == "https" && value.host == HOST &&
        value.port == 443 && value.username.isEmpty() && value.password.isEmpty() && value.fragment == null
}
