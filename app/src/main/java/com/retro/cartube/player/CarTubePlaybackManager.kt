package com.retro.cartube.player

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.view.Surface
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.retro.cartube.lounge.YouTubeLoungeReceiver
import com.retro.cartube.video.VideoStreamInfo
import com.retro.cartube.video.YouTubeVideoResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudioSyncDelay(val delayMs: Long, val label: String, val shortName: String) {
    DELAY_2000(2000L, "Odgoda 2.0s (BMW BT)", "⏱ +2.0s"),
    DELAY_2500(2500L, "Odgoda 2.5s", "⏱ +2.5s"),
    OFF(0L, "Isključeno (0s)", "⏱ 0.0s"),
    DELAY_500(500L, "Odgoda 0.5s", "⏱ +0.5s"),
    DELAY_1000(1000L, "Odgoda 1.0s", "⏱ +1.0s"),
    DELAY_1500(1500L, "Odgoda 1.5s", "⏱ +1.5s")
}

enum class VideoQuality(val targetHeight: Int, val label: String, val shortName: String) {
    FULL_HD_1080P(1080, "1080p Full HD (Oštra slika)", "🎬 1080p"),
    HD_720P(720, "720p HD (Ušteda podataka)", "🎬 720p")
}

class BluetoothDelayAudioSink(
    delegate: AudioSink,
    private val getDelayMs: () -> Long
) : ForwardingAudioSink(delegate) {

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val rawPositionUs = super.getCurrentPositionUs(sourceEnded)
        if (rawPositionUs == C.TIME_UNSET) return C.TIME_UNSET

        val delayMs = getDelayMs()
        if (delayMs == 0L) return rawPositionUs

        val delayUs = delayMs * 1000L
        // Subtract delayUs from master audio clock so video renderer waits delayMs,
        // syncing video with the car's Bluetooth latency!
        return (rawPositionUs - delayUs).coerceAtLeast(0L)
    }
}

data class VideoQueueItem(
    val videoId: String,
    val title: String,
    val channel: String = "",
    val startPositionMs: Long = 0L,
    val timestampAdded: Long = System.currentTimeMillis()
)

object CarTubePlaybackManager {

    private const val TAG = "CarTubePlaybackMgr"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var exoPlayer: ExoPlayer? = null
    private var videoRenderer: CarVideoSurfaceRenderer? = null
    private var activeSurface: Surface? = null
    private var resolveJob: Job? = null
    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    // Scaling mode (Fit = Default, Best Fit = Zoom/Crop, Stretch = Full)
    private val _scaleMode = MutableStateFlow(VideoScaleMode.FIT)
    val scaleMode = _scaleMode.asStateFlow()

    // Audio sync delay (Default 2.0s as requested to eliminate Bluetooth car lag)
    private val _audioSyncDelay = MutableStateFlow(AudioSyncDelay.DELAY_2000)
    val audioSyncDelay = _audioSyncDelay.asStateFlow()

    // Video Quality Preference (Default 1080p Full HD for maximum sharpness)
    private val _videoQuality = MutableStateFlow(VideoQuality.FULL_HD_1080P)
    val videoQuality = _videoQuality.asStateFlow()

    // Video State
    private val _currentVideo = MutableStateFlow<VideoQueueItem?>(null)
    val currentVideo = _currentVideo.asStateFlow()

    private val _streamInfo = MutableStateFlow<VideoStreamInfo?>(null)
    val streamInfo = _streamInfo.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _statusText = MutableStateFlow("Čekam video...")
    val statusText = _statusText.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration = _duration.asStateFlow()

    // Seekbar visibility state
    private var hideSeekbarJob: Job? = null
    private val _isSeekbarVisible = MutableStateFlow(false)
    val isSeekbarVisible = _isSeekbarVisible.asStateFlow()

    // History of shared videos
    val videoHistory = mutableListOf<VideoQueueItem>()

    @Synchronized
    fun initialize(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
        if (prefs == null) {
            prefs = context.getSharedPreferences("cartube_prefs", Context.MODE_PRIVATE)
            val savedDelay = prefs?.getString("audio_sync_delay", AudioSyncDelay.DELAY_2000.name) ?: AudioSyncDelay.DELAY_2000.name
            val delay = try {
                AudioSyncDelay.valueOf(savedDelay)
            } catch (_: Exception) {
                AudioSyncDelay.DELAY_2000
            }
            _audioSyncDelay.value = delay
            Log.i(TAG, "Audio sync delay initialized: ${delay.label}")

            val savedQuality = prefs?.getString("video_quality", VideoQuality.FULL_HD_1080P.name) ?: VideoQuality.FULL_HD_1080P.name
            val quality = try {
                VideoQuality.valueOf(savedQuality)
            } catch (_: Exception) {
                VideoQuality.FULL_HD_1080P
            }
            _videoQuality.value = quality
            Log.i(TAG, "Video quality initialized: ${quality.label}")
        }

        if (videoRenderer == null) {
            try {
                val renderer = CarVideoSurfaceRenderer(context.applicationContext)
                videoRenderer = renderer
                _scaleMode.value = renderer.scaleMode
                Log.i(TAG, "CarVideoSurfaceRenderer initialized with default mode: ${renderer.scaleMode.label}")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize CarVideoSurfaceRenderer", e)
            }
        }

        if (exoPlayer == null) {
            try {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build()

                val renderersFactory = object : DefaultRenderersFactory(context.applicationContext) {
                    override fun buildAudioSink(
                        context: Context,
                        enableFloatOutput: Boolean,
                        enableAudioTrackPlaybackParams: Boolean
                    ): AudioSink? {
                        val defaultAudioSink = DefaultAudioSink.Builder(context)
                            .setEnableFloatOutput(enableFloatOutput)
                            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                            .build()
                        return BluetoothDelayAudioSink(defaultAudioSink) { _audioSyncDelay.value.delayMs }
                    }
                }

                val loadControl = DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 30_000,
                        /* maxBufferMs = */ 60_000,
                        /* bufferForPlaybackMs = */ 3_500,
                        /* bufferForPlaybackAfterRebufferMs = */ 5_000
                    )
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .setBackBuffer(15_000, true)
                    .build()

                exoPlayer = ExoPlayer.Builder(context.applicationContext, renderersFactory)
                    .setAudioAttributes(audioAttributes, true)
                    .setHandleAudioBecomingNoisy(true)
                    .setLoadControl(loadControl)
                    .build().apply {
                        repeatMode = Player.REPEAT_MODE_OFF
                        trackSelectionParameters = trackSelectionParameters
                            .buildUpon()
                            .setPreferredAudioLanguage("en")
                            .build()

                        // Connect ExoPlayer decoder to CarVideoSurfaceRenderer's input Surface
                        videoRenderer?.getInputSurface()?.let { inputSurf ->
                            if (inputSurf.isValid) {
                                setVideoSurface(inputSurf)
                            }
                        }

                        addListener(object : Player.Listener {
                            override fun onIsPlayingChanged(playing: Boolean) {
                                _isPlaying.value = playing
                                YouTubeLoungeReceiver.reportCurrentState()
                            }

                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_READY) {
                                    _isLoading.value = false
                                    _duration.value = exoPlayer?.duration?.coerceAtLeast(0L) ?: 0L
                                    YouTubeLoungeReceiver.reportCurrentState()
                                } else if (playbackState == Player.STATE_ENDED) {
                                    _isPlaying.value = false
                                    _statusText.value = "Video je završio"
                                    YouTubeLoungeReceiver.reportCurrentState()
                                } else if (playbackState == Player.STATE_BUFFERING) {
                                    _isLoading.value = true
                                    YouTubeLoungeReceiver.reportCurrentState()
                                }
                            }

                            override fun onVideoSizeChanged(videoSize: VideoSize) {
                                if (videoSize.width > 0 && videoSize.height > 0) {
                                    Log.i(TAG, "onVideoSizeChanged: ${videoSize.width}x${videoSize.height}")
                                    videoRenderer?.setVideoSize(videoSize.width, videoSize.height)
                                }
                            }

                            override fun onPlayerError(error: PlaybackException) {
                                Log.e(TAG, "ExoPlayer error", error)
                                _isLoading.value = false
                                _statusText.value = "Greška reprodukcije: ${error.localizedMessage}"
                            }
                        })
                    }

                // Start position tracker loop
                scope.launch {
                    while (isActive) {
                        val p = exoPlayer
                        if (p != null) {
                            val pos = p.currentPosition.coerceAtLeast(0L)
                            val dur = p.duration.coerceAtLeast(0L)
                            _currentPosition.value = pos
                            _duration.value = dur
                            if (_isSeekbarVisible.value) {
                                videoRenderer?.updateSeekbar(pos, dur, p.isPlaying)
                            }
                        }
                        delay(250)
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize ExoPlayer", e)
            }
        }
    }

    /**
     * Called when a link is shared from YouTube ReVanced or entered manually
     */
    fun enqueueOrPlay(context: Context, videoId: String, startPositionMs: Long = 0L, fallbackTitle: String = "YouTube Video") {
        initialize(context)

        val item = VideoQueueItem(
            videoId = videoId,
            title = fallbackTitle,
            startPositionMs = startPositionMs
        )
        _currentVideo.value = item
        videoHistory.add(0, item)
        if (videoHistory.size > 20) videoHistory.removeLast()

        // Immediately start resolving and preparing stream in background
        prepareAndPlay(context, item)
    }

    /**
     * Called when CarTubeScreen's surface is available or ready
     */
    fun attachSurface(context: Context, surface: Surface, width: Int = 1920, height: Int = 720) {
        initialize(context)
        activeSurface = surface
        videoRenderer?.setCarSurface(surface, width, height)

        val player = exoPlayer ?: return
        val inputSurf = videoRenderer?.getInputSurface()
        if (inputSurf != null && inputSurf.isValid) {
            player.setVideoSurface(inputSurf)
        }

        val stream = _streamInfo.value
        val item = _currentVideo.value

        if (stream != null) {
            if (player.playbackState == Player.STATE_IDLE) {
                setupPlayerMedia(context, stream, item?.startPositionMs ?: 0L)
            }
            player.play()
            _isPlaying.value = true
            _isLoading.value = false
            Log.i(TAG, "Attached surface and started playback for: ${stream.title}")
        } else if (item != null) {
            if (resolveJob?.isActive != true) {
                prepareAndPlay(context, item)
            }
        }
    }

    fun updateScreenSize(width: Int, height: Int) {
        videoRenderer?.setScreenSize(width, height)
    }

    fun detachSurface() {
        activeSurface = null
        videoRenderer?.setCarSurface(null, 0, 0)
    }

    private fun prepareAndPlay(context: Context, item: VideoQueueItem) {
        resolveJob?.cancel()
        _isLoading.value = true
        _statusText.value = "Učitavam YouTube video..."

        resolveJob = scope.launch {
            try {
                Log.i(TAG, "Resolving video stream for ID: ${item.videoId} at ${_videoQuality.value.targetHeight}p")
                val stream = YouTubeVideoResolver.resolveStream(item.videoId, item.title, _videoQuality.value.targetHeight)
                if (stream == null) {
                    _isLoading.value = false
                    _statusText.value = "Nije moguće učitati video stream"
                    return@launch
                }

                _streamInfo.value = stream
                _currentVideo.value = item.copy(title = stream.title, channel = stream.channel)
                _statusText.value = stream.title

                val player = exoPlayer ?: return@launch
                val inputSurf = videoRenderer?.getInputSurface()
                if (inputSurf != null && inputSurf.isValid) {
                    player.setVideoSurface(inputSurf)
                }

                val surface = activeSurface
                if (surface != null && surface.isValid) {
                    setupPlayerMedia(context, stream, item.startPositionMs)
                    player.play()
                    _isPlaying.value = true
                    _isLoading.value = false
                    Log.i(TAG, "Playing video on car surface: ${stream.title} (${stream.qualityLabel}) [Mode: ${_scaleMode.value.label}]")
                } else {
                    _isLoading.value = false
                    _statusText.value = "Spreman za auto: ${stream.title}"
                    Log.i(TAG, "Video resolved and ready for car surface: ${stream.title}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve/play stream", e)
                _isLoading.value = false
                _statusText.value = "Greška: ${e.message}"
            }
        }
    }

    private fun setupPlayerMedia(context: Context, stream: VideoStreamInfo, startPositionMs: Long) {
        val player = exoPlayer ?: return

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(25_000)
            .setAllowCrossProtocolRedirects(true)

        val dataSourceFactory = DefaultDataSource.Factory(context.applicationContext, httpDataSourceFactory)

        val progressiveFactory = ProgressiveMediaSource.Factory(dataSourceFactory)
            .setContinueLoadingCheckIntervalBytes(64 * 1024)

        val mediaSource = if (stream.audioUrl != null) {
            val videoSource = progressiveFactory.createMediaSource(MediaItem.fromUri(stream.streamUrl))
            val audioSource = progressiveFactory.createMediaSource(MediaItem.fromUri(stream.audioUrl))
            MergingMediaSource(videoSource, audioSource)
        } else if (stream.isHls) {
            val hlsFactory = HlsMediaSource.Factory(dataSourceFactory)
            hlsFactory.createMediaSource(MediaItem.fromUri(stream.streamUrl))
        } else {
            progressiveFactory.createMediaSource(MediaItem.fromUri(stream.streamUrl))
        }

        player.setMediaSource(mediaSource)

        if (startPositionMs > 0L) {
            player.seekTo(startPositionMs)
        }

        player.prepare()
    }

    fun setScaleMode(mode: VideoScaleMode) {
        videoRenderer?.setScaleMode(mode)
        _scaleMode.value = mode
    }

    fun cycleScaleMode(): VideoScaleMode {
        val next = videoRenderer?.cycleScaleMode() ?: VideoScaleMode.FIT
        _scaleMode.value = next
        return next
    }

    fun setAudioSyncDelay(delay: AudioSyncDelay) {
        _audioSyncDelay.value = delay
        prefs?.edit()?.putString("audio_sync_delay", delay.name)?.apply()
        Log.i(TAG, "Audio sync delay set to: ${delay.label}")
    }

    fun cycleAudioSyncDelay(): AudioSyncDelay {
        val values = AudioSyncDelay.values()
        val next = values[(_audioSyncDelay.value.ordinal + 1) % values.size]
        setAudioSyncDelay(next)
        return next
    }

    fun setVideoQuality(quality: VideoQuality, context: Context? = null) {
        _videoQuality.value = quality
        prefs?.edit()?.putString("video_quality", quality.name)?.apply()
        Log.i(TAG, "Video quality set to: ${quality.label}")

        // If a video is playing, re-resolve in the new quality seamlessly at the current playback position
        val cur = _currentVideo.value
        val ctx = context ?: appContext
        val player = exoPlayer
        if (cur != null && ctx != null && player != null && player.playbackState != Player.STATE_IDLE) {
            val currentPos = player.currentPosition.coerceAtLeast(0L)
            prepareAndPlay(ctx, cur.copy(startPositionMs = currentPos))
        }
    }

    fun cycleVideoQuality(context: Context? = null): VideoQuality {
        val values = VideoQuality.values()
        val next = values[(_videoQuality.value.ordinal + 1) % values.size]
        setVideoQuality(next, context)
        return next
    }

    fun showSeekbar(autoHideMs: Long = 4500L) {
        _isSeekbarVisible.value = true
        videoRenderer?.setSeekbarVisible(true)
        videoRenderer?.updateSeekbar(
            _currentPosition.value,
            _duration.value,
            _isPlaying.value
        )
        hideSeekbarJob?.cancel()
        hideSeekbarJob = scope.launch {
            delay(autoHideMs)
            _isSeekbarVisible.value = false
            videoRenderer?.setSeekbarVisible(false)
        }
    }

    fun hideSeekbar() {
        hideSeekbarJob?.cancel()
        _isSeekbarVisible.value = false
        videoRenderer?.setSeekbarVisible(false)
    }

    fun toggleSeekbar() {
        if (_isSeekbarVisible.value) {
            hideSeekbar()
        } else {
            showSeekbar()
        }
    }

    fun togglePlayPause() {
        scope.launch(Dispatchers.Main) {
            val player = exoPlayer ?: return@launch
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
            showSeekbar()
        }
    }

    fun play() {
        scope.launch(Dispatchers.Main) {
            val player = exoPlayer ?: return@launch
            player.play()
            showSeekbar()
        }
    }

    fun pause() {
        scope.launch(Dispatchers.Main) {
            val player = exoPlayer ?: return@launch
            player.pause()
            showSeekbar()
        }
    }

    fun seekRelative(deltaMs: Long) {
        scope.launch(Dispatchers.Main) {
            val player = exoPlayer ?: return@launch
            val cur = player.currentPosition
            val dur = player.duration.coerceAtLeast(0L)
            val target = (cur + deltaMs).coerceIn(0L, dur)
            seekTo(target)
        }
    }

    fun seekTo(positionMs: Long) {
        scope.launch(Dispatchers.Main) {
            val player = exoPlayer ?: return@launch
            val dur = player.duration.coerceAtLeast(0L)
            val target = positionMs.coerceIn(0L, dur)
            player.seekTo(target)
            _currentPosition.value = target
            if (_isSeekbarVisible.value) {
                videoRenderer?.updateSeekbar(target, dur, player.isPlaying)
                showSeekbar()
            }
            YouTubeLoungeReceiver.reportCurrentState()
        }
    }

    fun stop() {
        scope.launch(Dispatchers.Main) {
            resolveJob?.cancel()
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
            _currentVideo.value = null
            _streamInfo.value = null
            _isPlaying.value = false
            _isLoading.value = false
            _statusText.value = "Zaustavljeno"
            videoRenderer?.clearSurface()
            YouTubeLoungeReceiver.reportCurrentState()
        }
    }

    fun release() {
        scope.launch(Dispatchers.Main) {
            resolveJob?.cancel()
            exoPlayer?.release()
            exoPlayer = null
            videoRenderer?.release()
            videoRenderer = null
        }
    }
}
