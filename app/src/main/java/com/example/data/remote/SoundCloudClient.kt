package com.example.data.remote

import android.util.Log
import com.example.model.MusicSource
import com.example.model.MusicTrack
import com.example.player.ResolvedAudioStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class SoundCloudClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    private val clientIdMutex = Mutex()

    @Volatile
    private var cachedClientId: String? = null

    suspend fun searchFullTracks(query: String, limit: Int = 18): List<MusicTrack> =
        withContext(Dispatchers.IO) {
            val clean = query.trim()
            if (clean.isEmpty()) return@withContext emptyList()

            val clientId = getOrFetchClientId() ?: return@withContext emptyList()
            val encoded = URLEncoder.encode(clean, "UTF-8")
            var body = executeGet(
                "https://api-v2.soundcloud.com/search/tracks?q=$encoded&client_id=$clientId&limit=$limit"
            )
            if (body == null) {
                val refreshed = getOrFetchClientId(forceRefresh = true) ?: return@withContext emptyList()
                body = executeGet(
                    "https://api-v2.soundcloud.com/search/tracks?q=$encoded&client_id=$refreshed&limit=$limit"
                )
            }
            if (body.isNullOrBlank()) return@withContext emptyList()
            parseTracksResponse(body)
        }

    suspend fun resolvePlayableStreams(streamToken: String): List<ResolvedAudioStream> =
        withContext(Dispatchers.IO) {
            if (!streamToken.startsWith(TOKEN_PREFIX)) {
                return@withContext listOf(ResolvedAudioStream(streamToken, WEB_UA, "SoundCloud"))
            }
            val payload = streamToken.removePrefix(TOKEN_PREFIX)
            val parts = payload.split("|")
            val transcodeUrls = parts.getOrNull(0)?.split(";")?.filter { it.isNotBlank() }.orEmpty()
            val trackAuth = parts.getOrNull(1).orEmpty()

            var clientId = getOrFetchClientId() ?: return@withContext emptyList()
            val resolved = mutableListOf<ResolvedAudioStream>()
            for (tUrl in transcodeUrls) {
                val fullUrl = buildTranscodeRequestUrl(tUrl, clientId, trackAuth)
                var body = executeGet(fullUrl)
                if (body == null) {
                    val refreshed = getOrFetchClientId(forceRefresh = true)
                    if (refreshed != null && refreshed != clientId) {
                        clientId = refreshed
                        body = executeGet(buildTranscodeRequestUrl(tUrl, clientId, trackAuth))
                    }
                }
                val finalUrl = body?.let {
                    runCatching { JSONObject(it).optString("url").trim() }.getOrNull()
                }.orEmpty()
                if (finalUrl.isNotBlank()) {
                    resolved.add(ResolvedAudioStream(finalUrl, WEB_UA, "SoundCloud"))
                }
            }
            resolved
        }

    suspend fun searchAndResolveBestMatch(title: String, artist: String): List<ResolvedAudioStream> =
        withContext(Dispatchers.IO) {
            val queries = buildCandidateQueries(title, artist)
            for (q in queries) {
                val tracks = runCatching { searchFullTracks(q, limit = 6) }.getOrDefault(emptyList())
                for (candidate in tracks.take(3)) {
                    val streams = resolvePlayableStreams(candidate.streamUrl)
                    if (streams.isNotEmpty()) return@withContext streams
                }
            }
            emptyList()
        }

    private fun buildTranscodeRequestUrl(
        transcodeUrl: String,
        clientId: String,
        trackAuth: String
    ): String {
        val sep = if (transcodeUrl.contains("?")) "&" else "?"
        val authPart = if (trackAuth.isNotBlank()) {
            "&track_authorization=${URLEncoder.encode(trackAuth, "UTF-8")}"
        } else {
            ""
        }
        return "$transcodeUrl${sep}client_id=$clientId$authPart"
    }

    private suspend fun getOrFetchClientId(forceRefresh: Boolean = false): String? {
        if (!forceRefresh) {
            cachedClientId?.let { return it }
        }
        return clientIdMutex.withLock {
            if (!forceRefresh) {
                cachedClientId?.let { return@withLock it }
            }
            val html = executeGet("https://soundcloud.com") ?: return@withLock null
            val scriptUrls = Regex("https://a-v2\\.sndcdn\\.com/assets/[^\"']+\\.js")
                .findAll(html)
                .map { it.value }
                .toList()
            for (scriptUrl in scriptUrls.takeLast(6).reversed()) {
                val js = executeGet(scriptUrl) ?: continue
                val match = Regex("client_id:\"([a-zA-Z0-9]{20,40})\"").find(js)
                    ?: Regex("client_id=([a-zA-Z0-9]{20,40})").find(js)
                if (match != null) {
                    val found = match.groupValues[1]
                    cachedClientId = found
                    return@withLock found
                }
            }
            null
        }
    }

    private fun executeGet(url: String): String? {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", WEB_UA)
                .header("Accept-Language", "he-IL,he;q=0.9,en-US;q=0.8,en;q=0.7")
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    Log.d(TAG, "HTTP ${res.code} for $url")
                    return@use null
                }
                res.body?.string()
            }
        }.onFailure {
            Log.d(TAG, "GET error for $url: ${it.message}")
        }.getOrNull()
    }

    companion object {
        private const val TAG = "SoundCloudClient"
        const val TOKEN_PREFIX = "sc_transcode|"
        const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        fun parseTracksResponse(jsonText: String): List<MusicTrack> {
            val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return emptyList()
            val collection = root.optJSONArray("collection") ?: return emptyList()
            val tracks = mutableListOf<MusicTrack>()

            for (i in 0 until collection.length()) {
                val item = collection.optJSONObject(i) ?: continue
                val id = item.optLong("id", 0L)
                if (id <= 0L) continue
                if (!item.optBoolean("streamable", true)) continue
                val policy = item.optString("policy").uppercase()
                if (policy == "SNIP" || policy == "BLOCK") continue

                val durationMs = item.optLong("duration", 0L)
                if (durationMs < 45_000L) continue

                val title = item.optString("title").trim()
                val userObj = item.optJSONObject("user")
                val artist = userObj?.optString("username")?.trim().orEmpty().ifBlank { "SoundCloud" }
                if (title.isBlank()) continue

                val transcodings = item.optJSONObject("media")?.optJSONArray("transcodings") ?: continue
                val orderedUrls = mutableListOf<String>()
                var progressiveMp3: String? = null
                var hlsMp4: String? = null
                var hlsOther: String? = null

                for (j in 0 until transcodings.length()) {
                    val t = transcodings.optJSONObject(j) ?: continue
                    val tUrl = t.optString("url").trim()
                    if (tUrl.isBlank()) continue
                    val fmt = t.optJSONObject("format")
                    val proto = fmt?.optString("protocol")?.lowercase().orEmpty()
                    val mime = fmt?.optString("mime_type")?.lowercase().orEmpty()
                    when {
                        proto == "progressive" -> progressiveMp3 = progressiveMp3 ?: tUrl
                        proto == "hls" && mime.contains("mp4") -> hlsMp4 = hlsMp4 ?: tUrl
                        proto == "hls" -> hlsOther = hlsOther ?: tUrl
                    }
                }
                progressiveMp3?.let { orderedUrls.add(it) }
                hlsMp4?.let { orderedUrls.add(it) }
                hlsOther?.let { orderedUrls.add(it) }
                if (orderedUrls.isEmpty()) continue

                val trackAuth = item.optString("track_authorization").trim()
                val token = "$TOKEN_PREFIX${orderedUrls.joinToString(";")}|$trackAuth"
                val rawArt = item.optString("artwork_url").trim()
                    .ifBlank { userObj?.optString("avatar_url")?.trim().orEmpty() }
                val highResArt = rawArt.replace("-large", "-t500x500")
                val genre = item.optString("genre").trim()

                tracks.add(
                    MusicTrack(
                        trackKey = "sc_$id",
                        title = title,
                        artist = artist,
                        album = genre.ifBlank { "SoundCloud" },
                        source = MusicSource.SOUNDCLOUD,
                        streamUrl = token,
                        artworkUrl = highResArt,
                        durationSeconds = durationMs / 1000L,
                        fullTrack = true
                    )
                )
            }
            return tracks
        }

        fun buildCandidateQueries(title: String, artist: String): List<String> {
            val cleanTitle = cleanLabel(title)
            val cleanArtist = cleanLabel(artist)
                .replace(Regex("\\s*-\\s*topic$", RegexOption.IGNORE_CASE), "")
                .trim()
            val queries = linkedSetOf<String>()
            if (cleanTitle.isNotBlank() && cleanArtist.isNotBlank() &&
                !cleanTitle.contains(cleanArtist, ignoreCase = true) &&
                !cleanArtist.equals("YouTube", ignoreCase = true)
            ) {
                queries.add("$cleanTitle $cleanArtist")
            }
            if (cleanTitle.isNotBlank()) {
                queries.add(cleanTitle)
            }
            val primarySegment = cleanTitle.split("|", "—", "–").firstOrNull()?.trim().orEmpty()
            if (primarySegment.length >= 3) {
                queries.add(primarySegment)
            }
            return queries.toList()
        }

        private fun cleanLabel(raw: String): String =
            raw.replace(Regex("\\([^)]*\\)|\\[[^\\]]*\\]"), " ")
                .replace(Regex("(?i)\\b(official\\s*(music\\s*)?(video|audio|lyric\\s*video|lyrics)|hd|4k|hq)\\b"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
    }
}
