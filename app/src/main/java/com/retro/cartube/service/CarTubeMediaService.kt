package com.retro.cartube.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarNotificationManager
import androidx.car.app.notification.CarPendingIntent
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import com.retro.cartube.MainActivity
import com.retro.cartube.R
import com.retro.cartube.car.CarTubeCarService
import com.retro.cartube.player.CarTubePlaybackManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Declares MediaBrowserService for Android Auto host discovery so CarTube
 * is recognized and listed in Android Auto's app launcher and "Customize Launcher".
 *
 * When tapped from the car launcher (or when returning from Google Maps):
 * - Presents a clean "▶ Otvori Video Player" entry item (no more "No items").
 * - Emits a high-priority CarAppExtender Heads-Up banner to jump directly to fullscreen video.
 * - Bridges media session metadata so Coolwalk media controls work in split-screen alongside Maps.
 */
class CarTubeMediaService : MediaBrowserServiceCompat() {

    companion object {
        private const val TAG = "CarTubeMediaService"
        private const val CHANNEL_ID = "cartube_playback_channel"
        private const val NOTIFICATION_ID = 4001
        private const val ROOT_ID = "cartube_root"

        @Volatile
        var instance: CarTubeMediaService? = null
            private set
    }

    private lateinit var mediaSession: MediaSessionCompat
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            Log.d(TAG, "MediaSession: onPlay")
            CarTubePlaybackManager.play()
            postCarJumpNotification()
            updateSessionState()
        }

        override fun onPause() {
            Log.d(TAG, "MediaSession: onPause")
            CarTubePlaybackManager.pause()
            updateSessionState()
        }

        override fun onSkipToNext() {
            Log.d(TAG, "MediaSession: onSkipToNext (+10s)")
            CarTubePlaybackManager.seekRelative(10000L)
            updateSessionState()
        }

        override fun onSkipToPrevious() {
            Log.d(TAG, "MediaSession: onSkipToPrevious (-10s)")
            CarTubePlaybackManager.seekRelative(-10000L)
            updateSessionState()
        }

        override fun onSeekTo(pos: Long) {
            Log.d(TAG, "MediaSession: onSeekTo $pos")
            CarTubePlaybackManager.seekTo(pos)
            updateSessionState()
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            Log.d(TAG, "MediaSession: onPlayFromMediaId: $mediaId")
            CarTubePlaybackManager.play()
            postCarJumpNotification()
            updateSessionState()
        }

        override fun onCustomAction(action: String?, extras: Bundle?) {
            Log.d(TAG, "MediaSession: onCustomAction: $action")
            postCarJumpNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "CarTubeMediaService onCreate")

        createNotificationChannel()

        val sessionIntent = Intent(this, MainActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(this, 0, sessionIntent, flags)

        mediaSession = MediaSessionCompat(this, TAG).apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setSessionActivity(sessionActivityPendingIntent)
            setCallback(sessionCallback)
            isActive = true
        }
        sessionToken = mediaSession.sessionToken

        updateSessionState()

        // Observe playback state changes and sync MediaSession
        serviceScope.launch {
            CarTubePlaybackManager.isPlaying.collectLatest {
                updateSessionState()
            }
        }
        serviceScope.launch {
            CarTubePlaybackManager.currentVideo.collectLatest { item ->
                updateSessionState()
                if (item != null) {
                    postCarJumpNotification()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        return START_STICKY
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        Log.d(TAG, "onGetRoot: $clientPackageName")
        val extras = Bundle().apply {
            putBoolean("android.service.media.extra.RECENT", true)
            putBoolean("android.service.media.extra.OFFLINE", true)
        }
        return BrowserRoot(ROOT_ID, extras)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        Log.d(TAG, "onLoadChildren: $parentId")
        val items = mutableListOf<MediaItem>()

        val current = CarTubePlaybackManager.currentVideo.value
        val isPlaying = CarTubePlaybackManager.isPlaying.value

        val title = if (current != null) {
            if (isPlaying) "▶ Prikaz Videa: ${current.title}" else "▶ Nastavi Video: ${current.title}"
        } else {
            "▶ Otvori CarTube Video Player"
        }
        val subtitle = if (current != null && current.channel.isNotBlank()) current.channel else "Kliknite za prikaz na cijelom ekranu"

        val iconBitmap = try {
            BitmapFactory.decodeResource(resources, R.drawable.ic_app_icon)
        } catch (e: Exception) {
            null
        }

        val descBuilder = MediaDescriptionCompat.Builder()
            .setMediaId("cartube_launch_video")
            .setTitle(title)
            .setSubtitle(subtitle)
            .setDescription("CarTube Fullscreen Video Player")

        if (iconBitmap != null) {
            descBuilder.setIconBitmap(iconBitmap)
        }

        items.add(MediaItem(descBuilder.build(), MediaItem.FLAG_PLAYABLE))
        result.sendResult(items)

        // Whenever the user taps CarTube in the launcher or enters this view,
        // trigger the car jump banner so they can seamlessly jump straight to fullscreen video!
        postCarJumpNotification()
    }

    fun postCarJumpNotification() {
        try {
            val intent = Intent(this, CarTubeCarService::class.java).apply {
                action = "androidx.car.app.CarAppService"
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val carPendingIntent = CarPendingIntent.getCarApp(this, 100, intent, flags)

            val current = CarTubePlaybackManager.currentVideo.value
            val title = current?.title ?: "CarTube Video"

            val carExtender = CarAppExtender.Builder()
                .setContentTitle("🎬 $title")
                .setContentText("Dodirnite za prikaz preko cijelog ekrana")
                .setSmallIcon(R.drawable.ic_app_icon)
                .setContentIntent(carPendingIntent)
                .setImportance(NotificationManager.IMPORTANCE_HIGH)
                .build()

            val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_app_icon)
                .setContentTitle("🎬 $title")
                .setContentText("Dodirnite za prikaz preko cijelog ekrana")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(carPendingIntent)
                .setAutoCancel(true)
                .extend(carExtender)

            val carNotificationManager = CarNotificationManager.from(this)
            carNotificationManager.notify(NOTIFICATION_ID, notificationBuilder)
        } catch (e: Exception) {
            Log.w(TAG, "Error posting car jump notification", e)
        }
    }

    private fun updateSessionState() {
        try {
            val isPlaying = CarTubePlaybackManager.isPlaying.value
            val position = CarTubePlaybackManager.currentPosition.value
            val duration = CarTubePlaybackManager.duration.value
            val current = CarTubePlaybackManager.currentVideo.value

            val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            val actions = PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO

            val stateBuilder = PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, position, if (isPlaying) 1.0f else 0.0f, SystemClock.elapsedRealtime())

            mediaSession.setPlaybackState(stateBuilder.build())

            val metaBuilder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, current?.title ?: "CarTube Video")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, current?.channel ?: "YouTube")
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "CarTube")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, current?.title ?: "CarTube Video")
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, current?.channel ?: "YouTube")

            mediaSession.setMetadata(metaBuilder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Error updating session state", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CarTube Video Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows CarTube video return banner in Android Auto"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession.isActive = false
        mediaSession.release()
        instance = null
        super.onDestroy()
    }
}
