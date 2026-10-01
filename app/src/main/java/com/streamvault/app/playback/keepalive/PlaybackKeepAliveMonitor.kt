package com.MegaStream.app.playback.keepalive

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.MegaStream.player.PlayerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

private const val MONITOR_KEY = "com.MegaStream.app.playback.keepalive.monitor"

/** Call from a committed screen effect, never from remember's calculation. */
internal fun ViewModel.getOrCreatePlaybackKeepAliveMonitor(
    context: Context,
    engines: StateFlow<PlayerEngine>,
    hasCurrentStream: () -> Boolean
): PlaybackKeepAliveMonitor = getCloseable<PlaybackKeepAliveMonitor>(MONITOR_KEY)
    ?: PlaybackKeepAliveMonitor(
        viewModelScope,
        engines,
        hasCurrentStream,
        PlaybackKeepAliveController(AndroidPlaybackKeepAliveRequests(context.applicationContext))
    ).also { addCloseable(MONITOR_KEY, it) }

/**
 * ViewModel-owned rather than screen-lifecycle-collected: terminal/reset emissions still stop the
 * service while backgrounded or displaying the guide. Only booleans/tokens outlive screen detach.
 * All methods and the supplied scope are confined to the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlaybackKeepAliveMonitor(
    scope: CoroutineScope,
    private val engines: StateFlow<PlayerEngine>,
    private val hasCurrentStream: () -> Boolean,
    private val controller: PlaybackKeepAliveController
) : AutoCloseable {
    private data class Attachment(var foreground: Boolean, var hasScreenStream: Boolean)
    private val attachments = mutableMapOf<Any, Attachment>()
    private var closed = false
    private val collection = scope.launch {
        engines.flatMapLatest { engine ->
            combine(engine.playbackState, engine.isPlaying) { _, _ -> Unit }
        }.collect {
            // Read the actual engine and current logical stream together, not a stale UI snapshot.
            refresh()
        }
    }

    fun attach(foregroundEligible: Boolean, hasScreenStream: Boolean): Any {
        val token = Any()
        if (!closed) {
            attachments[token] = Attachment(foregroundEligible, hasScreenStream)
            refresh()
        }
        return token
    }

    fun foregroundChanged(token: Any, foregroundEligible: Boolean) {
        val attachment = attachments[token] ?: return
        attachment.foreground = foregroundEligible
        // Synchronous ON_STOP handling closes the cold-start window before any coroutine resumes.
        refresh()
    }

    fun screenStreamChanged(token: Any, hasScreenStream: Boolean) {
        val attachment = attachments[token] ?: return
        if (attachment.hasScreenStream == hasScreenStream) return
        attachment.hasScreenStream = hasScreenStream
        // Route changes trigger a read; the engine's current logical URL remains authoritative.
        refresh()
    }

    fun detach(token: Any) {
        attachments.remove(token)
        // Detach does not release playback. An existing successful request may carry on.
        refresh()
    }

    fun keepPlayingInBackground(): Boolean =
        !closed && controller.serviceRequested && snapshot().hasActivePlayback()

    private fun snapshot(): PlaybackKeepAliveInput {
        val engine = engines.value
        return PlaybackKeepAliveInput(
            hasStream = hasCurrentStream(),
            playbackState = engine.playbackState.value,
            isPlaying = engine.isPlaying.value,
            screenAttached = attachments.isNotEmpty(),
            foregroundEligible = attachments.values.any { it.foreground },
            released = closed
        )
    }

    private fun refresh() {
        if (!closed) controller.update(snapshot())
    }

    override fun close() {
        if (closed) return
        closed = true
        attachments.clear()
        collection.cancel()
        // Close independently of the canceled scope and the engine reset in ViewModel.onCleared.
        controller.close()
    }
}
