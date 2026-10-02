package com.MegaStream.app.ui.screens.settings

import android.content.Context
import android.content.SharedPreferences
import com.MegaStream.data.security.AndroidKeystoreCredentialCrypto
import com.MegaStream.data.security.CredentialCrypto

/** Only explicitly saved manual addresses are reported; provider/Stalker MACs are unrelated. */
class DeviceMacAddressStore internal constructor(
    private val preferences: SharedPreferences,
    private val crypto: CredentialCrypto
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("device_network_settings", Context.MODE_PRIVATE),
        AndroidKeystoreCredentialCrypto()
    )

    private companion object {
        val lock = Any()
        const val MAC_KEY = "manual_mac"
    }

    fun read(): DeviceMacAddress? = synchronized(lock) { runCatching {
        val encrypted = preferences.getString(MAC_KEY, null) ?: return null
        val address = normalizeDeviceMacAddress(crypto.decryptIfNeeded(encrypted)) ?: return null
        DeviceMacAddress(address, DeviceMacSource.MANUAL)
    }.getOrNull() }

    /** Blocking Keystore/disk access: call from Dispatchers.IO. Throws on invalid input or failed save. */
    fun saveManual(input: String): DeviceMacAddress {
        val address = requireNotNull(normalizeDeviceMacAddress(input)) { "Invalid device MAC address" }
        val encrypted = crypto.encryptIfNeeded(address)
        persist(encrypted)
        return DeviceMacAddress(address, DeviceMacSource.MANUAL)
    }

    fun clear() {
        persist(null)
    }

    private fun persist(encrypted: String?) = synchronized(lock) {
        val previous = preferences.getString(MAC_KEY, null)
        if (!preferences.edit().putString(MAC_KEY, encrypted).commit()) {
            // A failed commit can still change the in-memory value seen by the reporting helper.
            preferences.edit().putString(MAC_KEY, previous).commit()
            error("Unable to persist device MAC")
        }
    }
}

/** Optional reporting value. Blocking: call on IO; null means absent, invalid, or unreadable. */
fun readPersistedDeviceMacAddress(context: Context): String? = DeviceMacAddressStore(context).read()?.address

/** IO only. Readable interface addresses may be randomized; never imports provider/Stalker MACs. */
fun readDeviceMacAddressForReport(context: Context): DeviceMacAddress? =
    DeviceMacAddressStore(context).read() ?: readDeviceNetworkDetails(context).readableMac
