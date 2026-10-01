package com.MegaStream.domain.licensing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EntitlementEvaluatorTest {
    private val evaluator = EntitlementEvaluator()
    private val lease = OfflineLease(
        issuer = "issuer",
        audience = "audience",
        installationId = "installation",
        credentialBinding = "binding",
        issuedAtEpochSeconds = 1_000,
        notBeforeEpochSeconds = 1_000,
        expiresAtEpochSeconds = 1_000_000,
        licenseStartsAtEpochSeconds = 500,
        licenseEndsAtEpochSeconds = 800_000,
        licenseId = "license",
        licenseRevision = 1,
        tokenId = "token",
        policyVersion = 1,
        decision = LicenseAccessState.ALLOWED
    )

    @Test
    fun `all authenticated denials override missing and invalid lease or clock`() {
        LicenseAccessState.values().filter { it != LicenseAccessState.ALLOWED }.forEach { denial ->
            listOf(null, lease, lease.copy(expiresAtEpochSeconds = 0)).forEach { candidate ->
                listOf(null, 0L, -1L, 1_000L, Long.MAX_VALUE).forEach { now ->
                    assertThat(evaluator.evaluate(candidate, now, denial))
                        .isEqualTo(LicenseAccessDecision(denial))
                }
            }
        }
    }

    @Test
    fun `lease denial overrides invalid time but persisted denial wins over lease denial`() {
        LicenseAccessState.values().filter { it != LicenseAccessState.ALLOWED }.forEach { denial ->
            val deniedLease = lease.copy(decision = denial, expiresAtEpochSeconds = 0)
            assertThat(evaluator.evaluate(deniedLease, null))
                .isEqualTo(LicenseAccessDecision(denial))
            assertThat(evaluator.evaluate(deniedLease, null, LicenseAccessState.ALLOWED))
                .isEqualTo(LicenseAccessDecision(denial))
            assertThat(evaluator.evaluate(deniedLease, null, LicenseAccessState.REVOKED))
                .isEqualTo(LicenseAccessDecision(LicenseAccessState.REVOKED))
        }
    }

    @Test
    fun `authenticated allowed never grants access without valid lease and clock`() {
        assertThat(evaluator.evaluate(null, 1_000, LicenseAccessState.ALLOWED).state)
            .isEqualTo(LicenseAccessState.VERIFICATION_REQUIRED)
        listOf(null, 0L, -1L, Long.MIN_VALUE).forEach { now ->
            assertThat(evaluator.evaluate(lease, now, LicenseAccessState.ALLOWED).state)
                .isEqualTo(LicenseAccessState.VERIFICATION_REQUIRED)
        }
    }

    @Test
    fun `nonpositive lease times and empty or reversed intervals require verification`() {
        val nonpositiveTimes = listOf(0L, -1L, Long.MIN_VALUE).flatMap { invalid ->
            listOf(
                lease.copy(issuedAtEpochSeconds = invalid),
                lease.copy(notBeforeEpochSeconds = invalid),
                lease.copy(expiresAtEpochSeconds = invalid),
                lease.copy(licenseStartsAtEpochSeconds = invalid),
                lease.copy(licenseEndsAtEpochSeconds = invalid)
            )
        }
        val invalidIntervals = listOf(
            lease.copy(notBeforeEpochSeconds = lease.expiresAtEpochSeconds),
            lease.copy(notBeforeEpochSeconds = lease.expiresAtEpochSeconds + 1),
            lease.copy(issuedAtEpochSeconds = lease.expiresAtEpochSeconds),
            lease.copy(issuedAtEpochSeconds = lease.expiresAtEpochSeconds + 1),
            lease.copy(licenseStartsAtEpochSeconds = lease.licenseEndsAtEpochSeconds),
            lease.copy(licenseStartsAtEpochSeconds = lease.licenseEndsAtEpochSeconds + 1)
        )
        (nonpositiveTimes + invalidIntervals).forEach { invalidLease ->
            assertThat(evaluator.evaluate(invalidLease, 1_000).state)
                .isEqualTo(LicenseAccessState.VERIFICATION_REQUIRED)
        }
    }

    @Test
    fun `future issuance fails closed even when license or lease has not started`() {
        listOf(lease, lease.copy(notBeforeEpochSeconds = 2_000, licenseStartsAtEpochSeconds = 2_000))
            .forEach { candidate ->
                assertThat(evaluator.evaluate(candidate, 999).state)
                    .isEqualTo(LicenseAccessState.VERIFICATION_REQUIRED)
            }
        assertThat(evaluator.evaluate(lease, 1_000).allowed).isTrue()
    }

    @Test
    fun `lease and license start boundaries are inclusive`() {
        listOf(
            lease.copy(notBeforeEpochSeconds = 2_000),
            lease.copy(licenseStartsAtEpochSeconds = 2_000)
        ).forEach { candidate ->
            assertThat(evaluator.evaluate(candidate, 1_999).state).isEqualTo(LicenseAccessState.NOT_STARTED)
            assertThat(evaluator.evaluate(candidate, 2_000).state).isEqualTo(LicenseAccessState.ALLOWED)
        }
    }

    @Test
    fun `issuance seventy two hour cap and lease expiration cap are exclusive`() {
        listOf(
            lease to 260_200L,
            lease.copy(expiresAtEpochSeconds = 2_000) to 2_000L
        ).forEach { (candidate, deadline) ->
            val before = evaluator.evaluate(candidate, deadline - 1)
            assertThat(before.allowed).isTrue()
            assertThat(before.offlineDeadlineEpochSeconds).isEqualTo(deadline)
            listOf(deadline, deadline + 1).forEach { now ->
                val denied = evaluator.evaluate(candidate, now)
                assertThat(denied.state).isEqualTo(LicenseAccessState.VERIFICATION_REQUIRED)
                assertThat(denied.allowed).isFalse()
                assertThat(denied.offlineDeadlineEpochSeconds).isEqualTo(deadline)
            }
        }
    }

    @Test
    fun `signed allowed lease continues at and after license end within offline grace`() {
        val endingLease = lease.copy(licenseEndsAtEpochSeconds = 2_000)
        listOf(1_999L, 2_000L, 2_001L, 260_199L).forEach { now ->
            assertThat(evaluator.evaluate(endingLease, now).state).isEqualTo(LicenseAccessState.ALLOWED)
        }
        assertThat(evaluator.evaluate(endingLease, 260_200).state)
            .isEqualTo(LicenseAccessState.VERIFICATION_REQUIRED)
        assertThat(evaluator.evaluate(endingLease, 2_000, LicenseAccessState.EXPIRED).state)
            .isEqualTo(LicenseAccessState.EXPIRED)
    }

    @Test
    fun `license end plus seventy two hours caps lease issued after license end`() {
        val renewedLease = lease.copy(issuedAtEpochSeconds = 3_000, licenseEndsAtEpochSeconds = 2_000)
        val before = evaluator.evaluate(renewedLease, 261_199)
        assertThat(before.allowed).isTrue()
        assertThat(before.offlineDeadlineEpochSeconds).isEqualTo(261_200L)
        assertThat(evaluator.evaluate(renewedLease, 261_200).state)
            .isEqualTo(LicenseAccessState.VERIFICATION_REQUIRED)
    }

    @Test
    fun `expired offline cap cannot be bypassed by a future start`() {
        val futureLease = lease.copy(licenseStartsAtEpochSeconds = 300_000)
        assertThat(evaluator.evaluate(futureLease, 260_200).state)
            .isEqualTo(LicenseAccessState.VERIFICATION_REQUIRED)
    }

    @Test
    fun `overflow in either grace addition requires verification even with earlier expiration cap`() {
        listOf(
            lease.copy(issuedAtEpochSeconds = Long.MAX_VALUE - 1, expiresAtEpochSeconds = Long.MAX_VALUE),
            lease.copy(licenseEndsAtEpochSeconds = Long.MAX_VALUE)
        ).forEach { candidate ->
            assertThat(evaluator.evaluate(candidate, candidate.issuedAtEpochSeconds).state)
                .isEqualTo(LicenseAccessState.VERIFICATION_REQUIRED)
        }
    }

    @Test
    fun `exact maximum deadline does not overflow and remains exclusive`() {
        val maximumLease = lease.copy(
            issuedAtEpochSeconds = Long.MAX_VALUE - 259_200,
            licenseStartsAtEpochSeconds = Long.MAX_VALUE - 259_201,
            licenseEndsAtEpochSeconds = Long.MAX_VALUE - 259_200,
            expiresAtEpochSeconds = Long.MAX_VALUE
        )
        val before = evaluator.evaluate(maximumLease, Long.MAX_VALUE - 1)
        assertThat(before.allowed).isTrue()
        assertThat(before.offlineDeadlineEpochSeconds).isEqualTo(Long.MAX_VALUE)
        assertThat(evaluator.evaluate(maximumLease, Long.MAX_VALUE).state)
            .isEqualTo(LicenseAccessState.VERIFICATION_REQUIRED)
    }

    @Test
    fun `allowed property is false for every denial state`() {
        LicenseAccessState.values().forEach { state ->
            assertThat(LicenseAccessDecision(state).allowed).isEqualTo(state == LicenseAccessState.ALLOWED)
        }
    }
}
