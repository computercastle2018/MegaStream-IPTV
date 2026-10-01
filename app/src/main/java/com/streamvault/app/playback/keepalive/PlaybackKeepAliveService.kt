package com.MegaStream.app.playback.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.MegaStream.app.MainActivity
import com.MegaStream.app.R

/**
 * Reduces low-memory process eviction risk; it cannot prevent OOM, force-stop, or all OEM kills.
 * It never brings the app to the foreground. The ViewModel/engine retain playback ownership.
 */
class PlaybackKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val permit = intent?.getLongExtra(EXTRA_PERMIT, 0L) ?: 0L
        when (reducePlaybackKeepAliveCommand(
            isStart = intent?.action == ACTION_START,
            permitValid = PlaybackKeepAlivePermits.contains(permit),
            hasOwners = PlaybackKeepAlivePermits.hasOwners()
        )) {
            PlaybackKeepAliveCommand.IGNORE_STALE_OWNER -> return START_NOT_STICKY
            PlaybackKeepAliveCommand.STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            PlaybackKeepAliveCommand.PROMOTE -> Unit
        }
        try {
            // Promote immediately; no playback work, permission prompt, or asynchronous setup.
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else {
                    0
                }
            )
        } catch (_: IllegalStateException) {
            stopAfterDeniedPromotion(permit)
        } catch (_: SecurityException) {
            stopAfterDeniedPromotion(permit)
        }
        return START_NOT_STICKY
    }

    private fun stopAfterDeniedPromotion(permit: Long) {
        PlaybackKeepAlivePermits.revoke(permit)
        Log.w("PlaybackKeepAlive", "Playback foreground promotion was denied")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.player_playback_label), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openPlayer = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.player_playback_label))
            .setContentIntent(openPlayer)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    internal companion object {
        const val ACTION_START = "com.MegaStream.app.playback.keepalive.START"
        const val ACTION_STOP = "com.MegaStream.app.playback.keepalive.STOP"
        const val EXTRA_PERMIT = "playback_owner"
        private const val CHANNEL_ID = "playback_keepalive"
        private const val NOTIFICATION_ID = 7301
    }
}
