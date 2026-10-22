package com.retro.cartube.lounge

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.retro.cartube.player.CarTubePlaybackManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object YouTubeLoungeReceiver {

    private const val TAG = "YouTubeLoungeReceiver"
    private const val PREFS_NAME = "cartube_lounge_prefs"
    private const val KEY_SCREEN_ID = "lounge_screen_id"
    private const val KEY_LOUNGE_TOKEN = "lounge_token"
    private const val KEY_PAIRING_CODE = "lounge_pairing_code"
    private const val DEVICE_NAME = "BMW CarTube"
    private const val ENCODED_DEVICE_NAME = "BMW%20CarTube"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var loopJob: Job? = null
    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    // OkHttpClient with 65s read timeout for long-poll BrowserChannel stream
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(65, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // StateFlows for UI (Phone Companion & In-Car AA screen)
    private val _pairingCode = MutableStateFlow("")
    val pairingCode = _pairingCode.asStateFlow()

    private val _rawPairingCode = MutableStateFlow("")
    val rawPairingCode = _rawPairingCode.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _connectedClient = MutableStateFlow<String?>(null)
    val connectedClient = _connectedClient.asStateFlow()

    private val _statusMessage = MutableStateFlow("Inicijalizacija TV koda...")
    val statusMessage = _statusMessage.asStateFlow()

    // Lounge Session state
    private var screenId: String? = null
    private var loungeToken: String? = null
    private var sid: String? = null
    private var gsessionid: String? = null
    private var lastAid = 0L
    private val ridCounter = AtomicLong(1L)
    private var commandOffset = 0
    private val postMutex = Mutex()
    private var currentCpn = generateRandomCpn()
    private var syncJob: Job? = null

    @Synchronized
    fun start(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        if (loopJob?.isActive == true) {
            return
        }

        startPeriodicSyncLoop()

        loopJob = scope.launch {
            try {
                initCredentials()
                runLoungeLoop()
            } catch (e: CancellationException) {
                Log.i(TAG, "Lounge receiver stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Fatal exception in Lounge receiver", e)
            }
        }
    }

    fun stop() {
        syncJob?.cancel()
        syncJob = null
        loopJob?.cancel()
        loopJob = null
        sid = null
        gsessionid = null
        _isConnected.value = false
        _connectedClient.value = null
    }

    private fun generateRandomCpn(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        return (1..16).map { chars.random() }.joinToString("")
    }

    private fun startPeriodicSyncLoop() {
        syncJob?.cancel()
        syncJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1500)
                try {
                    if (sid != null && gsessionid != null) {
                        val video = CarTubePlaybackManager.currentVideo.value
                        val isPlaying = CarTubePlaybackManager.isPlaying.value
                        if (video != null && isPlaying) {
                            reportPlaybackProgress()
                        }
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.d(TAG, "Sync loop exception: ${e.message}")
                }
            }
        }
    }

    fun refreshPairingCode(force: Boolean = true) {
        scope.launch {
            try {
                _statusMessage.value = "Generiram novi TV kod..."
                val sId = screenId ?: getOrCreateScreenId()
                val token = loungeToken ?: getOrCreateLoungeToken(sId)
                val code = fetchPairingCode(sId, token)
                if (code.isNotBlank()) {
                    setPairingCode(code)
                    _statusMessage.value = "Spreman za povezivanje"
                    Log.i(TAG, "New pairing code generated: $code")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh pairing code", e)
                _statusMessage.value = "Greška kod dohvaćanja koda: ${e.message}"
            }
        }
    }

    private suspend fun initCredentials() {
        _statusMessage.value = "Dohvaćanje YouTube Lounge ID..."
        val sId = getOrCreateScreenId()
        screenId = sId

        val token = getOrCreateLoungeToken(sId)
        loungeToken = token

        // Load cached pairing code or fetch fresh one
        val cachedCode = prefs?.getString(KEY_PAIRING_CODE, null)
        if (!cachedCode.isNullOrBlank()) {
            setPairingCode(cachedCode)
        } else {
            val code = fetchPairingCode(sId, token)
            setPairingCode(code)
        }
    }

    private fun setPairingCode(rawCode: String) {
        _rawPairingCode.value = rawCode
        prefs?.edit()?.putString(KEY_PAIRING_CODE, rawCode)?.apply()

        // Format as XXX XXX XXX XXX
        val formatted = if (rawCode.length == 12) {
            "${rawCode.substring(0, 3)} ${rawCode.substring(3, 6)} ${rawCode.substring(6, 9)} ${rawCode.substring(9, 12)}"
        } else {
            rawCode
        }
        _pairingCode.value = formatted
    }

    private suspend fun getOrCreateScreenId(): String = withContext(Dispatchers.IO) {
        val existing = prefs?.getString(KEY_SCREEN_ID, null)
        if (!existing.isNullOrBlank()) {
            return@withContext existing
        }

        try {
            val req = Request.Builder()
                .url("https://www.youtube.com/api/lounge/pairing/generate_screen_id")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string()?.trim().orEmpty()
                if (resp.isSuccessful && body.isNotBlank()) {
                    prefs?.edit()?.putString(KEY_SCREEN_ID, body)?.apply()
                    Log.i(TAG, "Generated new screen ID: $body")
                    return@withContext body
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to call generate_screen_id, generating fallback", e)
        }

        // Fallback random 32 char lowercase alphanumeric ID
        val fallback = generateRandomScreenId()
        prefs?.edit()?.putString(KEY_SCREEN_ID, fallback)?.apply()
        fallback
    }

    private fun generateRandomScreenId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..32).map { chars.random() }.joinToString("")
    }

    private suspend fun getOrCreateLoungeToken(sId: String): String = withContext(Dispatchers.IO) {
        val existing = prefs?.getString(KEY_LOUNGE_TOKEN, null)
        if (!existing.isNullOrBlank()) {
            return@withContext existing
        }

        val form = FormBody.Builder()
            .add("screen_ids", sId)
            .build()

        val req = Request.Builder()
            .url("https://www.youtube.com/api/lounge/pairing/get_lounge_token_batch")
            .post(form)
            .build()

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful && body.isNotBlank()) {
                val json = JSONObject(body)
                val screens = json.getJSONArray("screens")
                if (screens.length() > 0) {
                    val token = screens.getJSONObject(0).getString("loungeToken")
                    prefs?.edit()?.putString(KEY_LOUNGE_TOKEN, token)?.apply()
                    Log.i(TAG, "Acquired lounge token successfully")
                    return@withContext token
                }
            }
            throw IOException("Failed to get lounge token: HTTP ${resp.code} body: $body")
        }
    }

    private suspend fun fetchPairingCode(sId: String, token: String): String = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("access_type", "permanent")
            .add("screen_id", sId)
            .add("device_id", sId)
            .add("lounge_token", token)
            .add("screen_name", DEVICE_NAME)
            .build()

        val req = Request.Builder()
            .url("https://www.youtube.com/api/lounge/pairing/get_pairing_code")
            .post(form)
            .build()

        client.newCall(req).execute().use { resp ->
            val code = resp.body?.string()?.trim().orEmpty()
            if (resp.isSuccessful && code.isNotBlank()) {
                return@withContext code
            }
            throw IOException("Failed to get pairing code: HTTP ${resp.code} $code")
        }
    }

    private suspend fun runLoungeLoop() {
        while (scope.isActive) {
            try {
                if (sid == null || gsessionid == null) {
                    _statusMessage.value = "Povezivanje s Lounge poslužiteljem..."
                    performInitialBind()
                }

                _statusMessage.value = "Spreman za emitiranje (Cast)"
                listenForCommands()
            } catch (e: CancellationException) {
                break
            } catch (e: java.net.SocketTimeoutException) {
                Log.d(TAG, "Lounge long-poll read timeout, reconnecting (AID=$lastAid)")
            } catch (e: Exception) {
                Log.e(TAG, "Lounge loop error, reconnecting in 3s: ${e.message}", e)
                _statusMessage.value = "Ponovno povezivanje..."
                sid = null
                gsessionid = null
                lastAid = 0L
                commandOffset = 0
                delay(3000)
            }
        }
    }

    private suspend fun performInitialBind() = withContext(Dispatchers.IO) {
        val sId = screenId ?: throw IllegalStateException("Screen ID missing")
        val token = loungeToken ?: throw IllegalStateException("Lounge token missing")

        lastAid = 0L
        commandOffset = 0
        val initialRid = (41000..49999).random().toLong()
        ridCounter.set(initialRid)
        val rid = ridCounter.getAndIncrement()

        val url = "https://www.youtube.com/api/lounge/bc/bind?device=LOUNGE_SCREEN&theme=cl&capabilities=dsp%2Cmic%2Cdpa%2Cntb&mdx-version=3&VER=8&v=2&RID=$rid&AID=0&name=${ENCODED_DEVICE_NAME}&id=$sId&loungeIdToken=$token&CVER=1"
        val req = Request.Builder()
            .url(url)
            .header("X-YouTube-LoungeId-Token", token)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post("count=0".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string().orEmpty()
                Log.e(TAG, "Initial bind failed HTTP ${resp.code}: $errBody")
                if (resp.code == 401) {
                    prefs?.edit()?.remove(KEY_LOUNGE_TOKEN)?.apply()
                    loungeToken = null
                    initCredentials()
                }
                throw IOException("Initial bind failed with HTTP ${resp.code}: $errBody")
            }
            val source = resp.body?.source() ?: throw IOException("Empty bind body")
            parseChunks(source)
        }
    }

    private suspend fun listenForCommands() = withContext(Dispatchers.IO) {
        val sId = screenId ?: return@withContext
        val token = loungeToken ?: return@withContext
        val currentSid = sid ?: return@withContext
        val currentGsessionid = gsessionid ?: return@withContext

        val url = "https://www.youtube.com/api/lounge/bc/bind?device=LOUNGE_SCREEN&theme=cl&capabilities=dsp%2Cmic%2Cdpa%2Cntb&mdx-version=3&VER=8&v=2&RID=rpc&AID=$lastAid&name=${ENCODED_DEVICE_NAME}&id=$sId&SID=$currentSid&gsessionid=$currentGsessionid&loungeIdToken=$token&CI=0&TYPE=xmlhttp"
        val req = Request.Builder()
            .url(url)
            .header("X-YouTube-LoungeId-Token", token)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post("count=0".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string().orEmpty()
                Log.e(TAG, "RPC listen failed HTTP ${resp.code}: $errBody | AID=$lastAid | SID=$currentSid | URL=$url")
                if (resp.code == 400 || resp.code == 404 || resp.code == 410) {
                    sid = null
                    gsessionid = null
                    lastAid = 0L
                }
                throw IOException("RPC listen failed with HTTP ${resp.code}: $errBody")
            }
            Log.d(TAG, "RPC listen connection established successfully (AID=$lastAid)")
            val source = resp.body?.source() ?: return@use
            parseChunks(source)
            Log.d(TAG, "RPC listen stream closed by server normally")
        }
    }

    private fun parseChunks(source: BufferedSource) {
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val chunkLen = trimmed.toIntOrNull()
            if (chunkLen != null && chunkLen > 0) {
                val jsonText = source.readUtf8(chunkLen.toLong())
                processChunkPayload(jsonText)
            }
        }
    }

    private fun processChunkPayload(jsonText: String) {
        try {
            val root = JSONArray(jsonText)
            for (i in 0 until root.length()) {
                val entry = root.getJSONArray(i)
                val aid = entry.getLong(0)
                if (aid > lastAid) {
                    lastAid = aid
                }
                val cmd = entry.getJSONArray(1)
                val action = cmd.getString(0)
                val params = if (cmd.length() > 1 && !cmd.isNull(1)) {
                    cmd.optJSONObject(1)
                } else null

                handleCommand(action, params, cmd)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing chunk JSON: $jsonText", e)
        }
    }

    private fun handleCommand(action: String, params: JSONObject?, fullCmd: JSONArray) {
        Log.i(TAG, "Lounge command received: $action")
        when (action) {
            "c" -> {
                val newSid = fullCmd.optString(1)
                if (newSid.isNotBlank()) {
                    sid = newSid
                    Log.i(TAG, "Assigned Lounge SID: $newSid")
                }
            }
            "S" -> {
                val newGsession = fullCmd.optString(1)
                if (newGsession.isNotBlank()) {
                    gsessionid = newGsession
                    Log.i(TAG, "Assigned Lounge gsessionid: $newGsession")
                }
            }
            "noop" -> {
                // Heartbeat from server
            }
            "loungeStatus" -> {
                Log.i(TAG, "Lounge status received")
            }
            "remoteConnected" -> {
                val name = params?.optString("clientName", "YouTube ReVanced") ?: "YouTube ReVanced"
                _connectedClient.value = name
                _isConnected.value = true
                _statusMessage.value = "Povezano: $name"
                Log.i(TAG, "Remote client connected: $name")
                reportCurrentState()
                reportVolume(100, false)
            }
            "remoteDisconnected" -> {
                _connectedClient.value = null
                _isConnected.value = false
                _statusMessage.value = "Klijent se odspojio"
                Log.i(TAG, "Remote client disconnected -> immediately stopping and clearing video on car")
                CarTubePlaybackManager.stop()
            }
            "setPlaylist", "updatePlaylist" -> {
                val videoId = params?.optString("videoId")
                if (!videoId.isNullOrBlank()) {
                    currentCpn = generateRandomCpn()
                    val currentTimeSec = params.optDouble("currentTime", 0.0)
                    val startMs = (currentTimeSec * 1000.0).toLong()
                    Log.i(TAG, "▶ Play request from Lounge: $videoId at ${startMs}ms (CPN: $currentCpn)")
                    appContext?.let { ctx ->
                        CarTubePlaybackManager.enqueueOrPlay(ctx, videoId, startMs)
                    }
                    reportCurrentState()
                }
            }
            "seekTo" -> {
                val newTimeSec = params?.optDouble("newTime", 0.0) ?: 0.0
                val targetMs = (newTimeSec * 1000.0).toLong()
                Log.i(TAG, "⏱ Seek request from Lounge: ${targetMs}ms")
                CarTubePlaybackManager.seekTo(targetMs)
                reportCurrentState()
            }
            "play" -> {
                Log.i(TAG, "▶ Play request from Lounge")
                CarTubePlaybackManager.play()
                reportCurrentState()
            }
            "pause" -> {
                Log.i(TAG, "⏸ Pause request from Lounge")
                CarTubePlaybackManager.pause()
                reportCurrentState()
            }
            "stop", "stopVideo", "clearPlaylist", "leave", "gracefulDisconnect" -> {
                Log.i(TAG, "⏹ Stop/Disconnect request from Lounge ($action) -> stopping playback and clearing car screen")
                CarTubePlaybackManager.stop()
                reportCurrentState()
            }
            "setVolume" -> {
                val vol = params?.optInt("volume", 100) ?: 100
                Log.i(TAG, "Volume set from Lounge: $vol")
                reportVolume(vol, false)
            }
            "getNowPlaying" -> {
                reportCurrentState()
            }
            else -> {
                Log.d(TAG, "Unhandled Lounge action: $action")
            }
        }
    }

    /**
     * Reports active video and player state back to Google Lounge in a single batched HTTP request
     * so YouTube ReVanced immediately dismisses the loading spinner and shows player controls.
     */
    fun reportCurrentState() {
        scope.launch(Dispatchers.IO) {
            try {
                val sId = screenId ?: return@launch
                val token = loungeToken ?: return@launch
                val currentSid = sid ?: return@launch
                val currentGsession = gsessionid ?: return@launch

                val video = CarTubePlaybackManager.currentVideo.value
                val isPlaying = CarTubePlaybackManager.isPlaying.value
                val isLoading = CarTubePlaybackManager.isLoading.value
                val posSec = CarTubePlaybackManager.currentPosition.value / 1000.0
                val durSec = CarTubePlaybackManager.duration.value / 1000.0

                if (video != null) {
                    // state: 1 = playing, 2 = paused, 3 = buffering
                    val stateStr = when {
                        isLoading -> "3"
                        isPlaying -> "1"
                        else -> "2"
                    }

                    val commands = listOf(
                        "nowPlaying" to mapOf(
                            "videoId" to video.videoId,
                            "cpn" to currentCpn
                        ),
                        "onStateChange" to mapOf(
                            "state" to stateStr,
                            "currentTime" to String.format(Locale.US, "%.1f", posSec),
                            "duration" to String.format(Locale.US, "%.1f", durSec),
                            "loadedTime" to String.format(Locale.US, "%.1f", durSec),
                            "seekableStartTime" to "0",
                            "seekableEndTime" to String.format(Locale.US, "%.1f", durSec),
                            "cpn" to currentCpn
                        )
                    )
                    postLoungeBatch(sId, token, currentSid, currentGsession, commands)
                } else {
                    val commands = listOf(
                        "onStateChange" to mapOf(
                            "state" to "0",
                            "currentTime" to "0",
                            "duration" to "0",
                            "loadedTime" to "0",
                            "seekableStartTime" to "0",
                            "seekableEndTime" to "0",
                            "cpn" to currentCpn
                        )
                    )
                    postLoungeBatch(sId, token, currentSid, currentGsession, commands)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to report state to Lounge: ${e.message}")
            }
        }
    }

    /**
     * Reports periodic playback progress to Lounge while video is playing
     */
    suspend fun reportPlaybackProgress() = withContext(Dispatchers.IO) {
        val sId = screenId ?: return@withContext
        val token = loungeToken ?: return@withContext
        val currentSid = sid ?: return@withContext
        val currentGsession = gsessionid ?: return@withContext
        val video = CarTubePlaybackManager.currentVideo.value ?: return@withContext
        val posSec = CarTubePlaybackManager.currentPosition.value / 1000.0
        val durSec = CarTubePlaybackManager.duration.value / 1000.0

        val commands = listOf(
            "onStateChange" to mapOf(
                "state" to "1",
                "currentTime" to String.format(Locale.US, "%.1f", posSec),
                "duration" to String.format(Locale.US, "%.1f", durSec),
                "loadedTime" to String.format(Locale.US, "%.1f", durSec),
                "seekableStartTime" to "0",
                "seekableEndTime" to String.format(Locale.US, "%.1f", durSec),
                "cpn" to currentCpn
            )
        )
        postLoungeBatch(sId, token, currentSid, currentGsession, commands)
    }

    fun reportVolume(volume: Int, muted: Boolean) {
        scope.launch(Dispatchers.IO) {
            try {
                val sId = screenId ?: return@launch
                val token = loungeToken ?: return@launch
                val currentSid = sid ?: return@launch
                val currentGsession = gsessionid ?: return@launch

                postLoungeBatch(
                    sId, token, currentSid, currentGsession,
                    listOf(
                        "onVolumeChanged" to mapOf(
                            "volume" to volume.toString(),
                            "muted" to muted.toString()
                        )
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to report volume: ${e.message}")
            }
        }
    }

    private suspend fun postLoungeBatch(
        sId: String,
        token: String,
        currentSid: String,
        currentGsession: String,
        commands: List<Pair<String, Map<String, String>>>
    ) = withContext(Dispatchers.IO) {
        postMutex.withLock {
            val currentOfs = commandOffset
            val ridVal = ridCounter.getAndIncrement()
            commandOffset += commands.size

            val url = "https://www.youtube.com/api/lounge/bc/bind?device=LOUNGE_SCREEN&theme=cl&capabilities=dsp%2Cmic%2Cdpa%2Cntb&mdx-version=3&VER=8&v=2&RID=$ridVal&AID=$lastAid&name=${ENCODED_DEVICE_NAME}&id=$sId&SID=$currentSid&gsessionid=$currentGsession&loungeIdToken=$token"

            val formBuilder = FormBody.Builder()
            formBuilder.add("count", commands.size.toString())
            formBuilder.add("ofs", currentOfs.toString())
            commands.forEachIndexed { index, (cmd, params) ->
                formBuilder.add("req${index}__sc", cmd)
                for ((k, v) in params) {
                    formBuilder.add("req${index}_$k", v)
                }
            }

            val req = Request.Builder()
                .url(url)
                .header("X-YouTube-LoungeId-Token", token)
                .post(formBuilder.build())
                .build()

            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        Log.w(TAG, "postLoungeBatch failed: HTTP ${resp.code} $body (ofs=$currentOfs, AID=$lastAid)")
                        if (resp.code == 400 || resp.code == 401 || resp.code == 404 || resp.code == 410) {
                            sid = null
                            gsessionid = null
                            lastAid = 0L
                            commandOffset = 0
                        }
                    } else {
                        Log.d(TAG, "postLoungeBatch success: ${commands.map { it.first }} (ofs=$currentOfs)")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "postLoungeBatch network exception: ${e.message}")
            }
        }
    }
}
