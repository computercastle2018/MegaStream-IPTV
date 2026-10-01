package com.MegaStream.domain.licensing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrustedTimeEvaluatorTest {
    private val evaluator = TrustedTimeEvaluator()
    private val anchor = TrustedTimeAnchor(1_000, 5_000, "boot-1")
    private val reading = ClockReading(1_010, 15_000, "boot-1")

    @Test
    fun `elapsed time advances in whole seconds including exact boundaries`() {
        listOf(0L to 1_000L, 999L to 1_000L, 1_000L to 1_001L, 1_999L to 1_001L).forEach {
            (elapsedDelta, expected) ->
            assertThat(evaluator.evaluate(anchor, reading.copy(
                elapsedRealtimeMillis = anchor.elapsedRealtimeMillis + elapsedDelta,
                wallEpochSeconds = expected
            ))).isEqualTo(expected)
        }
    }

    @Test
    fun `wall forward jump cannot extend trusted time`() {
        assertThat(evaluator.evaluate(anchor, reading.copy(wallEpochSeconds = Long.MAX_VALUE)))
            .isEqualTo(1_010L)
    }

    @Test
    fun `missing anchor and boot uncertainty fail closed`() {
        assertThat(evaluator.evaluate(null, reading)).isNull()
        listOf("", " ", "other-boot").forEach { bootId ->
            assertThat(evaluator.evaluate(anchor, reading.copy(bootId = bootId))).isNull()
        }
        listOf("", " ").forEach { bootId ->
            assertThat(evaluator.evaluate(anchor.copy(bootId = bootId), reading.copy(bootId = bootId)))
                .isNull()
        }
    }

    @Test
    fun `nonpositive epochs and negative elapsed values fail closed`() {
        listOf(0L, -1L, Long.MIN_VALUE).forEach { invalidEpoch ->
            assertThat(evaluator.evaluate(anchor.copy(serverEpochSeconds = invalidEpoch), reading))
                .isNull()
            assertThat(evaluator.evaluate(anchor, reading.copy(wallEpochSeconds = invalidEpoch)))
                .isNull()
        }
        listOf(-1L, Long.MIN_VALUE).forEach { invalidElapsed ->
            assertThat(evaluator.evaluate(anchor.copy(elapsedRealtimeMillis = invalidElapsed), reading))
                .isNull()
            assertThat(evaluator.evaluate(anchor, reading.copy(elapsedRealtimeMillis = invalidElapsed)))
                .isNull()
        }
    }

    @Test
    fun `elapsed regression and wall rollback below anchor or estimate fail closed`() {
        assertThat(evaluator.evaluate(anchor, reading.copy(elapsedRealtimeMillis = 4_999))).isNull()
        listOf(999L, 1_000L, 1_009L).forEach { rollback ->
            assertThat(evaluator.evaluate(anchor, reading.copy(wallEpochSeconds = rollback))).isNull()
        }
    }

    @Test
    fun `zero elapsed is valid and maximum elapsed does not overflow subtraction`() {
        val zeroAnchor = anchor.copy(elapsedRealtimeMillis = 0)
        assertThat(evaluator.evaluate(zeroAnchor, reading.copy(elapsedRealtimeMillis = 0)))
            .isEqualTo(1_000L)
        assertThat(evaluator.evaluate(zeroAnchor, reading.copy(
            elapsedRealtimeMillis = Long.MAX_VALUE,
            wallEpochSeconds = Long.MAX_VALUE
        ))).isEqualTo(1_000L + Long.MAX_VALUE / 1_000)
    }

    @Test
    fun `estimated epoch permits exact maximum but rejects addition overflow`() {
        val nearMaximum = anchor.copy(serverEpochSeconds = Long.MAX_VALUE - 1)
        assertThat(evaluator.evaluate(nearMaximum, reading.copy(
            elapsedRealtimeMillis = 6_000,
            wallEpochSeconds = Long.MAX_VALUE
        ))).isEqualTo(Long.MAX_VALUE)
        assertThat(evaluator.evaluate(nearMaximum, reading.copy(
            elapsedRealtimeMillis = 7_000,
            wallEpochSeconds = Long.MAX_VALUE
        ))).isNull()
    }
}
