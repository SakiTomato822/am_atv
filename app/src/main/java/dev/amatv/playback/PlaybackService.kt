package dev.amatv.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import dev.amatv.MainActivity
import dev.amatv.R

/**
 * Foreground service ensuring the OS does not terminate audio playback when the Activity is
 * backgrounded, and the media notification behind the system media controls (shade, lock screen,
 * car launcher cards). The notification carries the MediaSession token, so Android 13+ builds its
 * controls from the session; the explicit actions serve Android 11/12 and older car launchers.
 * While paused the notification stays, no longer in the foreground, so the controls remain.
 */
class PlaybackService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (val action = intent?.action) {
            ACTION_START, ACTION_UPDATE, ACTION_PAUSED -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)
                val artist = intent.getStringExtra(EXTRA_ARTIST) ?: ""
                val playing = action != ACTION_PAUSED && intent.getBooleanExtra(EXTRA_PLAYING, true)
                val notification = buildNotification(title, artist, playing)
                when (action) {
                    ACTION_START -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                    ACTION_PAUSED -> {
                        // Keep the controls; only a playing session holds the foreground.
                        stopForeground(STOP_FOREGROUND_DETACH)
                        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
                    }
                    else -> getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
                }
            }
            ACTION_PREVIOUS, ACTION_TOGGLE, ACTION_NEXT -> {
                val controller = token?.let { MediaController(this, it) }
                when (action) {
                    ACTION_PREVIOUS -> controller?.transportControls?.skipToPrevious()
                    ACTION_NEXT -> controller?.transportControls?.skipToNext()
                    else -> if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                        controller.transportControls.pause()
                    } else {
                        controller?.transportControls?.play()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.services_playback_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.services_playback_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, artist: String, playing: Boolean): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(icon: Int, label: Int, command: String, request: Int) = Notification.Action.Builder(
            Icon.createWithResource(this, icon),
            getString(label),
            PendingIntent.getService(this, request, Intent(this, PlaybackService::class.java).setAction(command),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
        ).build()
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        token?.let(style::setMediaSession)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(R.drawable.ic_launcher)
            .setLargeIcon(artwork)
            .setContentIntent(contentPendingIntent)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(action(android.R.drawable.ic_media_previous, R.string.services_media_previous, ACTION_PREVIOUS, 1))
            .addAction(if (playing) {
                action(android.R.drawable.ic_media_pause, R.string.services_media_pause, ACTION_TOGGLE, 2)
            } else {
                action(android.R.drawable.ic_media_play, R.string.services_media_play, ACTION_TOGGLE, 2)
            })
            .addAction(action(android.R.drawable.ic_media_next, R.string.services_media_next, ACTION_NEXT, 3))
            .setStyle(style)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "amatv_media_playback"
        const val NOTIFICATION_ID = 1001

        /** The app's MediaSession; the notification hands it to the system media controls. */
        @Volatile var token: MediaSession.Token? = null
        /** Artwork of the current song for the notification, or null while it loads. */
        @Volatile var artwork: Bitmap? = null

        private const val ACTION_START = "dev.amatv.action.PLAYBACK_START"
        private const val ACTION_UPDATE = "dev.amatv.action.PLAYBACK_UPDATE"
        private const val ACTION_PAUSED = "dev.amatv.action.PLAYBACK_PAUSED"
        private const val ACTION_PREVIOUS = "dev.amatv.action.PLAYBACK_PREVIOUS"
        private const val ACTION_TOGGLE = "dev.amatv.action.PLAYBACK_TOGGLE"
        private const val ACTION_NEXT = "dev.amatv.action.PLAYBACK_NEXT"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_ARTIST = "extra_artist"
        private const val EXTRA_PLAYING = "extra_playing"

        fun start(context: Context, title: String, artist: String): Boolean {
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.getOrElse {
                Log.w("AMATV-playback", "Could not start playback foreground service", it)
                false
            }
        }

        fun update(context: Context, title: String, artist: String) {
            send(context, ACTION_UPDATE, title, artist, playing = true)
        }

        /** Paused: the notification and its controls stay, out of the foreground. */
        fun paused(context: Context, title: String, artist: String) {
            send(context, ACTION_PAUSED, title, artist, playing = false)
        }

        private fun send(context: Context, command: String, title: String, artist: String, playing: Boolean) {
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = command
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_PLAYING, playing)
            }
            runCatching { context.startService(intent) }
                .onFailure { Log.w("AMATV-playback", "Could not update playback notification", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
