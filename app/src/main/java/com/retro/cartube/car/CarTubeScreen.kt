package com.retro.cartube.car

import android.graphics.Rect
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import com.retro.cartube.R
import com.retro.cartube.lounge.YouTubeLoungeReceiver
import com.retro.cartube.player.CarTubePlaybackManager
import kotlinx.coroutines.*

class CarTubeScreen(carContext: CarContext) : Screen(carContext) {

    private var activeSurface: Surface? = null
    private var surfaceWidth = 1920
    private var surfaceHeight = 720
    private var visibleAreaBounds = Rect(0, 0, 1920, 720)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastScrollTime = 0L

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            val surface = container.surface ?: return
            activeSurface = surface
            if (container.width > 0) surfaceWidth = container.width
            if (container.height > 0) surfaceHeight = container.height
            visibleAreaBounds = Rect(0, 0, surfaceWidth, surfaceHeight)
            android.util.Log.i("CarTubeScreen", "onSurfaceAvailable: ${surfaceWidth}x${surfaceHeight}")

            // Attach pure hardware surface directly to ExoPlayer - NEVER lock canvas on it!
            CarTubePlaybackManager.attachSurface(carContext, surface, surfaceWidth, surfaceHeight)
            invalidate()
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            // Android Auto calls onVisibleAreaChanged to inform navigation apps where host UI overlays are.
            // We deliberately DO NOT resize surfaceHeight here because visibleArea excludes ActionStrip (top 112px),
            // which caused empty space above the video. The video surface spans the full physical display.
        }

        override fun onSurfaceDestroyed(container: SurfaceContainer) {
            android.util.Log.i("CarTubeScreen", "onSurfaceDestroyed")
            CarTubePlaybackManager.detachSurface()
            activeSurface = null
            invalidate()
        }

        override fun onClick(x: Float, y: Float) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastScrollTime < 250L) return

            val isVideoReady = CarTubePlaybackManager.streamInfo.value != null
            if (!isVideoReady) {
                CarTubePlaybackManager.togglePlayPause()
                return
            }

            val isSeekbarVisible = CarTubePlaybackManager.isSeekbarVisible.value
            val seekbarTouchZoneHeight = 125f // pixels from bottom of car screen
            val isTouchInSeekbar = y >= (surfaceHeight - seekbarTouchZoneHeight)

            if (!isSeekbarVisible) {
                // First tap on screen: reveal the sleek seekbar and timestamps!
                CarTubePlaybackManager.showSeekbar()
            } else {
                // Seekbar is already visible
                if (isTouchInSeekbar) {
                    // Tap on the seekbar: seek directly to touched position!
                    val trackLeft = 50f
                    val trackRight = (surfaceWidth - 50f).coerceAtLeast(trackLeft + 10f)
                    val fraction = ((x - trackLeft) / (trackRight - trackLeft)).coerceIn(0f, 1f)
                    val dur = CarTubePlaybackManager.duration.value
                    if (dur > 0L) {
                        val targetMs = (fraction * dur).toLong()
                        CarTubePlaybackManager.seekTo(targetMs)
                    }
                } else {
                    // Tap in video area above seekbar: toggle play/pause or quick rewind/ffwd
                    val w = surfaceWidth.toFloat()
                    when {
                        x < w * 0.25f -> CarTubePlaybackManager.seekRelative(-10000L)
                        x > w * 0.75f -> CarTubePlaybackManager.seekRelative(10000L)
                        else -> CarTubePlaybackManager.togglePlayPause()
                    }
                }
            }
        }

        override fun onScroll(distanceX: Float, distanceY: Float) {
            lastScrollTime = android.os.SystemClock.elapsedRealtime()
            // Support BMW iDrive rotary knob turning or dragging across screen
            val delta = if (Math.abs(distanceX) >= Math.abs(distanceY)) distanceX else distanceY
            if (delta > 4f) {
                CarTubePlaybackManager.seekRelative(10000L)
            } else if (delta < -4f) {
                CarTubePlaybackManager.seekRelative(-10000L)
            }
        }

        override fun onFling(velocityX: Float, velocityY: Float) {
            lastScrollTime = android.os.SystemClock.elapsedRealtime()
            val delta = if (Math.abs(velocityX) >= Math.abs(velocityY)) velocityX else velocityY
            if (delta > 25f) {
                CarTubePlaybackManager.seekRelative(15000L)
            } else if (delta < -25f) {
                CarTubePlaybackManager.seekRelative(-15000L)
            }
        }
    }

    init {
        try {
            val appManager = carContext.getCarService(AppManager::class.java)
            appManager.setSurfaceCallback(surfaceCallback)
        } catch (e: Throwable) {
            android.util.Log.w("CarTubeScreen", "Error setting surface callback", e)
        }

        CarTubePlaybackManager.initialize(carContext)

        scope.launch {
            CarTubePlaybackManager.isPlaying.collect { invalidate() }
        }
        scope.launch {
            CarTubePlaybackManager.isLoading.collect { invalidate() }
        }
        scope.launch {
            CarTubePlaybackManager.currentVideo.collect { invalidate() }
        }
        scope.launch {
            CarTubePlaybackManager.statusText.collect { invalidate() }
        }
        scope.launch {
            CarTubePlaybackManager.streamInfo.collect { invalidate() }
        }
        scope.launch {
            CarTubePlaybackManager.scaleMode.collect { invalidate() }
        }
        scope.launch {
            CarTubePlaybackManager.videoSyncDelay.collect { invalidate() }
        }
        scope.launch {
            CarTubePlaybackManager.videoQuality.collect { invalidate() }
        }
        scope.launch {
            CarTubePlaybackManager.isSeekbarVisible.collect { invalidate() }
        }
        scope.launch {
            YouTubeLoungeReceiver.pairingCode.collect { invalidate() }
        }
        scope.launch {
            YouTubeLoungeReceiver.connectedClient.collect { invalidate() }
        }
    }

    override fun onGetTemplate(): Template {
        val stripBuilder = ActionStrip.Builder()
        val mapStripBuilder = ActionStrip.Builder()

        val isPlaying = CarTubePlaybackManager.isPlaying.value
        val hasActiveStream = CarTubePlaybackManager.streamInfo.value != null
        val queued = CarTubePlaybackManager.currentVideo.value
        val isLoading = CarTubePlaybackManager.isLoading.value

        val appIcon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.drawable.ic_app_icon)
        ).build()

        val templateBuilder = NavigationTemplate.Builder()
            .setBackgroundColor(CarColor.createCustom(0xFF000000.toInt(), 0xFF000000.toInt()))

        if (hasActiveStream) {
            val rewIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, android.R.drawable.ic_media_rew)).build()
            val ffIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, android.R.drawable.ic_media_ff)).build()
            val playPauseIcon = CarIcon.Builder(
                IconCompat.createWithResource(
                    carContext,
                    if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                )
            ).build()
            val stopIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_stop)).build()
            val scaleIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_aspect_ratio)).build()
            val qualityIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_hd)).build()
            val syncIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_sync)).build()

            // 1. TOP ACTION STRIP (Gore: Play/Pause, Previous, Next)
            // User requested order: Gore desno play / pause, previous, next (clean icons, no text)
            stripBuilder.addAction(
                Action.Builder()
                    .setIcon(playPauseIcon)
                    .setOnClickListener { CarTubePlaybackManager.togglePlayPause() }
                    .build()
            )

            stripBuilder.addAction(
                Action.Builder()
                    .setIcon(rewIcon)
                    .setOnClickListener { CarTubePlaybackManager.seekRelative(-10000L) }
                    .build()
            )

            stripBuilder.addAction(
                Action.Builder()
                    .setIcon(ffIcon)
                    .setOnClickListener { CarTubePlaybackManager.seekRelative(10000L) }
                    .build()
            )

            stripBuilder.addAction(
                Action.Builder()
                    .setIcon(syncIcon)
                    .setOnClickListener {
                        val next = CarTubePlaybackManager.cycleVideoSyncDelay()
                        androidx.car.app.CarToast.makeText(carContext, "Odgoda videa: ${next.label}", androidx.car.app.CarToast.LENGTH_SHORT).show()
                        invalidate()
                    }
                    .build()
            )

            // 2. SIDE ACTION STRIP (Ispod desno: Fit/Stretch, Quality, Kill video)
            // User requested order: Fit/Stretch, Quality, Kill video (clean icons, no text)
            mapStripBuilder.addAction(
                Action.Builder()
                    .setIcon(scaleIcon)
                    .setOnClickListener {
                        val next = CarTubePlaybackManager.cycleScaleMode()
                        androidx.car.app.CarToast.makeText(carContext, "Prikaz: ${next.label}", androidx.car.app.CarToast.LENGTH_SHORT).show()
                        invalidate()
                    }
                    .build()
            )

            mapStripBuilder.addAction(
                Action.Builder()
                    .setIcon(qualityIcon)
                    .setOnClickListener {
                        val next = CarTubePlaybackManager.cycleVideoQuality(carContext)
                        androidx.car.app.CarToast.makeText(carContext, "Kvaliteta: ${next.label}", androidx.car.app.CarToast.LENGTH_SHORT).show()
                        invalidate()
                    }
                    .build()
            )

            mapStripBuilder.addAction(
                Action.Builder()
                    .setIcon(stopIcon)
                    .setOnClickListener {
                        CarTubePlaybackManager.stop()
                        androidx.car.app.CarToast.makeText(carContext, "Video zaustavljen", androidx.car.app.CarToast.LENGTH_SHORT).show()
                        invalidate()
                    }
                    .build()
            )

            // Action.PAN is mandatory for Car App Library to forward touch/gesture events to SurfaceCallback
            mapStripBuilder.addAction(Action.PAN)
        } else {
            // Action.PAN is mandatory for SurfaceCallback
            mapStripBuilder.addAction(Action.PAN)
            // Idle or loading state: Show clean native Android Auto message card
            val messageInfo = if (isLoading) {
                stripBuilder.addAction(
                    Action.Builder()
                        .setTitle("⏳ Učitavanje...")
                        .setOnClickListener { invalidate() }
                        .build()
                )
                MessageInfo.Builder("Učitavam YouTube video...")
                    .setText(queued?.title ?: "Dohvaćanje video stream-a...")
                    .setImage(appIcon)
                    .build()
            } else if (queued != null) {
                stripBuilder.addAction(
                    Action.Builder()
                        .setTitle("▶ Pokreni Video")
                        .setOnClickListener {
                            activeSurface?.let { s ->
                                CarTubePlaybackManager.attachSurface(carContext, s)
                            }
                            invalidate()
                        }
                        .build()
                )
                MessageInfo.Builder(queued.title)
                    .setText(if (queued.channel.isNotBlank()) queued.channel else "Kliknite 'Pokreni Video' na ekranu ili mobitelu")
                    .setImage(appIcon)
                    .build()
            } else {
                val clientName = YouTubeLoungeReceiver.connectedClient.value
                val code = YouTubeLoungeReceiver.pairingCode.value

                stripBuilder.addAction(
                    Action.Builder()
                        .setTitle("🔄 Osvježi Kod")
                        .setOnClickListener {
                            YouTubeLoungeReceiver.refreshPairingCode(force = true)
                            invalidate()
                        }
                        .build()
                )

                if (clientName != null) {
                    MessageInfo.Builder("BMW CarTube")
                        .setText("Povezano s: $clientName\nOdaberite video u YouTube ReVanced za reprodukciju")
                        .setImage(appIcon)
                        .build()
                } else if (code.isNotBlank()) {
                    MessageInfo.Builder("BMW CarTube - TV Kod")
                        .setText("Kod: $code\n(ReVanced ➔ Gledajte na TV-u ➔ Povežite kodom za TV)")
                        .setImage(appIcon)
                        .build()
                } else {
                    MessageInfo.Builder("BMW CarTube")
                        .setText("Učitavanje TV koda...\nIli podijelite video iz YouTube ReVanced")
                        .setImage(appIcon)
                        .build()
                }
            }

            templateBuilder.setNavigationInfo(messageInfo)
        }

        return templateBuilder
            .setActionStrip(stripBuilder.build())
            .setMapActionStrip(mapStripBuilder.build())
            .setPanModeListener { isInPanMode ->
                android.util.Log.d("CarTubeScreen", "Pan mode state: $isInPanMode")
            }
            .build()
    }
}
