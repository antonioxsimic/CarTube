package com.retro.cartube.video

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class VideoStreamInfo(
    val videoId: String,
    val title: String,
    val channel: String = "",
    val streamUrl: String,
    val qualityLabel: String = "HD",
    val isHls: Boolean = false,
    val durationMs: Long = 0L,
    val audioUrl: String? = null
)

object YouTubeVideoResolver {

    private const val TAG = "YTVideoResolver"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val videoCache = mutableMapOf<String, VideoStreamInfo>()

    /**
     * Parses standard YouTube URL or text and extracts (videoId, startPositionMs)
     */
    fun parseYouTubeUrl(text: String): Pair<String, Long>? {
        if (text.isBlank()) return null

        var videoId: String? = null
        var startMs = 0L

        // Match youtu.be/<id>
        val youtuBePattern = Pattern.compile("(?:https?:\\/\\/)?(?:www\\.)?youtu\\.be\\/([a-zA-Z0-9_-]{11})")
        val youtuBeMatcher = youtuBePattern.matcher(text)
        if (youtuBeMatcher.find()) {
            videoId = youtuBeMatcher.group(1)
        }

        // Match youtube.com/watch?v=<id> or /shorts/<id> or /live/<id> or /embed/<id>
        if (videoId == null) {
            val ytPattern = Pattern.compile("(?:https?:\\/\\/)?(?:[a-zA-Z0-9_-]+\\.)?youtube\\.com\\/(?:watch\\?.*?v=|shorts\\/|live\\/|embed\\/)([a-zA-Z0-9_-]{11})")
            val ytMatcher = ytPattern.matcher(text)
            if (ytMatcher.find()) {
                videoId = ytMatcher.group(1)
            }
        }

        // Match plain 11-char ID
        if (videoId == null) {
            val plainPattern = Pattern.compile("^[a-zA-Z0-9_-]{11}$")
            if (plainPattern.matcher(text.trim()).matches()) {
                videoId = text.trim()
            }
        }

        if (videoId == null) return null

        // Parse timestamp: ?t=120 or &t=1m45s or ?t=45s
        val timePattern = Pattern.compile("[?&]t=([0-9mhseconds]+)")
        val timeMatcher = timePattern.matcher(text)
        if (timeMatcher.find()) {
            val tStr = timeMatcher.group(1) ?: ""
            startMs = parseTimeStringToMs(tStr)
        }

        return Pair(videoId, startMs)
    }

    private fun parseTimeStringToMs(timeStr: String): Long {
        try {
            // If it's purely digits (seconds)
            if (timeStr.matches(Regex("^\\d+$"))) {
                return timeStr.toLong() * 1000L
            }
            var totalSeconds = 0L
            val hoursMatch = Regex("(\\d+)h").find(timeStr)
            val minutesMatch = Regex("(\\d+)m").find(timeStr)
            val secondsMatch = Regex("(\\d+)s").find(timeStr)

            if (hoursMatch != null) totalSeconds += hoursMatch.groupValues[1].toLong() * 3600L
            if (minutesMatch != null) totalSeconds += minutesMatch.groupValues[1].toLong() * 60L
            if (secondsMatch != null) totalSeconds += secondsMatch.groupValues[1].toLong()

            return totalSeconds * 1000L
        } catch (_: Exception) {
            return 0L
        }
    }

    suspend fun resolveStream(videoId: String, fallbackTitle: String = "", preferredHeight: Int = 1080): VideoStreamInfo? = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext null

        val cacheKey = "${videoId}_$preferredHeight"
        synchronized(videoCache) {
            val cached = videoCache[cacheKey]
            if (cached != null) return@withContext cached
        }

        // 1. Primary: NewPipeExtractor (handles YouTube bot checks & signature decryption natively)
        try {
            Log.i(TAG, "Attempting stream resolution via NewPipeExtractor for $videoId (target: ${preferredHeight}p)")
            val newPipeStream = NewPipeHelper.extractStream(videoId, preferredHeight)
            if (newPipeStream != null) {
                synchronized(videoCache) {
                    videoCache[cacheKey] = newPipeStream
                }
                return@withContext newPipeStream
            }
        } catch (e: Throwable) {
            Log.w(TAG, "NewPipeExtractor attempt failed for $videoId", e)
        }

        // 2. Fallback: InnerTube ANDROID_VR / TV / ANDROID clients (Progressive MP4 & HLS)
        try {
            Log.i(TAG, "Attempting fallback stream resolution via InnerTube for $videoId")
            val innerTubeInfo = resolveViaInnerTube(videoId, fallbackTitle)
            if (innerTubeInfo != null) {
                synchronized(videoCache) {
                    videoCache[cacheKey] = innerTubeInfo
                }
                return@withContext innerTubeInfo
            }
        } catch (e: Throwable) {
            Log.w(TAG, "InnerTube attempt failed for $videoId", e)
        }

        Log.w(TAG, "All stream resolution methods failed for videoId: $videoId")
        null
    }

    private fun resolveViaInnerTube(videoId: String, fallbackTitle: String): VideoStreamInfo? {
        val clientConfigs = listOf(
            // 1. Android VR (Oculus Quest 3 client - progressive MP4 unthrottled)
            JSONObject().apply {
                put("clientName", "ANDROID_VR")
                put("clientVersion", "1.61.48")
                put("deviceMake", "Oculus")
                put("deviceModel", "Quest 3")
                put("hl", "en")
                put("gl", "US")
            },
            // 2. TV HTML5 Simply Embedded Player
            JSONObject().apply {
                put("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER")
                put("clientVersion", "2.0")
                put("clientScreen", "WATCH")
                put("hl", "en")
                put("gl", "US")
            },
            // 3. Android Embedded Player
            JSONObject().apply {
                put("clientName", "ANDROID_EMBEDDED_PLAYER")
                put("clientVersion", "19.34.42")
                put("hl", "en")
                put("gl", "US")
            }
        )

        for (clientObj in clientConfigs) {
            try {
                val payload = JSONObject().apply {
                    val ctx = JSONObject().apply {
                        put("client", clientObj)
                        put("thirdParty", JSONObject().apply {
                            put("embedUrl", "https://www.youtube.com/")
                        })
                    }
                    put("context", ctx)
                    put("videoId", videoId)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                }

                val request = Request.Builder()
                    .url("https://www.youtube.com/youtubei/v1/player")
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .addHeader("Origin", "https://www.youtube.com")
                    .post(payload.toString().toRequestBody(JSON_MEDIA))
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) continue

                val bodyStr = response.body?.string() ?: continue
                val json = JSONObject(bodyStr)

                val status = json.optJSONObject("playabilityStatus")?.optString("status")
                if (status != "OK") continue

                val streamingData = json.optJSONObject("streamingData") ?: continue
                val videoDetails = json.optJSONObject("videoDetails")
                val videoTitle = videoDetails?.optString("title", fallbackTitle) ?: fallbackTitle
                val channel = videoDetails?.optString("author", "") ?: ""
                val lengthSeconds = videoDetails?.optLong("lengthSeconds", 0L) ?: 0L

                // Progressive formats (audio + video combined in single MP4)
                val formats = streamingData.optJSONArray("formats")
                if (formats != null && formats.length() > 0) {
                    var bestUrl: String? = null
                    var bestQuality = "360p"
                    var bestItag = 0

                    for (i in 0 until formats.length()) {
                        val fmt = formats.optJSONObject(i) ?: continue
                        val url = fmt.optString("url")
                        val mime = fmt.optString("mimeType")
                        val itag = fmt.optInt("itag", 0)
                        val qLabel = fmt.optString("qualityLabel", "360p")

                        if (url.isNotBlank() && mime.contains("video/mp4")) {
                            if (itag > bestItag) {
                                bestItag = itag
                                bestUrl = url
                                bestQuality = qLabel
                            }
                        }
                    }

                    if (!bestUrl.isNullOrBlank()) {
                        Log.i(TAG, "Found progressive MP4 via ${clientObj.optString("clientName")} for $videoId ($bestQuality)")
                        return VideoStreamInfo(
                            videoId = videoId,
                            title = videoTitle,
                            channel = channel,
                            streamUrl = bestUrl,
                            qualityLabel = bestQuality,
                            isHls = false,
                            durationMs = lengthSeconds * 1000L
                        )
                    }
                }

                // HLS stream fallback
                val hlsUrl = streamingData.optString("hlsManifestUrl")
                if (hlsUrl.isNotBlank()) {
                    Log.i(TAG, "Found HLS stream via ${clientObj.optString("clientName")} for $videoId")
                    return VideoStreamInfo(
                        videoId = videoId,
                        title = videoTitle,
                        channel = channel,
                        streamUrl = hlsUrl,
                        qualityLabel = "HLS Auto",
                        isHls = true,
                        durationMs = lengthSeconds * 1000L
                    )
                }
            } catch (e: Exception) {
                Log.d(TAG, "InnerTube client ${clientObj.optString("clientName")} failed: ${e.message}")
            }
        }

        return null
    }

    private fun resolveViaInvidious(videoId: String, fallbackTitle: String): VideoStreamInfo? {
        val instances = listOf(
            "https://pipedapi.kavin.rocks",
            "https://api.piped.yt",
            "https://invidious.nerdvpn.de",
            "https://invidious.f5.si",
            "https://inv.nadeko.net",
            "https://yewtu.be",
            "https://invidious.tiekoetter.com"
        )

        for (host in instances) {
            try {
                if (host.contains("piped")) {
                    val request = Request.Builder()
                        .url("$host/streams/$videoId")
                        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .get()
                        .build()

                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string() ?: continue
                        val json = JSONObject(bodyStr)
                        val hls = json.optString("hls")
                        val title = json.optString("title", fallbackTitle)
                        val uploader = json.optString("uploader", "")
                        val duration = json.optLong("duration", 0L)

                        if (hls.isNotBlank()) {
                            return VideoStreamInfo(videoId, title, uploader, hls, "HLS", true, duration * 1000L)
                        }
                        val vStreams = json.optJSONArray("videoStreams")
                        if (vStreams != null && vStreams.length() > 0) {
                            for (i in 0 until vStreams.length()) {
                                val s = vStreams.optJSONObject(i) ?: continue
                                val u = s.optString("url")
                                val videoOnly = s.optBoolean("videoOnly", false)
                                if (u.isNotBlank() && !videoOnly) {
                                    return VideoStreamInfo(
                                        videoId = videoId,
                                        title = title,
                                        channel = uploader,
                                        streamUrl = u,
                                        qualityLabel = s.optString("quality", "720p"),
                                        isHls = false,
                                        durationMs = duration * 1000L
                                    )
                                }
                            }
                        }
                    }
                } else {
                    val request = Request.Builder()
                        .url("$host/api/v1/videos/$videoId")
                        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .get()
                        .build()

                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string() ?: continue
                        val json = JSONObject(bodyStr)
                        val title = json.optString("title", fallbackTitle)
                        val author = json.optString("author", "")
                        val length = json.optLong("lengthSeconds", 0L)

                        val formatStreams = json.optJSONArray("formatStreams")
                        if (formatStreams != null && formatStreams.length() > 0) {
                            for (i in formatStreams.length() - 1 downTo 0) {
                                val stream = formatStreams.optJSONObject(i) ?: continue
                                val url = stream.optString("url")
                                val res = stream.optString("resolution", "720p")
                                if (url.isNotBlank()) {
                                    return VideoStreamInfo(
                                        videoId = videoId,
                                        title = title,
                                        channel = author,
                                        streamUrl = url,
                                        qualityLabel = res,
                                        isHls = false,
                                        durationMs = length * 1000L
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Instance $host failed: ${e.message}")
            }
        }
        return null
    }

    suspend fun searchVideoId(query: String): String? = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext null
        try {
            val payload = JSONObject().apply {
                val ctx = JSONObject().apply {
                    val client = JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20240101.00.00")
                        put("hl", "en")
                        put("gl", "US")
                    }
                    put("client", client)
                }
                put("context", ctx)
                put("query", query)
            }

            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/search")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Origin", "https://www.youtube.com")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string() ?: ""
                val match = Regex("\"videoId\":\\s*\"([a-zA-Z0-9_-]{11})\"").find(bodyStr)
                return@withContext match?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Search failed for query: $query", e)
        }
        null
    }
}
