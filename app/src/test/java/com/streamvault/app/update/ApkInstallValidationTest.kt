package com.MegaStream.app.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ApkInstallValidationTest {
    private val hash = "ab".repeat(32)
    private val signer = "cd".repeat(32)
    private val other = "ef".repeat(32)
    private val valid = ApkInstallValidation.Evidence(
        expectedPackage = "com.megastream.app",
        metadataPackage = "com.megastream.app",
        candidatePackage = "com.megastream.app",
        expectedVersionCode = 42,
        candidateVersionCode = 42L,
        installedVersionCode = 41L,
        persistedSha256 = hash,
        actualSha256 = hash,
        installedCertificates = setOf(signer),
        candidateCertificates = setOf(signer),
        deviceSdk = 30,
        candidateMinSdk = 27,
    )

    @Test fun verifiedNewerApkAcceptsCaseInsensitiveDigestsAndSignerIntersection() {
        assertNull(ApkInstallValidation.failure(valid))
        assertNull(ApkInstallValidation.failure(valid.copy(
            persistedSha256 = hash.uppercase(),
            argumentSha256 = hash,
            installedCertificates = setOf(other, signer.uppercase()),
            candidateCertificates = setOf(signer),
            expectedCertificate = signer.uppercase()
        )))
    }

    @Test fun suppliedChecksumCannotReplaceOrOverridePersistedIntegrityMetadata() {
        val rejected = listOf(
            valid.copy(persistedSha256 = null, argumentSha256 = hash),
            valid.copy(persistedSha256 = "", argumentSha256 = hash),
            valid.copy(persistedSha256 = "not-a-digest", argumentSha256 = hash),
            valid.copy(persistedSha256 = other, argumentSha256 = hash),
            valid.copy(argumentSha256 = other),
            valid.copy(actualSha256 = other),
            valid.copy(actualSha256 = null),
            valid.copy(argumentSha256 = ""),
            valid.copy(persistedSha256 = " $hash"),
            valid.copy(argumentSha256 = "$hash\n")
        )
        rejected.forEachIndexed { index, evidence ->
            assertNotNull("integrity case $index", ApkInstallValidation.failure(evidence))
        }
    }

    @Test fun bothMetadataAndCandidateMustMatchTheApplicationPackage() {
        listOf(
            valid.copy(metadataPackage = null),
            valid.copy(metadataPackage = ""),
            valid.copy(metadataPackage = "other.package"),
            valid.copy(candidatePackage = null),
            valid.copy(candidatePackage = "other.package"),
            valid.copy(expectedPackage = "")
        ).forEachIndexed { index, evidence ->
            assertNotNull("package case $index", ApkInstallValidation.failure(evidence))
        }
    }

    @Test fun exactExpectedVersionMustBeStrictlyNewerThanObservedInstalledCode() {
        listOf(
            valid.copy(expectedVersionCode = null),
            valid.copy(expectedVersionCode = 0, candidateVersionCode = 0),
            valid.copy(candidateVersionCode = null),
            valid.copy(candidateVersionCode = 43),
            valid.copy(candidateVersionCode = 41),
            valid.copy(installedVersionCode = null),
            valid.copy(installedVersionCode = -1),
            valid.copy(installedVersionCode = 42),
            valid.copy(installedVersionCode = 43),
            valid.copy(installedVersionCode = Long.MAX_VALUE)
        ).forEachIndexed { index, evidence ->
            assertNotNull("version case $index", ApkInstallValidation.failure(evidence))
        }
    }

    @Test fun missingOrDisjointSigningEvidenceAndUnmetPinnedCertificateFailClosed() {
        listOf(
            valid.copy(installedCertificates = emptySet()),
            valid.copy(candidateCertificates = emptySet()),
            valid.copy(installedCertificates = setOf("invalid")),
            valid.copy(candidateCertificates = setOf(other)),
            valid.copy(expectedCertificate = other),
            valid.copy(expectedCertificate = "invalid"),
            valid.copy(expectedCertificate = ""),
            valid.copy(expectedCertificate = " $signer")
        ).forEachIndexed { index, evidence ->
            assertNotNull("certificate case $index", ApkInstallValidation.failure(evidence))
        }
    }

    @Test fun rejectionMessagesNeverExposeChecksumsOrCertificates() {
        listOf(
            valid.copy(actualSha256 = other),
            valid.copy(argumentSha256 = other),
            valid.copy(expectedCertificate = other),
            valid.copy(candidateCertificates = setOf(other))
        ).forEach { evidence ->
            val failure = ApkInstallValidation.failure(evidence)
            assertNotNull(failure)
            listOf(hash, signer, other).forEach { secret ->
                assertFalse(failure!!.contains(secret, ignoreCase = true))
            }
        }
    }

    @Test fun actualApkMinSdkMustBeKnownSupportedAndMatchSuppliedMetadata() {
        assertNull(ApkInstallValidation.failure(valid.copy(expectedMinSdk = 27)))
        for (evidence in listOf(valid.copy(candidateMinSdk = null), valid.copy(candidateMinSdk = 0),
            valid.copy(candidateMinSdk = 31), valid.copy(expectedMinSdk = 28))) {
            assertNotNull(ApkInstallValidation.failure(evidence))
        }
    }
}
