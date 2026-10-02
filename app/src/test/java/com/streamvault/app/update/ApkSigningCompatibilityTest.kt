package com.MegaStream.app.update

import android.content.pm.PackageInfo
import android.content.pm.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.security.MessageDigest

class ApkSigningCompatibilityTest {
    @Suppress("DEPRECATION")
    @Test fun tvPackageManagerWithOnlyLegacySignaturesStillRequiresMatchingCertificate() {
        val bytes = byteArrayOf(1, 2, 3)
        val signature = mock<Signature>()
        whenever(signature.toByteArray()).thenReturn(bytes)
        val info = mock<PackageInfo>()
        info.signatures = arrayOf(signature)
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(setOf(expected), signatureSha256Set(info, 29))
        info.signatures = null
        assertTrue(signatureSha256Set(info, 29).isEmpty())
    }
}
