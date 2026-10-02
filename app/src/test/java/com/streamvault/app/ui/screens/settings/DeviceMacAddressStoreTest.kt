package com.MegaStream.app.ui.screens.settings

import android.content.SharedPreferences
import com.MegaStream.data.security.CredentialCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class DeviceMacAddressStoreTest {
    @Test
    fun explicitlySavedMacIsEncryptedManualAndCanBeRemoved() {
        val f = PreferenceBoundary()
        val store = DeviceMacAddressStore(f.preferences, f.crypto)
        assertNull(store.read())
        store.saveManual("00-1a-79-12-34-56")
        assertEquals("encrypted:00:1A:79:12:34:56", f.value)
        assertEquals(DeviceMacAddress("00:1A:79:12:34:56", DeviceMacSource.MANUAL), store.read())
        store.clear()
        assertNull(store.read())
    }

    @Test
    fun failedDiskCommitRestoresPreviousReportingValue() {
        val f = PreferenceBoundary()
        val store = DeviceMacAddressStore(f.preferences, f.crypto)
        store.saveManual("00:1A:79:12:34:56")
        f.failCommit = true
        assertThrows(IllegalStateException::class.java) { store.saveManual("00:1A:79:12:34:57") }
        assertEquals("00:1A:79:12:34:56", store.read()?.address)
        assertThrows(IllegalStateException::class.java) { store.clear() }
        assertEquals("00:1A:79:12:34:56", store.read()?.address)
    }

    @Test
    fun invalidInputAndFailedEncryptionNeverReplaceSavedValue() {
        val f = PreferenceBoundary()
        val store = DeviceMacAddressStore(f.preferences, f.crypto)
        store.saveManual("00:1A:79:12:34:56")
        assertThrows(IllegalArgumentException::class.java) { store.saveManual("02:00:00:00:00:00") }
        f.failCrypto = true
        assertThrows(SecurityException::class.java) { store.saveManual("00:1A:79:12:34:57") }
        assertEquals("encrypted:00:1A:79:12:34:56", f.value)
        assertNull(store.read())
    }

    // Android's boundary double deliberately models commit() mutating memory even on failure.
    private class PreferenceBoundary {
        var value: String? = null
        var pending: String? = null
        var failCommit = false
        var failCrypto = false
        val preferences: SharedPreferences = mock()
        private val editor: SharedPreferences.Editor = mock()
        val crypto = object : CredentialCrypto {
            override fun encryptIfNeeded(value: String): String {
                if (failCrypto) throw SecurityException("Keystore unavailable")
                return "encrypted:$value"
            }
            override fun decryptIfNeeded(value: String): String {
                if (failCrypto) throw SecurityException("Keystore unavailable")
                return value.removePrefix("encrypted:")
            }
        }
        init {
            whenever(preferences.getString(any(), anyOrNull())).thenAnswer { value }
            whenever(preferences.edit()).thenReturn(editor)
            doAnswer { pending = it.getArgument(1); editor }.whenever(editor).putString(any(), anyOrNull())
            whenever(editor.commit()).thenAnswer { value = pending; !failCommit }
        }
    }
}
