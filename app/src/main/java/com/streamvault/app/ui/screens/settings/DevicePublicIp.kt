package com.MegaStream.app.ui.screens.settings

import android.content.Context
import android.net.ConnectivityManager
import java.net.InetAddress
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Explicit user action only. No installation, MAC, credential, or provider data is sent. */
internal fun readDevicePublicIp(context: Context): String? = runCatching {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
    val network = manager.activeNetwork ?: return null
    val connection = network.openConnection(URL("https://speed.cloudflare.com/cdn-cgi/trace")) as HttpsURLConnection
    try {
        connection.connectTimeout = 3_000
        connection.readTimeout = 3_000
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        if (connection.responseCode != 200) return null
        val trace = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(4_097)
            var count = 0
            while (count < buffer.size) {
                val read = reader.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            String(buffer, 0, count)
        }
        parseCloudflarePublicIp(trace)
    } finally {
        connection.disconnect()
    }
}.getOrNull()

internal fun parseCloudflarePublicIp(trace: String): String? {
    if (trace.length > 4_096) return null
    val addresses = trace.lineSequence().filter { it.startsWith("ip=") }.map { it.removePrefix("ip=").trim() }.toList()
    if (addresses.size != 1) return null
    val value = addresses.single()
    val isIpv4 = Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}").matches(value) && value.split('.').all {
        it.toInt() <= 255 && (it.length == 1 || !it.startsWith('0'))
    }
    val isIpv6 = ':' in value && Regex("[0-9A-Fa-f:]{2,39}").matches(value)
    if (!isIpv4 && !isIpv6) return null
    // Only numeric literals reach InetAddress: no DNS lookup of response-controlled hostnames.
    val address = runCatching { InetAddress.getByName(value) }.getOrNull() ?: return null
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress ||
        (address.address.size == 16 && address.address[0].toInt() and 0xfe == 0xfc)) return null
    return value
}
