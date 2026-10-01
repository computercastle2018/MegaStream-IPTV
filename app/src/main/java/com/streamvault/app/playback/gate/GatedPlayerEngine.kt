package com.MegaStream.app.playback.gate

import android.net.Uri
import com.MegaStream.domain.model.AudioOutputPreference
import com.MegaStream.domain.model.DecoderMode
import com.MegaStream.domain.model.PlayerSurfaceMode
import com.MegaStream.domain.model.StreamInfo
import com.MegaStream.player.Media3PlayerEngine
import com.MegaStream.player.PlayerEngine
import com.MegaStream.player.timeshift.TimeshiftConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Keeps authorization outside the media engine, including its retry and timeshift entry points. */
class GatedPlayerEngine(
    private val delegate: PlayerEngine,
    private val gate: PlaybackGate,
    scope: CoroutineScope,
) : PlayerEngine by delegate {
    private var active = false
    private var released = false
    private val observation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        gate.decision.collect { verdict ->
            if (verdict is PlaybackGateVerdict.Blocked && active && !released) block(verdict)
        }
    }

    override fun prepare(streamInfo: StreamInfo) = protect { delegate.prepare(streamInfo) }
    override fun play() = protect { delegate.play() }
    override fun renewStreamUrl(streamInfo: StreamInfo) = protect { delegate.renewStreamUrl(streamInfo) }
    override fun preload(streamInfo: StreamInfo?) {
        if (streamInfo == null) delegate.preload(null) else protect { delegate.preload(streamInfo) }
    }
    override fun startLiveTimeshift(streamInfo: StreamInfo, channelKey: String, config: TimeshiftConfig) =
        protect { delegate.startLiveTimeshift(streamInfo, channelKey, config) }
    override fun resumeTimeshift() = protect { delegate.resumeTimeshift() }
    override fun pauseTimeshift() = protect { delegate.pauseTimeshift() }
    // Despite its name this method can prepare and auto-play the original live stream.
    override fun stopLiveTimeshift() = protect { delegate.stopLiveTimeshift() }
    override fun seekToLiveEdge() = protect { delegate.seekToLiveEdge() }
    override fun seekTo(positionMs: Long) = protect { delegate.seekTo(positionMs) }
    override fun seekForward(ms: Long) = protect { delegate.seekForward(ms) }
    override fun seekBackward(ms: Long) = protect { delegate.seekBackward(ms) }
    override fun setDecoderMode(mode: DecoderMode) = configure { delegate.setDecoderMode(mode) }
    override fun setSurfaceMode(mode: PlayerSurfaceMode) = configure { delegate.setSurfaceMode(mode) }
    override fun setAudioOutputPreference(preference: AudioOutputPreference) =
        configure { delegate.setAudioOutputPreference(preference) }
    override fun addExternalSubtitle(subtitleUri: Uri, language: String) =
        protect { delegate.addExternalSubtitle(subtitleUri, language) }

    /** Preview handoff changes settings without exposing an unguarded engine. */
    fun configureForFullScreen(mediaSessionEnabled: Boolean) {
        when (delegate) {
            is GatedPlayerEngine -> delegate.configureForFullScreen(mediaSessionEnabled)
            is Media3PlayerEngine -> {
                delegate.bypassAudioFocus = false
                delegate.constrainResolutionForMultiView = false
                delegate.setMediaSessionEnabled(mediaSessionEnabled)
            }
            else -> delegate.setMediaSessionEnabled(mediaSessionEnabled)
        }
    }

    override fun resetForReuse() {
        active = false
        delegate.resetForReuse()
    }

    override fun release() {
        if (released) return
        released = true
        active = false
        observation.cancel()
        delegate.release()
    }

    private inline fun configure(action: () -> Unit) {
        if (released) return
        if (active) protect(action) else action()
    }

    private inline fun protect(action: () -> Unit) {
        if (released) return
        when (val verdict = gate.checkNow()) {
            PlaybackGateVerdict.Allowed -> {
                active = true
                action()
            }
            is PlaybackGateVerdict.Blocked -> block(verdict)
        }
    }

    private fun block(verdict: PlaybackGateVerdict.Blocked) {
        val hadResources = active
        active = false
        try {
            delegate.stop()
        } finally {
            try {
                // stop() alone leaves timeshift capture and pending asynchronous preparations alive.
                if (hadResources) delegate.resetForReuse()
            } finally {
                gate.reportBlocked(verdict)
            }
        }
    }
}
