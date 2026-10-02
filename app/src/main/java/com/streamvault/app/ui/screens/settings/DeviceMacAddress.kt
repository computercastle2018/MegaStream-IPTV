package com.MegaStream.app.ui.screens.settings

import com.MegaStream.data.util.ProviderInputSanitizer
import java.util.Locale

enum class DeviceMacSource { MANUAL, READABLE }

data class DeviceMacAddress(val address: String, val source: DeviceMacSource)

fun normalizeDeviceMacAddress(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.contains(':') && trimmed.contains('-')) return null
    val normalized = trimmed.replace('-', ':').uppercase(Locale.ROOT)
    if (ProviderInputSanitizer.validateMacAddress(normalized) != null) return null
    if (normalized in setOf("00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "02:00:00:00:00:00")) return null
    if (normalized.substring(0, 2).toInt(16) and 1 != 0) return null
    return normalized
}
