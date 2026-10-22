package com.retro.cartube.service

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat

/**
 * Declares MediaBrowserService for Android Auto host discovery so CarTube
 * is recognized and listed in Android Auto's app launcher and "Customize Launcher".
 */
class CarTubeMediaService : MediaBrowserServiceCompat() {

    private lateinit var mediaSession: MediaSessionCompat

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, "CarTubeMediaService").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                                PlaybackStateCompat.ACTION_PAUSE or
                                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    )
                    .setState(PlaybackStateCompat.STATE_NONE, 0L, 1.0f)
                    .build()
            )
            isActive = true
        }
        sessionToken = mediaSession.sessionToken
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        return BrowserRoot("cartube_root", null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        result.sendResult(mutableListOf())
    }

    override fun onDestroy() {
        mediaSession.release()
        super.onDestroy()
    }
}
