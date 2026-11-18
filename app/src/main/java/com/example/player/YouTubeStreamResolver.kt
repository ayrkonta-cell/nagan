package com.example.player

import android.util.Log
import com.example.data.remote.JioSaavnClient
import com.example.data.remote.SoundCloudClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ResolvedAudioStream(
    val url: String,
    val userAgent: String,
    val sourceLabel: String
)

class YouTubeStreamResolver(
    private val soundCloudClient: SoundCloudClient = SoundCloudClient(),
    private val jioSaavnClient: JioSaavnClient = JioSaavnClient(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    suspend fun resolveAudioStreams(
        videoId: String,
        trackTitle: String = "",
        trackArtist: String = ""
    ): List<ResolvedAudioStream> = withContext(Dispatchers.IO) {
        coroutineScope {
            val ytDeferred = async {
                if (videoId.isBlank()) emptyList() else resolveDirectYouTube(videoId)
            }
            val scDeferred = async {
                if (trackTitle.isBlank()) emptyList()
                else runCatching {
                    soundCloudClient.searchAndResolveBestMatch(trackTitle, trackArtist)
                }.getOrDefault(emptyList())
            }
            val jioDeferred = async {
                if (trackTitle.isBlank()) emptyList()
                else runCatching {
                    jioSaavnClient.searchAndResolveBestMatch(trackTitle, trackArtist)
                }.getOrDefault(emptyList())
            }

            val ytStreams = ytDeferred.await()
            val scStreams = scDeferred.await()
            val jioStreams = jioDeferred.await()

            (ytStreams + scStreams + jioStreams).distinctBy { it.url }
        }
    }

    suspend fun resolveCrossSourceFallback(
        trackTitle: String,
        trackArtist: String
    ): List<ResolvedAudioStream> = withContext(Dispatchers.IO) {
        if (trackTitle.isBlank()) return@withContext emptyList()
        coroutineScope {
            val sc = async {
                runCatching {
                    soundCloudClient.searchAndResolveBestMatch(trackTitle, trackArtist)
                }.getOrDefault(emptyList())
            }
            val jio = async {
                runCatching {
                    jioSaavnClient.searchAndResolveBestMatch(trackTitle, trackArtist)
                }.getOrDefault(emptyList())
            }
            (sc.await() + jio.await()).distinctBy { it.url }
        }
    }

    private suspend fun resolveDirectYouTube(videoId: String): List<ResolvedAudioStream> {
        val innerTubeResults = coroutineScope {
            listOf(
                async { fetchInnerTubeAndroidVr(videoId) },
                async { fetchInnerTubeIos(videoId) },
                async { fetchInnerTubeAndroid(videoId) }
            ).awaitAll().flatten()
        }.distinctBy { it.url }

        if (innerTubeResults.isNotEmpty()) {
            return innerTubeResults
        }

        val pipedInstances = listOf(
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.adminforge.de"
        )
        val invidiousInstances = listOf(
            "https://inv.nadeko.net",
            "https://yewtu.be"
        )

        return coroutineScope {
            val tasks = buildList {
                pipedInstances.forEach { base -> add(async { fetchPipedAudioUrls(base, videoId) }) }
                invidiousInstances.forEach { base -> add(async { fetchInvidiousAudioUrls(base, videoId) }) }
            }
            tasks.awaitAll().flatten()
        }.distinctBy { it.url }
    }

    private fun fetchInnerTubeAndroidVr(videoId: String): List<ResolvedAudioStream> {
        val ua =
            "com.google.android.apps.youtube.vr.oculus/1.57.29 (Linux; U; Android 12; eureka-user Build/SQ3A.220605.009.A1) gzip"
        val payload = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "ANDROID_VR")
                    put("clientVersion", "1.57.29")
                    put("androidSdkVersion", 32)
                    put("osName", "Android")
                    put("osVersion", "12")
                    put("hl", "iw")
                    put("gl", "IL")
                })
            })
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        return postInnerTubePlayer(videoId, payload, ua, "28", "1.57.29", "InnerTube-VR")
    }

    private fun fetchInnerTubeIos(videoId: String): List<ResolvedAudioStream> {
        val ua = "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)"
        val payload = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "IOS")
                    put("clientVersion", "19.29.1")
                    put("deviceMake", "Apple")
                    put("deviceModel", "iPhone16,2")
                    put("osName", "iPhone")
                    put("osVersion", "17.5.1.21F90")
                    put("hl", "iw")
                    put("gl", "IL")
                })
            })
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        return postInnerTubePlayer(videoId, payload, ua, "5", "19.29.1", "InnerTube-iOS")
    }

    private fun fetchInnerTubeAndroid(videoId: String): List<ResolvedAudioStream> {
        val ua = "com.google.android.youtube/19.29.37 (Linux; U; Android 11) gzip"
        val payload = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "ANDROID")
                    put("clientVersion", "19.29.37")
                    put("androidSdkVersion", 30)
                    put("osName", "Android")
                    put("osVersion", "11")
                    put("hl", "iw")
                    put("gl", "IL")
                })
            })
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        return postInnerTubePlayer(videoId, payload, ua, "3", "19.29.37", "InnerTube-Android")
    }

    private fun postInnerTubePlayer(
        videoId: String,
        payload: JSONObject,
        userAgent: String,
        clientIdHeader: String,
        clientVersionHeader: String,
        label: String
    ): List<ResolvedAudioStream> {
        return runCatching {
            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("User-Agent", userAgent)
                .header("X-YouTube-Client-Name", clientIdHeader)
                .header("X-YouTube-Client-Version", clientVersionHeader)
                .header("Origin", "https://www.youtube.com")
                .build()

            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@runCatching emptyList()
                val body = res.body?.string() ?: return@runCatching emptyList()
                parseInnerTubePlayerResponse(body, userAgent, label)
            }
        }.onFailure {
            Log.d(TAG, "$label failed for $videoId: ${it.message}")
        }.getOrDefault(emptyList())
    }

    private fun fetchPipedAudioUrls(baseUrl: String, videoId: String): List<ResolvedAudioStream> {
        return runCatching {
            val req = Request.Builder()
                .url("$baseUrl/streams/$videoId")
                .header("User-Agent", DEFAULT_UA)
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@runCatching emptyList()
                val body = res.body?.string() ?: return@runCatching emptyList()
                val streams = JSONObject(body).optJSONArray("audioStreams") ?: return@runCatching emptyList()

                val m4a = mutableListOf<Pair<Int, String>>()
                val other = mutableListOf<Pair<Int, String>>()
                for (i in 0 until streams.length()) {
                    val item = streams.optJSONObject(i) ?: continue
                    val url = item.optString("url").trim()
                    if (url.isBlank()) continue
                    val mime = item.optString("mimeType").lowercase()
                    val bitrate = item.optInt("bitrate", 0)
                    if (mime.contains("mp4") || mime.contains("m4a")) {
                        m4a.add(bitrate to url)
                    } else {
                        other.add(bitrate to url)
                    }
                }
                (m4a.sortedByDescending { it.first } + other.sortedByDescending { it.first })
                    .map { ResolvedAudioStream(it.second, DEFAULT_UA, "Piped") }
            }
        }.onFailure {
            Log.d(TAG, "Piped $baseUrl failed for $videoId: ${it.message}")
        }.getOrDefault(emptyList())
    }

    private fun fetchInvidiousAudioUrls(baseUrl: String, videoId: String): List<ResolvedAudioStream> {
        return runCatching {
            val req = Request.Builder()
                .url("$baseUrl/api/v1/videos/$videoId?local=true")
                .header("User-Agent", DEFAULT_UA)
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@runCatching emptyList()
                val body = res.body?.string() ?: return@runCatching emptyList()
                val formats = JSONObject(body).optJSONArray("adaptiveFormats") ?: return@runCatching emptyList()

                val m4a = mutableListOf<Pair<Int, String>>()
                val other = mutableListOf<Pair<Int, String>>()
                for (i in 0 until formats.length()) {
                    val item = formats.optJSONObject(i) ?: continue
                    val type = item.optString("type").lowercase()
                    if (!type.startsWith("audio/")) continue
                    val rawUrl = item.optString("url").trim()
                    if (rawUrl.isBlank()) continue
                    val url = if (rawUrl.startsWith("/")) "$baseUrl$rawUrl" else rawUrl
                    val bitrate = item.optString("bitrate").toIntOrNull() ?: 0
                    if (type.contains("mp4")) {
                        m4a.add(bitrate to url)
                    } else {
                        other.add(bitrate to url)
                    }
                }
                (m4a.sortedByDescending { it.first } + other.sortedByDescending { it.first })
                    .map { ResolvedAudioStream(it.second, DEFAULT_UA, "Invidious") }
            }
        }.onFailure {
            Log.d(TAG, "Invidious $baseUrl failed for $videoId: ${it.message}")
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val TAG = "YouTubeStreamResolver"
        const val DEFAULT_UA = "TzlilimMusicApp/1.4 (Android)"

        fun parseInnerTubePlayerResponse(
            jsonText: String,
            userAgent: String,
            label: String
        ): List<ResolvedAudioStream> {
            val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return emptyList()
            val streamingData = root.optJSONObject("streamingData") ?: return emptyList()

            val m4aAudio = mutableListOf<Pair<Int, String>>()
            val webmAudio = mutableListOf<Pair<Int, String>>()
            val muxedFallback = mutableListOf<Pair<Int, String>>()

            val adaptive = streamingData.optJSONArray("adaptiveFormats")
            if (adaptive != null) {
                for (i in 0 until adaptive.length()) {
                    val item = adaptive.optJSONObject(i) ?: continue
                    val url = item.optString("url").trim()
                    if (url.isBlank()) continue
                    val mime = item.optString("mimeType").lowercase()
                    if (!mime.startsWith("audio/")) continue
                    val bitrate = item.optInt("bitrate", 0)
                    if (mime.contains("mp4")) {
                        m4aAudio.add(bitrate to url)
                    } else {
                        webmAudio.add(bitrate to url)
                    }
                }
            }

            val formats = streamingData.optJSONArray("formats")
            if (formats != null) {
                for (i in 0 until formats.length()) {
                    val item = formats.optJSONObject(i) ?: continue
                    val url = item.optString("url").trim()
                    if (url.isBlank()) continue
                    val mime = item.optString("mimeType").lowercase()
                    if (!mime.contains("mp4")) continue
                    val bitrate = item.optInt("bitrate", 0)
                    muxedFallback.add(bitrate to url)
                }
            }

            val ordered = m4aAudio.sortedByDescending { it.first } +
                webmAudio.sortedByDescending { it.first } +
                muxedFallback.sortedBy { it.first }

            return ordered.map { ResolvedAudioStream(it.second, userAgent, label) }
        }
    }
}
