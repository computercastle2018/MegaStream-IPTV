package com.MegaStream.app.update

/** Pure fail-closed gate. Inputs are observed from the APK/installed package, never version labels. */
internal object ApkInstallValidation {
    data class Evidence(
        val expectedPackage: String,
        val metadataPackage: String?,
        val candidatePackage: String?,
        val expectedVersionCode: Int?,
        val candidateVersionCode: Long?,
        val installedVersionCode: Long?,
        val persistedSha256: String?,
        val actualSha256: String?,
        val argumentSha256: String? = null,
        val installedCertificates: Set<String> = emptySet(),
        val candidateCertificates: Set<String> = emptySet(),
        val expectedCertificate: String? = null,
        val deviceSdk: Int,
        val candidateMinSdk: Int?,
        val expectedMinSdk: Int? = null,
    )

    /** Returns a safe, digest-free explanation, or null when all checks pass. */
    fun failure(evidence: Evidence): String? = with(evidence) {
        val expectedHash = digest(persistedSha256)
            ?: return "Persisted update integrity metadata is missing or invalid"
        if (argumentSha256 != null && digest(argumentSha256) != expectedHash) {
            return "Update integrity metadata conflicts with the requested checksum"
        }
        if (digest(actualSha256) != expectedHash) return "Downloaded update failed integrity verification"
        if (expectedPackage.isBlank() || metadataPackage != expectedPackage || candidatePackage != expectedPackage) {
            return "Update package identity could not be verified"
        }
        if (expectedVersionCode == null || expectedVersionCode <= 0 ||
            candidateVersionCode == null || candidateVersionCode != expectedVersionCode.toLong() || installedVersionCode == null ||
            installedVersionCode < 0 || candidateVersionCode <= installedVersionCode
        ) return "Update version must exactly match metadata and be newer than the installed app"
        if (candidateMinSdk == null || candidateMinSdk !in 1..deviceSdk ||
            (expectedMinSdk != null && expectedMinSdk != candidateMinSdk)) {
            return "Update minimum Android version could not be verified"
        }
        val installed = installedCertificates.mapNotNull(::digest).toSet()
        val candidate = candidateCertificates.mapNotNull(::digest).toSet()
        if (installed.isEmpty() || candidate.isEmpty() || installed.intersect(candidate).isEmpty()) {
            return "Update signing certificate could not be verified against the installed app"
        }
        if (expectedCertificate != null && digest(expectedCertificate) !in candidate) {
            return "Update signing certificate does not match release metadata"
        }
        null
    }

    fun isValidDigest(value: String?): Boolean = digest(value) != null

    private fun digest(value: String?): String? = value
        ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }?.lowercase()
}
