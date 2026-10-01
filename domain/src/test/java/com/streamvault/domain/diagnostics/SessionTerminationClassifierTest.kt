package com.MegaStream.domain.diagnostics

import com.MegaStream.domain.diagnostics.SessionTerminationClassifier.EvidenceSource
import com.MegaStream.domain.diagnostics.SessionTerminationClassifier.EvidenceStrength
import com.MegaStream.domain.diagnostics.SessionTerminationClassifier.ExitReasonSnapshot
import com.MegaStream.domain.diagnostics.SessionTerminationClassifier.MarkerState
import com.MegaStream.domain.diagnostics.SessionTerminationClassifier.PreviousSessionMarker
import com.MegaStream.domain.diagnostics.SessionTerminationClassifier.Result
import com.MegaStream.domain.diagnostics.SessionTerminationClassifier.Termination
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTerminationClassifierTest {
    private val classifier = SessionTerminationClassifier()
    private val marker = PreviousSessionMarker(
        pid = 42,
        startedAtMillis = 100,
        recordedAtMillis = 200,
        state = MarkerState.Running,
    )

    @Test
    fun `every platform reason maps to its own termination category`() {
        val expected = mapOf(
            0 to Termination.Unknown,
            1 to Termination.Clean,
            2 to Termination.Signal,
            3 to Termination.LowMemoryKill,
            4 to Termination.JavaCrash,
            5 to Termination.NativeCrash,
            6 to Termination.Anr,
            7 to Termination.SystemKill,
            8 to Termination.SystemKill,
            9 to Termination.SystemKill,
            10 to Termination.UserRequested,
            11 to Termination.UserRequested,
            12 to Termination.SystemKill,
            13 to Termination.Unknown,
            14 to Termination.SystemKill,
            15 to Termination.SystemKill,
            16 to Termination.SystemKill,
            -1 to Termination.Unknown,
            Int.MAX_VALUE to Termination.Unknown,
        )
        expected.forEach { (reason, termination) ->
            val result = classify(exit = exit(reason))
            val strength = if (termination == Termination.Unknown) EvidenceStrength.None
                else EvidenceStrength.Confirmed
            val source = if (termination == Termination.Unknown) EvidenceSource.None
                else EvidenceSource.SystemExit
            assertEquals("reason=$reason", Result(termination, strength, source), result)
        }
    }

    @Test
    fun `missing previous session never attributes an arbitrary system exit`() {
        listOf(null, exit(1), exit(4), exit(6), exit(3)).forEach { exit ->
            assertUnknown(classify(previous = null, exit = exit))
        }
    }

    @Test
    fun `marker-only recovery works without an Android exit record`() {
        val expected = mapOf(
            MarkerState.Running to Pair(Termination.Unknown, EvidenceStrength.None),
            MarkerState.Clean to Pair(Termination.Clean, EvidenceStrength.Recorded),
            MarkerState.JavaCrash to Pair(Termination.JavaCrash, EvidenceStrength.Recorded),
            MarkerState.Oom to Pair(Termination.Oom, EvidenceStrength.Recorded),
            MarkerState.WatchdogTimeout to Pair(Termination.Anr, EvidenceStrength.Suspected),
            MarkerState.WatchdogRecovered to Pair(Termination.Unknown, EvidenceStrength.None),
        )
        expected.forEach { (state, expectedResult) ->
            val source = if (expectedResult.first == Termination.Unknown) EvidenceSource.None
                else EvidenceSource.SessionMarker
            assertEquals(
                "state=$state",
                Result(expectedResult.first, expectedResult.second, source),
                classify(marker.copy(state = state)),
            )
        }
    }

    @Test
    fun `invalid or already recovered markers cannot supply fallback or system attribution`() {
        val invalidMarkers = listOf(
            marker.copy(pid = 0),
            marker.copy(pid = -1),
            marker.copy(startedAtMillis = -1),
            marker.copy(startedAtMillis = 201),
            marker.copy(recordedAtMillis = 401),
            marker.copy(endedAtMillis = 199),
            marker.copy(endedAtMillis = 401),
            marker.copy(recoveredAtMillis = 350),
            marker.copy(recoveredAtMillis = 0),
            marker.copy(recoveredAtMillis = 500),
        )
        invalidMarkers.forEach { invalid ->
            listOf(null, exit(4)).forEach { exit ->
                assertUnknown(classify(invalid.copy(state = MarkerState.Oom), exit))
            }
        }
        assertUnknown(classify(marker.copy(state = MarkerState.Clean), recovery = 199))
        assertUnknown(classify(marker.copy(state = MarkerState.Clean), recovery = -1))
    }

    @Test
    fun `other PID stale and future exits are ignored without losing valid marker evidence`() {
        val bounded = marker.copy(endedAtMillis = 350)
        val unrelatedExits = listOf(
            exit(4).copy(pid = 43),
            exit(4).copy(pid = 0),
            exit(4).copy(timestampMillis = -1),
            exit(4).copy(timestampMillis = 99),
            exit(4).copy(timestampMillis = 199),
            exit(4).copy(timestampMillis = 351),
            exit(4).copy(timestampMillis = 401),
            exit(4).copy(timestampMillis = Long.MAX_VALUE),
        )
        unrelatedExits.forEach { exit ->
            assertUnknown(classify(bounded, exit))
            assertEquals(
                "exit=$exit",
                Result(Termination.Oom, EvidenceStrength.Recorded, EvidenceSource.SessionMarker),
                classify(bounded.copy(state = MarkerState.Oom), exit),
            )
        }
        // An absent end bound must still not admit a future record.
        assertUnknown(classify(exit = exit(4).copy(timestampMillis = 401)))
    }

    @Test
    fun `inclusive temporal boundaries admit matching exits without timestamp arithmetic overflow`() {
        val cases = listOf(
            marker.copy(recordedAtMillis = 100) to exit(6).copy(timestampMillis = 100),
            marker to exit(6).copy(timestampMillis = 200),
            marker.copy(endedAtMillis = 350) to exit(6).copy(timestampMillis = 350),
            marker to exit(6).copy(timestampMillis = 400),
        )
        cases.forEach { (previous, exit) ->
            assertEquals(Termination.Anr, classify(previous, exit).termination)
        }
        assertEquals(
            Termination.Anr,
            classify(
                marker.copy(startedAtMillis = Long.MAX_VALUE, recordedAtMillis = Long.MAX_VALUE),
                exit(6).copy(timestampMillis = Long.MAX_VALUE),
                recovery = Long.MAX_VALUE,
            ).termination,
        )
    }

    @Test
    fun `system evidence supersedes a contradictory clean marker`() {
        val expected = mapOf(
            2 to Termination.Signal,
            3 to Termination.LowMemoryKill,
            4 to Termination.JavaCrash,
            5 to Termination.NativeCrash,
            6 to Termination.Anr,
            7 to Termination.SystemKill,
            10 to Termination.UserRequested,
            15 to Termination.SystemKill,
        )
        expected.forEach { (reason, termination) ->
            assertEquals(
                Result(termination, EvidenceStrength.Confirmed, EvidenceSource.SystemExit),
                classify(marker.copy(state = MarkerState.Clean), exit(reason)),
            )
        }
    }

    @Test
    fun `nonzero self exit never reports clean even when marker claims clean`() {
        listOf(-1, 1, 137, Int.MAX_VALUE).forEach { status ->
            listOf(MarkerState.Running, MarkerState.Clean).forEach { state ->
                val result = classify(marker.copy(state = state), exit(1).copy(status = status))
                assertEquals(Termination.Unknown, result.termination)
                assertEquals(EvidenceStrength.None, result.evidenceStrength)
            }
        }
    }

    @Test
    fun `explicit OOM refines generic Java crash but never relabels LMK or a signal`() {
        assertEquals(
            Result(Termination.Oom, EvidenceStrength.Recorded, EvidenceSource.Combined),
            classify(marker.copy(state = MarkerState.Oom), exit(4)),
        )
        listOf(MarkerState.Running, MarkerState.Oom, MarkerState.JavaCrash).forEach { state ->
            assertEquals(
                Result(Termination.LowMemoryKill, EvidenceStrength.Confirmed, EvidenceSource.SystemExit),
                classify(marker.copy(state = state), exit(3)),
            )
            listOf(9, 11, 15).forEach { signal ->
                assertEquals(
                    Result(Termination.Signal, EvidenceStrength.Confirmed, EvidenceSource.SystemExit),
                    classify(marker.copy(state = state), exit(2).copy(status = signal)),
                )
            }
        }
    }

    @Test
    fun `watchdog is suspected until the matching OS record confirms ANR`() {
        listOf(MarkerState.WatchdogTimeout, MarkerState.WatchdogRecovered).forEach { state ->
            assertEquals(
                Result(Termination.Anr, EvidenceStrength.Confirmed, EvidenceSource.SystemExit),
                classify(marker.copy(state = state), exit(6)),
            )
        }
        val watchdog = marker.copy(state = MarkerState.WatchdogTimeout)
        assertEquals(
            Result(Termination.Clean, EvidenceStrength.Confirmed, EvidenceSource.SystemExit),
            classify(watchdog, exit(1)),
        )
        assertEquals(
            EvidenceStrength.Suspected,
            classify(watchdog, exit(6).copy(pid = 43)).evidenceStrength,
        )
    }

    @Test
    fun `unclassified reasons retain explicit marker fallback without upgrading evidence`() {
        listOf(0, 13, 1000).forEach { reason ->
            assertEquals(
                Result(Termination.JavaCrash, EvidenceStrength.Recorded, EvidenceSource.SessionMarker),
                classify(marker.copy(state = MarkerState.JavaCrash), exit(reason)),
            )
        }
    }

    private fun exit(reason: Int) = ExitReasonSnapshot(pid = 42, timestampMillis = 300, reason = reason)

    private fun classify(
        previous: PreviousSessionMarker? = marker,
        exit: ExitReasonSnapshot? = null,
        recovery: Long = 400,
    ) = classifier.classify(previous, exit, recovery)

    private fun assertUnknown(result: Result) {
        assertEquals(Result(Termination.Unknown, EvidenceStrength.None, EvidenceSource.None), result)
    }
}
