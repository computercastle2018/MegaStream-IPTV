package com.MegaStream.domain.licensing

enum class LicenseAccessState {
    ALLOWED,
    UNLICENSED,
    NOT_STARTED,
    EXPIRED,
    SUSPENDED,
    REVOKED,
    INSTALLATION_DISABLED,
    VERIFICATION_REQUIRED
}
