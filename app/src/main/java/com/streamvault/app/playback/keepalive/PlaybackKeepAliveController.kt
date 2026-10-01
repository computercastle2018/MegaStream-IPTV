package com.MegaStream.app.playback.keepalive

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicLong

internal interface PlaybackKeepAliveRequests {
    fun start(): Boolean
    fun stop(): Boolean
}

/** Main-thread owner of successful requests, not a claim that Android will keep the process alive. */
internal class PlaybackKeepAliveController(
    private val requests: PlaybackKeepAliveRequests
) : AutoCloseable {
    var serviceRequested: Boolean = false
        private set
    private var startAttempted = false
    private var closed = false

    fun update(input: PlaybackKeepAliveInput) {
        if (closed) return
        val desired = reducePlaybackKeepAlive(input, serviceRequested)
        if (!desired) {
            startAttempted = false
            if (serviceRequested && requests.stop()) serviceRequested = false
        } else if (!serviceRequested && !startAttempted) {
            // A denied start must not turn repeated engine/UI emissions into a retry loop.
            startAttempted = true
            serviceRequested = requests.start()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // Invalidate even a queued request before stopping the service.
        if (requests.stop()) serviceRequested = false
    }
}

/** Process-local permits prevent a queued START from resurrecting a cleared playback owner. */
internal object PlaybackKeepAlivePermits {
    private val nextId = AtomicLong()
    private val active = mutableSetOf<Long>()

    fun newId(): Long = nextId.incrementAndGet()
    @Synchronized fun allow(id: Long) { active.add(id) }
    @Synchronized fun revoke(id: Long) { active.remove(id) }
    @Synchronized fun contains(id: Long): Boolean = id in active
    @Synchronized fun hasOwners(): Boolean = active.isNotEmpty()
}

internal class AndroidPlaybackKeepAliveRequests(context: Context) : PlaybackKeepAliveRequests {
    private val applicationContext = context.applicationContext
    private val permit = PlaybackKeepAlivePermits.newId()

    override fun start(): Boolean {
        PlaybackKeepAlivePermits.allow(permit)
        return try {
            // Notification permission denial on Android 13+ does not prohibit an FGS request.
            val accepted = ContextCompat.startForegroundService(
                applicationContext,
                Intent(applicationContext, PlaybackKeepAliveService::class.java)
                    .setAction(PlaybackKeepAliveService.ACTION_START)
                    .putExtra(PlaybackKeepAliveService.EXTRA_PERMIT, permit)
            ) != null
            if (!accepted) PlaybackKeepAlivePermits.revoke(permit)
            accepted
        } catch (_: IllegalStateException) {
            startDenied()
        } catch (_: SecurityException) {
            startDenied()
        }
    }

    private fun startDenied(): Boolean {
        PlaybackKeepAlivePermits.revoke(permit)
        Log.w(TAG, "Playback foreground service request was denied")
        return false
    }

    override fun stop(): Boolean {
        PlaybackKeepAlivePermits.revoke(permit)
        if (PlaybackKeepAlivePermits.hasOwners()) return true
        return try {
            applicationContext.stopService(Intent(applicationContext, PlaybackKeepAliveService::class.java))
            true // false from stopService means there is already no running service.
        } catch (_: IllegalStateException) {
            Log.w(TAG, "Playback foreground service stop was denied")
            false
        } catch (_: SecurityException) {
            Log.w(TAG, "Playback foreground service stop was denied")
            false
        }
    }

    private companion object {
        const val TAG = "PlaybackKeepAlive"
    }
}
