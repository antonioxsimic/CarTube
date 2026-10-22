package com.retro.cartube

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.retro.cartube.lounge.YouTubeLoungeReceiver
import com.retro.cartube.player.CarTubePlaybackManager
import com.retro.cartube.video.YouTubeVideoResolver
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvVideoTitle: TextView
    private lateinit var tvChannel: TextView
    private lateinit var tvPlaybackState: TextView
    private lateinit var etYoutubeUrl: EditText
    private lateinit var btnSendToCar: Button
    private lateinit var phoneSeekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalDuration: TextView
    private lateinit var tvTvPairingCode: TextView
    private lateinit var tvLoungeConnectionBadge: TextView
    private lateinit var tvLoungeStatusDesc: TextView
    private lateinit var btnCopyCode: Button
    private lateinit var btnRefreshCode: Button
    private var isUserScrubbing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvVideoTitle = findViewById(R.id.tvVideoTitle)
        tvChannel = findViewById(R.id.tvChannel)
        tvPlaybackState = findViewById(R.id.tvPlaybackState)
        etYoutubeUrl = findViewById(R.id.etYoutubeUrl)
        btnSendToCar = findViewById(R.id.btnSendToCar)
        val btnScaleMode: Button = findViewById(R.id.btnScaleMode)
        val btnAudioSync: Button = findViewById(R.id.btnAudioSync)
        val btnQuality: Button = findViewById(R.id.btnQuality)
        phoneSeekBar = findViewById(R.id.phoneSeekBar)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalDuration = findViewById(R.id.tvTotalDuration)

        tvTvPairingCode = findViewById(R.id.tvTvPairingCode)
        tvLoungeConnectionBadge = findViewById(R.id.tvLoungeConnectionBadge)
        tvLoungeStatusDesc = findViewById(R.id.tvLoungeStatusDesc)
        btnCopyCode = findViewById(R.id.btnCopyCode)
        btnRefreshCode = findViewById(R.id.btnRefreshCode)

        CarTubePlaybackManager.initialize(applicationContext)
        YouTubeLoungeReceiver.start(applicationContext)

        btnCopyCode.setOnClickListener {
            val code = YouTubeLoungeReceiver.rawPairingCode.value.ifEmpty {
                YouTubeLoungeReceiver.pairingCode.value.replace(" ", "")
            }
            if (code.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("TV Code", code)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "📋 Kod $code kopiran!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Kod još nije spreman...", Toast.LENGTH_SHORT).show()
            }
        }

        btnRefreshCode.setOnClickListener {
            YouTubeLoungeReceiver.refreshPairingCode(force = true)
            Toast.makeText(this, "Dohvaćam novi TV kod...", Toast.LENGTH_SHORT).show()
        }

        btnScaleMode.setOnClickListener {
            val next = CarTubePlaybackManager.cycleScaleMode()
            Toast.makeText(this, "Format prikaza: ${next.label}", Toast.LENGTH_SHORT).show()
        }

        btnAudioSync.setOnClickListener {
            val next = CarTubePlaybackManager.cycleAudioSyncDelay()
            Toast.makeText(this, "Audio Sync: ${next.label}", Toast.LENGTH_SHORT).show()
        }

        btnQuality.setOnClickListener {
            val next = CarTubePlaybackManager.cycleVideoQuality(applicationContext)
            Toast.makeText(this, "Kvaliteta: ${next.label}", Toast.LENGTH_SHORT).show()
        }

        phoneSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = CarTubePlaybackManager.duration.value
                    if (dur > 0L) {
                        val targetMs = (progress.toFloat() / 1000f * dur).toLong()
                        tvCurrentTime.text = formatTime(targetMs)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserScrubbing = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val dur = CarTubePlaybackManager.duration.value
                val prog = seekBar?.progress ?: 0
                if (dur > 0L) {
                    val targetMs = (prog.toFloat() / 1000f * dur).toLong()
                    CarTubePlaybackManager.seekTo(targetMs)
                }
                isUserScrubbing = false
            }
        })

        lifecycleScope.launch {
            CarTubePlaybackManager.currentPosition.collect { pos ->
                val dur = CarTubePlaybackManager.duration.value
                if (!isUserScrubbing) {
                    if (dur > 0L) {
                        val progress = ((pos.toFloat() / dur.toFloat()) * 1000).toInt().coerceIn(0, 1000)
                        phoneSeekBar.progress = progress
                    } else {
                        phoneSeekBar.progress = 0
                    }
                    tvCurrentTime.text = formatTime(pos)
                }
            }
        }

        lifecycleScope.launch {
            CarTubePlaybackManager.duration.collect { dur ->
                tvTotalDuration.text = formatTime(dur)
            }
        }

        lifecycleScope.launch {
            CarTubePlaybackManager.scaleMode.collect { mode ->
                btnScaleMode.text = mode.shortName
            }
        }

        lifecycleScope.launch {
            CarTubePlaybackManager.audioSyncDelay.collect { sync ->
                btnAudioSync.text = sync.shortName
            }
        }

        lifecycleScope.launch {
            CarTubePlaybackManager.videoQuality.collect { quality ->
                btnQuality.text = quality.shortName
            }
        }

        btnSendToCar.setOnClickListener {
            val url = etYoutubeUrl.text.toString().trim()
            if (url.isBlank()) {
                Toast.makeText(this, "Unesite YouTube link!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val parsed = YouTubeVideoResolver.parseYouTubeUrl(url)
            if (parsed != null) {
                val (vid, startMs) = parsed
                CarTubePlaybackManager.enqueueOrPlay(applicationContext, vid, startMs)
                etYoutubeUrl.setText("")
                Toast.makeText(this, "▶ Poslano na auto!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Nevažeći YouTube link!", Toast.LENGTH_SHORT).show()
            }
        }

        lifecycleScope.launch {
            CarTubePlaybackManager.currentVideo.collect { item ->
                if (item != null) {
                    tvVideoTitle.text = item.title
                    tvChannel.text = if (item.channel.isNotBlank()) item.channel else "YouTube ID: ${item.videoId}"
                } else {
                    tvVideoTitle.text = "Nema aktivnog videa"
                    tvChannel.text = "Podijelite video iz YouTube ReVanced aplikacije"
                }
            }
        }

        lifecycleScope.launch {
            CarTubePlaybackManager.statusText.collect { status ->
                tvPlaybackState.text = status
            }
        }

        lifecycleScope.launch {
            YouTubeLoungeReceiver.pairingCode.collect { code ->
                if (code.isNotBlank()) {
                    tvTvPairingCode.text = code
                } else {
                    tvTvPairingCode.text = "--- --- --- ---"
                }
            }
        }

        lifecycleScope.launch {
            YouTubeLoungeReceiver.connectedClient.collect { clientName ->
                if (clientName != null) {
                    tvLoungeConnectionBadge.text = "🟢 Povezano"
                    tvLoungeConnectionBadge.setTextColor(0xFF00E676.toInt())
                    tvLoungeConnectionBadge.setBackgroundColor(0xFF1B3828.toInt())
                    tvLoungeStatusDesc.text = "Povezan klijent: $clientName"
                    tvLoungeStatusDesc.setTextColor(0xFF00E676.toInt())
                } else {
                    tvLoungeConnectionBadge.text = "⚪ Čekam vezu"
                    tvLoungeConnectionBadge.setTextColor(0xFF787C96.toInt())
                    tvLoungeConnectionBadge.setBackgroundColor(0xFF222538.toInt())
                    tvLoungeStatusDesc.text = YouTubeLoungeReceiver.statusMessage.value
                    tvLoungeStatusDesc.setTextColor(0xFF4DEEEA.toInt())
                }
            }
        }

        lifecycleScope.launch {
            YouTubeLoungeReceiver.statusMessage.collect { msg ->
                if (YouTubeLoungeReceiver.connectedClient.value == null) {
                    tvLoungeStatusDesc.text = msg
                }
            }
        }
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
}
