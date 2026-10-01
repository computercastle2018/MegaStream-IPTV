package com.MegaStream.app.playback.gate

import com.MegaStream.domain.manager.RecordingManager
import com.MegaStream.domain.model.RecordingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Authorizes at the invocation boundary, not from a potentially stale observed decision. */
inline fun <T> PlaybackGate.withPlaybackAdmission(
    onBlocked: (PlaybackGateVerdict.Blocked) -> Unit = {},
    action: () -> T,
): T? = when (val verdict = checkNow()) {
    PlaybackGateVerdict.Allowed -> action()
    is PlaybackGateVerdict.Blocked -> {
        try {
            onBlocked(verdict)
        } finally {
            reportBlocked(verdict)
        }
        null
    }
}

/** App-owned observation; AlarmManager capture without a live consumer remains outside this boundary. */
fun PlaybackGate.stopRecordingsWhenBlocked(scope: CoroutineScope, recordings: RecordingManager): Job = scope.launch {
    combine(decision, recordings.observeRecordingItems()) { verdict, items -> verdict to items }
        .collect { (verdict, items) ->
            if (verdict is PlaybackGateVerdict.Blocked) {
                val active = items.filter { it.status == RecordingStatus.RECORDING }
                active.forEach { recordings.stopRecording(it.id) }
                if (active.isNotEmpty()) reportBlocked(verdict)
            }
        }
}
