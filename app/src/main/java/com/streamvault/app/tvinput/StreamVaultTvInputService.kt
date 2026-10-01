package com.MegaStream.app.tvinput

import com.MegaStream.app.playback.gate.PlaybackGate
import com.MegaStream.app.playback.gate.PlaybackGateVerdict
import com.MegaStream.app.playback.gate.withPlaybackAdmission
import kotlinx.coroutines.flow.collect
import android.content.ContentUris
import android.content.Context
import android.media.tv.TvInputManager
import android.media.tv.TvInputService
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.MegaStream.domain.model.Channel
import com.MegaStream.domain.model.StreamInfo
import com.MegaStream.domain.model.StreamType
import com.MegaStream.domain.repository.ChannelRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject

@AndroidEntryPoint
class MegaStreamTvInputService : TvInputService() {

    @Inject
    lateinit var channelRepository: ChannelRepository

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var playbackGate: PlaybackGate

    override fun onCreateSession(inputId: String): Session = MegaStreamSession(this)

    private inner class MegaStreamSession(context: Context) : Session(context) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> notifyVideoAvailable()
                        Player.STATE_BUFFERING -> notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
                        Player.STATE_IDLE, Player.STATE_ENDED -> Unit
                    }
                }
            })
        }

        private var playbackActive = false

        init {
            scope.launch {
                playbackGate.decision.collect { verdict ->
                    if (verdict is PlaybackGateVerdict.Blocked && playbackActive) {
                        blockPlayback(verdict)
                    }
                }
            }
        }

        private fun canStartPlayback(): Boolean =
            playbackGate.withPlaybackAdmission(onBlocked = { stopBlockedPlayback() }) { true } ?: false

        private fun stopBlockedPlayback() {
            playbackActive = false
            player.playWhenReady = false
            player.stop()
            player.clearMediaItems()
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
        }

        private fun blockPlayback(verdict: PlaybackGateVerdict.Blocked) {
            try {
                stopBlockedPlayback()
            } finally {
                playbackGate.reportBlocked(verdict)
            }
        }

        override fun onTune(channelUri: Uri): Boolean {
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
            scope.launch {
                tune(channelUri)
            }
            return true
        }

        override fun onSetSurface(surface: Surface?): Boolean {
            player.setVideoSurface(surface)
            return true
        }

        override fun onSetStreamVolume(volume: Float) {
            player.volume = volume
        }

        override fun onSetCaptionEnabled(enabled: Boolean) = Unit

        override fun onRelease() {
            scope.cancel()
            player.release()
        }

        private suspend fun tune(channelUri: Uri) {
            val channelRef = withContext(Dispatchers.IO) { loadChannelRef(channelUri) }
            if (channelRef == null) {
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                return
            }

            val channel = channelRepository.getChannel(channelRef.channelId)
            if (channel == null) {
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                return
            }

            val streamInfo = channelRepository.getStreamInfo(channel).getOrNull()
            if (streamInfo == null) {
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                return
            }

            notifyContentAllowed()
            notifyChannelRetuned(channelUri)
            val mediaSource = buildMediaSource(streamInfo)
            if (!canStartPlayback()) return
            playbackActive = true
            player.setMediaSource(mediaSource)
            if (!canStartPlayback()) return
            player.prepare()
            if (!canStartPlayback()) return
            player.playWhenReady = true
        }

        private fun buildMediaSource(streamInfo: StreamInfo): MediaSource {
            val streamType = inferStreamType(streamInfo)
            val mediaItem = MediaItem.fromUri(streamInfo.url)
            return when (streamType) {
                StreamType.HLS -> HlsMediaSource.Factory(buildHttpDataSource(streamInfo)).createMediaSource(mediaItem)
                StreamType.DASH -> DashMediaSource.Factory(buildHttpDataSource(streamInfo)).createMediaSource(mediaItem)
                StreamType.SMOOTH_STREAMING -> SsMediaSource.Factory(buildHttpDataSource(streamInfo)).createMediaSource(mediaItem)
                StreamType.RTSP -> RtspMediaSource.Factory().createMediaSource(mediaItem)
                StreamType.MPEG_TS,
                StreamType.PROGRESSIVE,
                StreamType.UNKNOWN -> ProgressiveMediaSource.Factory(buildHttpDataSource(streamInfo)).createMediaSource(mediaItem)
            }
        }

        private fun buildHttpDataSource(streamInfo: StreamInfo): DataSource.Factory {
            return OkHttpDataSource.Factory(okHttpClient).apply {
                setUserAgent(streamInfo.userAgent ?: DEFAULT_USER_AGENT)
                setDefaultRequestProperties(streamInfo.headers)
            }
        }

        private fun inferStreamType(streamInfo: StreamInfo): StreamType {
            if (streamInfo.streamType != StreamType.UNKNOWN) return streamInfo.streamType
            val url = streamInfo.url.lowercase()
            return when {
                url.endsWith(".m3u8") -> StreamType.HLS
                url.endsWith(".mpd") -> StreamType.DASH
                url.contains(".isml/manifest") || url.contains(".ism/manifest") || url.endsWith(".ism") || url.endsWith(".isml") ->
                    StreamType.SMOOTH_STREAMING
                url.startsWith("rtsp") -> StreamType.RTSP
                url.endsWith(".ts") -> StreamType.MPEG_TS
                else -> StreamType.PROGRESSIVE
            }
        }

        private fun loadChannelRef(channelUri: Uri): ChannelRef? {
            val resolvedUri = if (ContentUris.parseId(channelUri) > 0) channelUri else return null
            return contentResolver.query(
                resolvedUri,
                arrayOf(CHANNEL_COLUMN_INTERNAL_PROVIDER_DATA),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val rawData = cursor.getString(cursor.getColumnIndexOrThrow(CHANNEL_COLUMN_INTERNAL_PROVIDER_DATA))
                decodeChannelRef(rawData)
            }
        }

        private fun decodeChannelRef(rawData: String?): ChannelRef? {
            val parts = rawData?.split(ENTRY_SEPARATOR).orEmpty()
            if (parts.size < 2) return null
            val providerId = parts[0].toLongOrNull() ?: return null
            val channelId = parts[1].toLongOrNull() ?: return null
            return ChannelRef(providerId = providerId, channelId = channelId)
        }
    }

    private data class ChannelRef(
        val providerId: Long,
        val channelId: Long
    )

    private companion object {
        const val TAG = "MegaStreamTvInput"
        const val DEFAULT_USER_AGENT = "MegaStreamTvInput"
        const val CHANNEL_COLUMN_INTERNAL_PROVIDER_DATA = "internal_provider_data"
        const val ENTRY_SEPARATOR = ":"
    }
}
