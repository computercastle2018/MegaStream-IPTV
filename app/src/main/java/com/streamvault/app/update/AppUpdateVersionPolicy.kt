package com.MegaStream.app.update

import com.MegaStream.app.BuildConfig
import java.time.Instant
import kotlin.math.max

internal fun isRemoteAppVersionNewer(
    remoteVersionCode: Int?,
    remoteVersionName: String,
    remotePublishedAt: String? = null
): Boolean {
    return isRemoteAppVersionNewerForBuild(
        remoteVersionCode = remoteVersionCode,
        remoteVersionName = remoteVersionName,
        remotePublishedAt = remotePublishedAt,
        currentVersionCode = BuildConfig.VERSION_CODE,
        currentVersionName = BuildConfig.VERSION_NAME,
        currentBuildTimestampUtc = BuildConfig.BUILD_TIMESTAMP_UTC,
        currentChannel = AppUpdateChannel.fromCurrentBuild()
    )
}

internal fun isRemoteAppVersionNewerForBuild(
    remoteVersionCode: Int?,
    remoteVersionName: String,
    remotePublishedAt: String?,
    currentVersionCode: Int,
    currentVersionName: String,
    currentBuildTimestampUtc: Long,
    currentChannel: AppUpdateChannel
): Boolean {
    val remoteDescriptor = parseAppVersionDescriptor(remoteVersionName)
    if (remoteDescriptor.channel != currentChannel) {
        return false
    }

    if (remoteVersionCode != null && remoteVersionCode > currentVersionCode) {
        return true
    }
    if (remoteVersionCode != null && remoteVersionCode < currentVersionCode) {
        return false
    }
    if (remoteVersionCode != null &&
        remoteVersionCode == currentVersionCode &&
        currentChannel != AppUpdateChannel.Beta
    ) {
        return false
    }

    val versionComparison = compareAppVersionNames(
        remoteDescriptor.baseVersionName,
        parseAppVersionDescriptor(currentVersionName).baseVersionName
    )
    if (versionComparison != 0) {
        return versionComparison > 0
    }

    if (currentChannel == AppUpdateChannel.Beta) {
        val remotePublishedAtMillis = remotePublishedAt.toEpochMillisOrNull() ?: return false
        return remotePublishedAtMillis > currentBuildTimestampUtc
    }

    return false
}

internal fun compareAppVersionNames(left: String, right: String): Int {
    val leftParts = left.removePrefix("v").split('.')
    val rightParts = right.removePrefix("v").split('.')
    val length = max(leftParts.size, rightParts.size)
    for (index in 0 until length) {
        val leftValue = leftParts.getOrNull(index)?.toIntOrNull() ?: 0
        val rightValue = rightParts.getOrNull(index)?.toIntOrNull() ?: 0
        if (leftValue != rightValue) {
            return leftValue.compareTo(rightValue)
        }
    }
    return 0
}

private data class ParsedAppVersionDescriptor(
    val baseVersionName: String,
    val channel: AppUpdateChannel
)

private fun parseAppVersionDescriptor(versionName: String): ParsedAppVersionDescriptor {
    val normalized = versionName.removePrefix("v").trim()
    val betaIndex = normalized.indexOf("-beta", ignoreCase = true)
    return if (betaIndex >= 0) {
        ParsedAppVersionDescriptor(
            baseVersionName = normalized.substring(0, betaIndex),
            channel = AppUpdateChannel.Beta
        )
    } else {
        ParsedAppVersionDescriptor(
            baseVersionName = normalized,
            channel = AppUpdateChannel.Stable
        )
    }
}

private fun String?.toEpochMillisOrNull(): Long? {
    val value = this?.trim().orEmpty()
    if (value.isEmpty()) return null
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}
