package com.retro.cartube.video

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

object NewPipeHelper {

    private const val TAG = "NewPipeHelper"
    private var isInitialized = false

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val customDownloader = object : Downloader() {
        override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
            val builder = Request.Builder().url(request.url())

            var hasUserAgent = false
            for ((key, values) in request.headers()) {
                if (key.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
                for (value in values) {
                    builder.addHeader(key, value)
                }
            }
            if (!hasUserAgent) {
                builder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36")
            }
            builder.header("Accept-Language", "en-US,en;q=0.9")

            val httpMethod = request.httpMethod()
            val dataToSend = request.dataToSend()

            when {
                httpMethod.equals("POST", ignoreCase = true) -> {
                    val mediaType = "application/json; charset=utf-8".toMediaType()
                    val body = (dataToSend ?: ByteArray(0)).toRequestBody(mediaType)
                    builder.post(body)
                }
                httpMethod.equals("HEAD", ignoreCase = true) -> {
                    builder.head()
                }
                else -> {
                    builder.get()
                }
            }

            val response = httpClient.newCall(builder.build()).execute()
            val headersMap = mutableMapOf<String, List<String>>()
            for (name in response.headers.names()) {
                headersMap[name] = response.headers.values(name)
            }

            val responseBody = response.body?.string() ?: ""
            return Response(response.code, response.message, headersMap, responseBody, response.request.url.toString())
        }
    }

    fun init() {
        if (!isInitialized) {
            try {
                NewPipe.init(customDownloader, Localization.fromLocale(Locale.ENGLISH), ContentCountry("US"))
                isInitialized = true
                Log.i(TAG, "NewPipeExtractor initialized successfully with English locale")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init NewPipeExtractor", e)
            }
        }
    }

    fun extractStream(videoId: String, preferredHeight: Int = 1080): VideoStreamInfo? {
        init()
        try {
            val url = "https://www.youtube.com/watch?v=$videoId"
            Log.i(TAG, "Fetching StreamInfo for $url (target: ${preferredHeight}p)...")
            val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, url)

            val title = streamInfo.name ?: "YouTube Video"
            val channel = streamInfo.uploaderName ?: ""
            val durationMs = streamInfo.duration * 1000L

            // 1. Check progressive streams (video + audio in single MP4)
            val videoStreams = streamInfo.videoStreams
            val progressive = videoStreams?.filter { !it.isVideoOnly } ?: emptyList()
            val bestProgressive = progressive
                .filter { !it.url.isNullOrBlank() }
                .maxByOrNull { it.height }
            val progHeight = bestProgressive?.height ?: 0

            // If we have progressive at or above preferredHeight, use it directly (single HTTP connection)
            if (bestProgressive != null && progHeight >= preferredHeight && !bestProgressive.url.isNullOrBlank()) {
                Log.i(TAG, "Found HD progressive stream via NewPipe: ${bestProgressive.resolution} - $title")
                return VideoStreamInfo(
                    videoId = videoId,
                    title = title,
                    channel = channel,
                    streamUrl = bestProgressive.url!!,
                    qualityLabel = bestProgressive.resolution ?: "${progHeight}p",
                    isHls = false,
                    durationMs = durationMs
                )
            }

            // 2. Check separate video-only (exact integer it.height matching, no 60fps string parsing bugs!) + audio streams merged
            val videoOnlyStreams = streamInfo.videoOnlyStreams
            val audioStreams = streamInfo.audioStreams
            if (!videoOnlyStreams.isNullOrEmpty() && !audioStreams.isNullOrEmpty()) {
                val bestVideo = videoOnlyStreams
                    .filter { !it.url.isNullOrBlank() }
                    .minByOrNull {
                        val h = it.height
                        if (preferredHeight >= 1080) {
                            when {
                                h == 1080 -> 0 // #1 preference: 1080p Full HD (crisp, uncompressed)
                                h > 1080 -> 1  // 1440p / 2K
                                h == 720 -> 2  // 720p HD fallback
                                h == 480 -> 3  // 480p SD fallback
                                else -> 4
                            }
                        } else {
                            when {
                                h == 720 -> 0  // #1 preference: 720p HD (data saver)
                                h == 1080 -> 1 // 1080p Full HD fallback
                                h == 480 -> 2  // 480p SD fallback
                                else -> 3
                            }
                        }
                    }

                // Prioritize English audio tracks or Original non-dubbed audio:
                // 0 = English (Original)
                // 1 = English (Dubbed or untagged)
                // 2 = Original non-dubbed creator voice
                // 3 = Default / untagged audio
                // 4 = Dubbed foreign language
                val bestAudio = audioStreams
                    .filter { !it.url.isNullOrBlank() }
                    .minWithOrNull(
                        compareBy<AudioStream> { stream ->
                            val lang = stream.audioLocale?.language?.lowercase() ?: ""
                            val name = stream.audioTrackName?.lowercase() ?: ""
                            val isOriginal = stream.audioTrackType == AudioTrackType.ORIGINAL || name.contains("original")
                            val isEnglish = lang == "en" || name.contains("english")
                            when {
                                isEnglish && isOriginal -> 0
                                isEnglish -> 1
                                isOriginal -> 2
                                lang.isEmpty() -> 3
                                else -> 4
                            }
                        }.thenByDescending { it.averageBitrate }
                    )

                if (bestVideo != null && bestAudio != null) {
                    val vUrl = bestVideo.url
                    val aUrl = bestAudio.url
                    if (!vUrl.isNullOrBlank() && !aUrl.isNullOrBlank()) {
                        val qLabel = "${bestVideo.resolution ?: "${bestVideo.height}p"} HD"
                        val aTrackInfo = "${bestAudio.audioTrackName ?: bestAudio.audioLocale?.displayName ?: "Default"} (${bestAudio.averageBitrate}bps)"
                        Log.i(TAG, "Found HD video+audio: video=$qLabel, audio=$aTrackInfo - $title")
                        return VideoStreamInfo(
                            videoId = videoId,
                            title = title,
                            channel = channel,
                            streamUrl = vUrl,
                            qualityLabel = qLabel,
                            isHls = false,
                            durationMs = durationMs,
                            audioUrl = aUrl
                        )
                    }
                }
            }

            // 3. Try HLS adaptive manifest (contains video + audio)
            val hlsUrl = streamInfo.hlsUrl
            if (!hlsUrl.isNullOrBlank()) {
                Log.i(TAG, "Found HLS adaptive stream via NewPipe - $title")
                return VideoStreamInfo(
                    videoId = videoId,
                    title = title,
                    channel = channel,
                    streamUrl = hlsUrl,
                    qualityLabel = "HLS Auto",
                    isHls = true,
                    durationMs = durationMs
                )
            }

            // 4. Fallback to progressive (even if 360p/480p)
            if (bestProgressive != null && !bestProgressive.url.isNullOrBlank()) {
                Log.i(TAG, "Found progressive stream via NewPipe: ${bestProgressive.resolution} - $title")
                return VideoStreamInfo(
                    videoId = videoId,
                    title = title,
                    channel = channel,
                    streamUrl = bestProgressive.url!!,
                    qualityLabel = bestProgressive.resolution ?: "360p",
                    isHls = false,
                    durationMs = durationMs
                )
            }

            // 4. Fallback to any available video stream
            val fallbackVideo = videoStreams?.firstOrNull { !it.url.isNullOrBlank() }
                ?: videoOnlyStreams?.firstOrNull { !it.url.isNullOrBlank() }
            val fUrl = fallbackVideo?.url
            if (fallbackVideo != null && !fUrl.isNullOrBlank()) {
                Log.w(TAG, "Using fallback stream for $videoId")
                return VideoStreamInfo(
                    videoId = videoId,
                    title = title,
                    channel = channel,
                    streamUrl = fUrl,
                    qualityLabel = fallbackVideo.resolution ?: "Video",
                    isHls = false,
                    durationMs = durationMs
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error extracting stream via NewPipe for $videoId: ${e.message}", e)
        }
        return null
    }
}
