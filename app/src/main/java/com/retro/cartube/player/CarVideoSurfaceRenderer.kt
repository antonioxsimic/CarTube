package com.retro.cartube.player

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

enum class VideoScaleMode(val label: String, val shortName: String) {
    FIT("Fit (16:9)", "📐 Fit"),
    BEST_FIT("Best Fit (Zoom)", "🔍 Best Fit"),
    STRETCH("Stretch", "↔ Stretch")
}

class CarVideoSurfaceRenderer(context: Context) {

    private val TAG = "CarVideoRenderer"
    private val prefs: SharedPreferences = context.getSharedPreferences("cartube_prefs", Context.MODE_PRIVATE)

    // Current scaling mode (Default is FIT as requested by user)
    var scaleMode: VideoScaleMode = loadSavedScaleMode()
        private set

    private var videoWidth: Int = 1920
    private var videoHeight: Int = 1080
    private var screenWidth: Int = 1920
    private var screenHeight: Int = 720

    // Render thread & handler
    private val renderThread = HandlerThread("CarVideoGLThread").apply { start() }
    private val renderHandler = Handler(renderThread.looper)

    // EGL objects
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    // Video GL Objects
    private var textureId: Int = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var glProgram: Int = 0

    // Video Shader locations
    private var aPositionLoc: Int = -1
    private var aTexCoordLoc: Int = -1
    private var uTexMatrixLoc: Int = -1
    private var uScaleXLoc: Int = -1
    private var uScaleYLoc: Int = -1

    private val texMatrix = FloatArray(16)
    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    private var targetCarSurface: Surface? = null

    // Seekbar Overlay GL & Canvas Objects
    private var overlayGlProgram: Int = 0
    private var aOverlayPosLoc: Int = -1
    private var aOverlayTexCoordLoc: Int = -1
    private var uOverlayTextureLoc: Int = -1
    private var overlayTextureId: Int = 0

    private var overlayVertexBuffer: FloatBuffer? = null
    private var overlayTexCoordBuffer: FloatBuffer? = null

    private var overlayBitmap: Bitmap? = null
    private var overlayCanvas: Canvas? = null
    val overlayHeight = 115 // pixels

    var isSeekbarVisible: Boolean = false
        private set

    // Cached paints for sleek seekbar rendering
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 25f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        setShadowLayer(5f, 0f, 2f, Color.argb(200, 0, 0, 0))
    }
    private val trackBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 255, 255, 255)
    }
    private val trackPlayedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF0033")
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF0033")
        setShadowLayer(6f, 0f, 2f, Color.argb(180, 0, 0, 0))
    }
    private val thumbCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }

    init {
        val quadCoords = floatArrayOf(
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f
        )
        val texCoords = floatArrayOf(
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f
        )

        vertexBuffer = ByteBuffer.allocateDirect(quadCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(quadCoords)
        vertexBuffer.position(0)

        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(texCoords)
        texCoordBuffer.position(0)

        // Initialize EGL & SurfaceTexture on render thread
        renderHandler.post {
            initEglBase()
        }
    }

    private fun loadSavedScaleMode(): VideoScaleMode {
        val saved = prefs.getString("scale_mode", VideoScaleMode.FIT.name) ?: VideoScaleMode.FIT.name
        return try {
            VideoScaleMode.valueOf(saved)
        } catch (_: Exception) {
            VideoScaleMode.FIT
        }
    }

    fun setScaleMode(mode: VideoScaleMode) {
        scaleMode = mode
        prefs.edit().putString("scale_mode", mode.name).apply()
        Log.i(TAG, "Scale mode set to: ${mode.label}")
        renderHandler.post {
            drawCurrentFrame()
        }
    }

    fun cycleScaleMode(): VideoScaleMode {
        val modes = VideoScaleMode.values()
        val next = modes[(scaleMode.ordinal + 1) % modes.size]
        setScaleMode(next)
        return next
    }

    fun setVideoSize(width: Int, height: Int) {
        if (width > 0 && height > 0 && (videoWidth != width || videoHeight != height)) {
            videoWidth = width
            videoHeight = height
            Log.i(TAG, "Video size updated: ${width}x${height} (Aspect: ${width.toFloat() / height.toFloat()})")
            renderHandler.post {
                drawCurrentFrame()
            }
        }
    }

    fun setScreenSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            screenWidth = width
            screenHeight = height
            renderHandler.post {
                updateOverlayQuadBuffers()
                drawCurrentFrame()
            }
        }
    }

    fun setSeekbarVisible(visible: Boolean) {
        if (isSeekbarVisible != visible) {
            isSeekbarVisible = visible
            renderHandler.post {
                drawCurrentFrame()
            }
        }
    }

    fun updateSeekbar(currentPosMs: Long, durationMs: Long, isPlaying: Boolean) {
        renderHandler.post {
            if (isSeekbarVisible) {
                drawSeekbarToBitmap(currentPosMs, durationMs, isPlaying)
                drawCurrentFrame()
            }
        }
    }

    /**
     * Attaches or detaches the car surface from Android Auto
     */
    fun setCarSurface(carSurface: Surface?, width: Int, height: Int) {
        targetCarSurface = carSurface
        if (width > 0) screenWidth = width
        if (height > 0) screenHeight = height

        renderHandler.post {
            destroyCarEglSurface()
            if (carSurface != null && carSurface.isValid) {
                createCarEglSurface(carSurface)
                drawCurrentFrame()
            }
        }
    }

    /**
     * Returns the Surface that ExoPlayer should decode into
     */
    fun getInputSurface(): Surface? {
        return inputSurface
    }

    private fun initEglBase() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "eglGetDisplay failed")
            return
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.e(TAG, "eglInitialize failed")
            return
        }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        eglConfig = configs[0]

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "eglCreateContext failed")
            return
        }

        // Create 1x1 pbuffer surface so context is valid before car surface arrives
        val pbufferAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        val pbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0)
        EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)

        // Setup GL Shaders & Program
        glProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPositionLoc = GLES20.glGetAttribLocation(glProgram, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(glProgram, "aTexCoord")
        uTexMatrixLoc = GLES20.glGetUniformLocation(glProgram, "uTexMatrix")
        uScaleXLoc = GLES20.glGetUniformLocation(glProgram, "uScaleX")
        uScaleYLoc = GLES20.glGetUniformLocation(glProgram, "uScaleY")

        // Setup OES External Texture
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // Setup Overlay Shaders & Texture
        overlayGlProgram = createProgram(OVERLAY_VERTEX_SHADER, OVERLAY_FRAGMENT_SHADER)
        aOverlayPosLoc = GLES20.glGetAttribLocation(overlayGlProgram, "aPosition")
        aOverlayTexCoordLoc = GLES20.glGetAttribLocation(overlayGlProgram, "aTexCoord")
        uOverlayTextureLoc = GLES20.glGetUniformLocation(overlayGlProgram, "uOverlayTexture")

        val overlayTextures = IntArray(1)
        GLES20.glGenTextures(1, overlayTextures, 0)
        overlayTextureId = overlayTextures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        updateOverlayQuadBuffers()

        // Create SurfaceTexture for ExoPlayer input
        surfaceTexture = SurfaceTexture(textureId).apply {
            setOnFrameAvailableListener({
                renderHandler.post {
                    drawCurrentFrame()
                }
            }, renderHandler)
        }
        inputSurface = Surface(surfaceTexture)
        Log.i(TAG, "CarVideoSurfaceRenderer initialized successfully with input surface")

        // Clean up temporary pbuffer
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
    }

    private fun createCarEglSurface(carSurface: Surface) {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglConfig == null) return

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, carSurface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "eglCreateWindowSurface failed for car surface: ${EGL14.eglGetError()}")
            return
        }

        // Query physical hardware surface dimensions directly from EGL
        val widthArr = IntArray(1)
        val heightArr = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, widthArr, 0)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, heightArr, 0)
        if (widthArr[0] > 0 && heightArr[0] > 0) {
            screenWidth = widthArr[0]
            screenHeight = heightArr[0]
            Log.i(TAG, "Queried real EGL surface dimensions: ${screenWidth}x${screenHeight}")
        }
        updateOverlayQuadBuffers()

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        Log.i(TAG, "Connected car surface to EGL (${screenWidth}x${screenHeight})")
    }

    private fun destroyCarEglSurface() {
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
            Log.i(TAG, "Destroyed car EGL surface")
        }
    }

    fun clearSurface() {
        renderHandler.post {
            try {
                if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
                    if (EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error clearing surface in GL", e)
            }
        }
    }

    private fun drawCurrentFrame() {
        val st = surfaceTexture ?: return
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return

        try {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)

            val screenW = screenWidth.toFloat()
            val screenH = screenHeight.toFloat()
            GLES20.glViewport(0, 0, screenWidth, screenHeight)

            // Pure OLED Pitch Black (#000000) for clean letterboxing and zero background distraction
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // Compute precise scale factors based on selected mode
            var scaleX = 1.0f
            var scaleY = 1.0f

            val vidW = if (videoWidth > 0) videoWidth.toFloat() else 16.0f
            val vidH = if (videoHeight > 0) videoHeight.toFloat() else 9.0f

            if (screenW > 0 && screenH > 0 && vidW > 0 && vidH > 0) {
                val screenAspect = screenW / screenH
                val videoAspect = vidW / vidH

                when (scaleMode) {
                    VideoScaleMode.FIT -> {
                        // Fit inside screen, preserving aspect ratio (letterbox/pillarbox)
                        if (screenAspect > videoAspect) {
                            // Ultra-wide BMW screen case: Pillarbox left & right
                            scaleX = videoAspect / screenAspect
                            scaleY = 1.0f
                        } else {
                            // Taller screen case: Letterbox top & bottom
                            scaleX = 1.0f
                            scaleY = screenAspect / videoAspect
                        }
                    }
                    VideoScaleMode.BEST_FIT -> {
                        // Zoom/Crop to fill entire display without distortion
                        if (screenAspect > videoAspect) {
                            // Fill full width, crop slightly top/bottom
                            scaleX = 1.0f
                            scaleY = screenAspect / videoAspect
                        } else {
                            scaleX = videoAspect / screenAspect
                            scaleY = 1.0f
                        }
                    }
                    VideoScaleMode.STRETCH -> {
                        // Stretch across all corners
                        scaleX = 1.0f
                        scaleY = 1.0f
                    }
                }
            }

            GLES20.glUseProgram(glProgram)

            GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
            GLES20.glUniform1f(uScaleXLoc, scaleX)
            GLES20.glUniform1f(uScaleYLoc, scaleY)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

            vertexBuffer.position(0)
            GLES20.glEnableVertexAttribArray(aPositionLoc)
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

            texCoordBuffer.position(0)
            GLES20.glEnableVertexAttribArray(aTexCoordLoc)
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(aPositionLoc)
            GLES20.glDisableVertexAttribArray(aTexCoordLoc)

            // Render Seekbar Overlay if visible
            if (isSeekbarVisible && overlayGlProgram != 0 && overlayTextureId != 0) {
                val vBuf = overlayVertexBuffer
                val tBuf = overlayTexCoordBuffer
                if (vBuf != null && tBuf != null) {
                    GLES20.glEnable(GLES20.GL_BLEND)
                    GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

                    GLES20.glUseProgram(overlayGlProgram)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
                    GLES20.glUniform1i(uOverlayTextureLoc, 1)

                    vBuf.position(0)
                    GLES20.glEnableVertexAttribArray(aOverlayPosLoc)
                    GLES20.glVertexAttribPointer(aOverlayPosLoc, 2, GLES20.GL_FLOAT, false, 0, vBuf)

                    tBuf.position(0)
                    GLES20.glEnableVertexAttribArray(aOverlayTexCoordLoc)
                    GLES20.glVertexAttribPointer(aOverlayTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, tBuf)

                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                    GLES20.glDisableVertexAttribArray(aOverlayPosLoc)
                    GLES20.glDisableVertexAttribArray(aOverlayTexCoordLoc)

                    GLES20.glDisable(GLES20.GL_BLEND)
                }
            }

            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } catch (e: Throwable) {
            Log.e(TAG, "Error drawing frame in GL", e)
        }
    }

    fun release() {
        renderHandler.post {
            destroyCarEglSurface()
            if (glProgram != 0) {
                GLES20.glDeleteProgram(glProgram)
                glProgram = 0
            }
            if (textureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                textureId = 0
            }
            if (overlayGlProgram != 0) {
                GLES20.glDeleteProgram(overlayGlProgram)
                overlayGlProgram = 0
            }
            if (overlayTextureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(overlayTextureId), 0)
                overlayTextureId = 0
            }
            overlayBitmap?.recycle()
            overlayBitmap = null
            overlayCanvas = null

            surfaceTexture?.release()
            surfaceTexture = null
            inputSurface?.release()
            inputSurface = null

            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglTerminate(eglDisplay)
                eglDisplay = EGL14.EGL_NO_DISPLAY
            }
            renderThread.quitSafely()
            Log.i(TAG, "CarVideoSurfaceRenderer released")
        }
    }

    private fun updateOverlayQuadBuffers() {
        val h = if (screenHeight > 0) screenHeight.toFloat() else 720f
        val ndcHeight = (overlayHeight.toFloat() / h) * 2.0f

        val quadCoords = floatArrayOf(
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f, -1.0f + ndcHeight,
             1.0f, -1.0f + ndcHeight
        )
        val texCoords = floatArrayOf(
            0.0f, 1.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f
        )

        overlayVertexBuffer = ByteBuffer.allocateDirect(quadCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(quadCoords).apply { position(0) }

        overlayTexCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(texCoords).apply { position(0) }
    }

    private fun drawSeekbarToBitmap(currentPosMs: Long, durationMs: Long, isPlaying: Boolean) {
        val w = if (screenWidth > 0) screenWidth else 1920
        val h = overlayHeight

        if (overlayBitmap == null || overlayBitmap?.width != w) {
            overlayBitmap?.recycle()
            overlayBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            overlayCanvas = Canvas(overlayBitmap!!)
            bgPaint.shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                Color.TRANSPARENT,
                Color.argb(220, 0, 0, 0),
                Shader.TileMode.CLAMP
            )
        }

        val canvas = overlayCanvas ?: return
        val bmp = overlayBitmap ?: return

        // Clear previous frame
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // 1. Sleek bottom gradient
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        // 2. Time text: e.g. "04:15 / 18:30"
        val curStr = formatTime(currentPosMs)
        val durStr = formatTime(durationMs)
        val timeString = "$curStr / $durStr"
        val trackLeft = 50f
        val trackRight = w - 50f
        val trackTop = 64f
        val trackBottom = 74f

        canvas.drawText(timeString, trackLeft, 44f, textPaint)

        if (!isPlaying) {
            val pausedPaint = Paint(textPaint).apply {
                color = Color.parseColor("#4DEEEA")
                textSize = 21f
            }
            val pausedStr = "❚❚ PAUZA"
            val pWidth = pausedPaint.measureText(pausedStr)
            canvas.drawText(pausedStr, trackRight - pWidth, 44f, pausedPaint)
        }

        // 3. Track Background
        canvas.drawRoundRect(trackLeft, trackTop, trackRight, trackBottom, 5f, 5f, trackBgPaint)

        // 4. Played progress (YouTube Red)
        val progress = if (durationMs > 0L) (currentPosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
        val playedRight = trackLeft + progress * (trackRight - trackLeft)
        if (playedRight > trackLeft) {
            canvas.drawRoundRect(trackLeft, trackTop, playedRight, trackBottom, 5f, 5f, trackPlayedPaint)
        }

        // 5. Thumb Circle
        val thumbY = (trackTop + trackBottom) / 2f
        canvas.drawCircle(playedRight, thumbY, 11f, thumbPaint)
        canvas.drawCircle(playedRight, thumbY, 4f, thumbCenterPaint)

        // Upload to GL Texture
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
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

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            uniform float uScaleX;
            uniform float uScaleY;
            varying vec2 vTexCoord;

            void main() {
                gl_Position = vec4(aPosition.x * uScaleX, aPosition.y * uScaleY, 0.0, 1.0);
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;

            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        private const val OVERLAY_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;

            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val OVERLAY_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uOverlayTexture;

            void main() {
                gl_FragColor = texture2D(uOverlayTexture, vTexCoord);
            }
        """
    }
}
