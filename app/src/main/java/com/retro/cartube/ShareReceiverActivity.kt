package com.retro.cartube

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.retro.cartube.player.CarTubePlaybackManager
import com.retro.cartube.video.YouTubeVideoResolver

class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleIntent(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            handleIntent(intent)
        }
        finish()
    }

    private fun handleIntent(intent: Intent) {
        val action = intent.action
        val text = when (action) {
            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            }
            Intent.ACTION_VIEW -> {
                intent.dataString ?: ""
            }
            else -> ""
        }

        if (text.isBlank()) {
            Toast.makeText(this, "Nema YouTube linka za dijeljenje", Toast.LENGTH_SHORT).show()
            return
        }

        val parsed = YouTubeVideoResolver.parseYouTubeUrl(text)
        if (parsed != null) {
            val (videoId, startMs) = parsed
            CarTubePlaybackManager.enqueueOrPlay(
                context = applicationContext,
                videoId = videoId,
                startPositionMs = startMs,
                fallbackTitle = "YouTube Video"
            )

            val timeHint = if (startMs > 0) " od ${startMs / 1000}s" else ""
            Toast.makeText(
                this,
                "▶ Video poslan na auto zaslon! ($videoId$timeHint)",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(this, "Nije prepoznat važeći YouTube link: $text", Toast.LENGTH_SHORT).show()
        }
    }
}
