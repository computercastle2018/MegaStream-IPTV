package com.MegaStream.domain.diagnostics

/**
 * Pure, conservative attribution of the previous process's termination.
 *
 * All timestamps must use the same epoch-millisecond clock. [PreviousSessionMarker.endedAtMillis]
 * is an optional authoritative upper bound, not merely the last heartbeat. The recovery timestamp
 * is the time the current process started recovering the marker. Clock regressions, corrupt marker
 * bounds, and already-consumed markers produce Unknown rather than attributing another process.
 * No Android API is needed, so marker-only classification also works before API 30.
 */
class SessionTerminationClassifier {
    enum class Termination {
        Clean, JavaCrash, Anr, Oom, LowMemoryKill, NativeCrash, Signal,
        UserRequested, SystemKill, Unknown,
    }

    enum class EvidenceStrength {
        None,
        /** An unresolved watchdog observation, not a system-confirmed ANR. */
        Suspected,
        /** Explicitly persisted by the application; not independently confirmed by the OS. */
        Recorded,
        /** An attributable system exit record directly identifies this category. */
        Confirmed,
    }

    enum class EvidenceSource { None, SessionMarker, SystemExit, Combined }

    enum class MarkerState { Running, Clean, JavaCrash, Oom, WatchdogTimeout, WatchdogRecovered }

    data class PreviousSessionMarker(
        val pid: Int,
        val startedAtMillis: Long,
        val recordedAtMillis: Long,
        val state: MarkerState,
        val endedAtMillis: Long? = null,
        /** Non-null means a previous recovery already consumed this marker. */
        val recoveredAtMillis: Long? = null,
    )

    /** Values mirror ApplicationExitInfo without linking the domain module to Android. */
    data class ExitReasonSnapshot(
        val pid: Int,
        val timestampMillis: Long,
        val reason: Int,
        /** Exit code for EXIT_SELF, or signal number for SIGNALED. */
        val status: Int = 0,
    )

    data class Result(
        val termination: Termination,
        val evidenceStrength: EvidenceStrength,
        val evidenceSource: EvidenceSource,
    )

    fun classify(
        previous: PreviousSessionMarker?,
        exit: ExitReasonSnapshot? = null,
        recoveryTimestampMillis: Long,
    ): Result {
        if (previous == null || !previous.isValidAt(recoveryTimestampMillis)) return unknown()
        val matchingExit = exit?.takeIf {
            it.pid == previous.pid &&
                it.timestampMillis >= previous.recordedAtMillis &&
                it.timestampMillis <= (previous.endedAtMillis ?: recoveryTimestampMillis) &&
                it.timestampMillis <= recoveryTimestampMillis
        }
        if (matchingExit != null) {
            // A generic Java crash can be refined by an explicit OOM marker, but an OS low-memory
            // kill or signal is a different termination mechanism and must not be relabelled OOM.
            if (matchingExit.reason == REASON_CRASH && previous.state == MarkerState.Oom) {
                return Result(Termination.Oom, EvidenceStrength.Recorded, EvidenceSource.Combined)
            }
            val systemTermination = matchingExit.termination()
            if (systemTermination != Termination.Unknown) {
                return Result(systemTermination, EvidenceStrength.Confirmed, EvidenceSource.SystemExit)
            }
            // An unsuccessful explicit exit contradicts a clean marker, even if no more precise
            // category exists.
            if (previous.state == MarkerState.Clean &&
                matchingExit.reason == REASON_EXIT_SELF && matchingExit.status != 0
            ) return unknown(EvidenceSource.SystemExit)
        }
        return previous.fallback()
    }

    private fun PreviousSessionMarker.isValidAt(recovery: Long): Boolean =
        pid > 0 && startedAtMillis >= 0 && recordedAtMillis >= startedAtMillis &&
            recovery >= recordedAtMillis && recoveredAtMillis == null &&
            (endedAtMillis == null || endedAtMillis in recordedAtMillis..recovery)

    private fun PreviousSessionMarker.fallback(): Result = when (state) {
        MarkerState.Clean -> recorded(Termination.Clean)
        MarkerState.JavaCrash -> recorded(Termination.JavaCrash)
        MarkerState.Oom -> recorded(Termination.Oom)
        MarkerState.WatchdogTimeout ->
            Result(Termination.Anr, EvidenceStrength.Suspected, EvidenceSource.SessionMarker)
        MarkerState.Running, MarkerState.WatchdogRecovered -> unknown()
    }

    private fun ExitReasonSnapshot.termination(): Termination = when (reason) {
        REASON_EXIT_SELF -> if (status == 0) Termination.Clean else Termination.Unknown
        REASON_SIGNALED -> Termination.Signal
        REASON_LOW_MEMORY -> Termination.LowMemoryKill
        REASON_CRASH -> Termination.JavaCrash
        REASON_CRASH_NATIVE -> Termination.NativeCrash
        REASON_ANR -> Termination.Anr
        REASON_USER_REQUESTED, REASON_USER_STOPPED -> Termination.UserRequested
        REASON_INITIALIZATION_FAILURE, REASON_PERMISSION_CHANGE, REASON_EXCESSIVE_RESOURCE_USAGE,
        REASON_DEPENDENCY_DIED, REASON_FREEZER, REASON_PACKAGE_STATE_CHANGE,
        REASON_PACKAGE_UPDATED -> Termination.SystemKill
        // UNKNOWN (0), OTHER (13), and future platform values do
        // not identify any of our specific categories. Preserve any usable marker evidence.
        else -> Termination.Unknown
    }

    private fun recorded(termination: Termination) =
        Result(termination, EvidenceStrength.Recorded, EvidenceSource.SessionMarker)

    private fun unknown(source: EvidenceSource = EvidenceSource.None) =
        Result(Termination.Unknown, EvidenceStrength.None, source)

    private companion object {
        const val REASON_EXIT_SELF = 1
        const val REASON_SIGNALED = 2
        const val REASON_LOW_MEMORY = 3
        const val REASON_CRASH = 4
        const val REASON_CRASH_NATIVE = 5
        const val REASON_ANR = 6
        const val REASON_INITIALIZATION_FAILURE = 7
        const val REASON_PERMISSION_CHANGE = 8
        const val REASON_EXCESSIVE_RESOURCE_USAGE = 9
        const val REASON_USER_REQUESTED = 10
        const val REASON_USER_STOPPED = 11
        const val REASON_DEPENDENCY_DIED = 12
        const val REASON_FREEZER = 14
        const val REASON_PACKAGE_STATE_CHANGE = 15
        const val REASON_PACKAGE_UPDATED = 16
    }
}
