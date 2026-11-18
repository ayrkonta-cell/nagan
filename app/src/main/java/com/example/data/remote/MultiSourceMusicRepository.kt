package com.example.data.remote

import android.util.Log
import com.example.model.MusicSource
import com.example.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

sealed interface SearchProgressEvent {
    data class SourceStarted(val source: MusicSource) : SearchProgressEvent
    data class SourceCompleted(
        val source: MusicSource,
        val tracks: List<MusicTrack>,
        val success: Boolean
    ) : SearchProgressEvent
}

class MultiSourceMusicRepository(
    private val soundCloudClient: SoundCloudClient = SoundCloudClient(),
    private val jioSaavnClient: JioSaavnClient = JioSaavnClient(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {

    fun searchAllSources(query: String): Flow<SearchProgressEvent> = channelFlow {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@channelFlow

        val encoded = withContext(Dispatchers.IO) {
            URLEncoder.encode(cleanQuery, "UTF-8")
        }

        val sources = listOf(
            MusicSource.SOUNDCLOUD,
            MusicSource.JIOSAAVN,
            MusicSource.YOUTUBE,
            MusicSource.AUDIUS,
            MusicSource.ARCHIVE,
            MusicSource.ITUNES,
            MusicSource.DEEZER
        )

        coroutineScope {
            for (source in sources) {
                launch(Dispatchers.IO) {
                    send(SearchProgressEvent.SourceStarted(source))
                    val result = runCatching {
                        when (source) {
                            MusicSource.SOUNDCLOUD -> soundCloudClient.searchFullTracks(cleanQuery)
                            MusicSource.JIOSAAVN -> jioSaavnClient.searchFullTracks(cleanQuery)
                            MusicSource.YOUTUBE -> fetchYouTube(cleanQuery, encoded)
                            MusicSource.AUDIUS -> fetchAudius(encoded)
                            MusicSource.ARCHIVE -> fetchInternetArchive(cleanQuery)
                            MusicSource.ITUNES -> fetchItunes(encoded)
                            MusicSource.DEEZER -> fetchDeezer(encoded)
                        }
                    }
                    result.onFailure { err ->
                        Log.w(TAG, "Search failed for ${source.name}: ${err.message}")
                    }
                    val tracks = result.getOrDefault(emptyList())
                    send(
                        SearchProgressEvent.SourceCompleted(
                            source = source,
                            tracks = tracks,
                            success = result.isSuccess
                        )
                    )
                }
            }
        }
    }

    private fun executeGet(url: String, desktopUa: Boolean = false): String? {
        val ua = if (desktopUa) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        } else {
            "TzlilimMusicApp/1.4 (Android)"
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept-Language", "he-IL,he;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "HTTP ${response.code} for $url")
                return null
            }
            return response.body?.string()
        }
    }

    private fun isAudioUrlReachable(url: String): Boolean {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .header("Range", "bytes=0-64")
                .build()
            client.newCall(req).execute().use { res ->
                res.isSuccessful || res.code == 206
            }
        }.getOrDefault(false)
    }

    private fun fetchYouTube(rawQuery: String, encodedQuery: String): List<MusicTrack> {
        val fromYoutubei = runCatching { fetchYouTubeViaInnerTube(rawQuery) }.getOrNull().orEmpty()
        if (fromYoutubei.isNotEmpty()) return fromYoutubei

        val fromHtml = runCatching { fetchYouTubeViaSearchHtml(encodedQuery) }.getOrNull().orEmpty()
        if (fromHtml.isNotEmpty()) return fromHtml

        return runCatching { fetchYouTubeViaPiped(encodedQuery) }.getOrDefault(emptyList())
    }

    private fun fetchYouTubeViaInnerTube(rawQuery: String): List<MusicTrack> {
        val payload = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB")
                    put("clientVersion", "2.20241101.00.00")
                    put("hl", "iw")
                    put("gl", "IL")
                })
            })
            put("query", rawQuery)
        }
        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
            )
            .header("Origin", "https://www.youtube.com")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            return parseYtInitialData(JSONObject(body))
        }
    }

    private fun fetchYouTubeViaSearchHtml(encodedQuery: String): List<MusicTrack> {
        val html = executeGet(
            "https://www.youtube.com/results?search_query=$encodedQuery",
            desktopUa = true
        ) ?: return emptyList()

        val marker = "var ytInitialData = "
        val startIdx = html.indexOf(marker).takeIf { it >= 0 }
            ?: html.indexOf("window[\"ytInitialData\"] = ").takeIf { it >= 0 }
            ?: return emptyList()
        val jsonStart = html.indexOf('{', startIdx)
        if (jsonStart < 0) return emptyList()

        var depth = 0
        var inString = false
        var escaped = false
        var jsonEnd = -1
        for (i in jsonStart until html.length) {
            val c = html[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\' && inString) {
                escaped = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        jsonEnd = i
                        break
                    }
                }
            }
        }
        if (jsonEnd <= jsonStart) return emptyList()
        val jsonText = html.substring(jsonStart, jsonEnd + 1)
        return parseYtInitialData(JSONObject(jsonText))
    }

    private fun parseYtInitialData(root: JSONObject): List<MusicTrack> {
        val tracks = mutableListOf<MusicTrack>()
        val sections = root
            .optJSONObject("contents")
            ?.optJSONObject("twoColumnSearchResultsRenderer")
            ?.optJSONObject("primaryContents")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents") ?: return emptyList()

        for (s in 0 until sections.length()) {
            val items = sections.optJSONObject(s)
                ?.optJSONObject("itemSectionRenderer")
                ?.optJSONArray("contents") ?: continue

            for (i in 0 until items.length()) {
                val video = items.optJSONObject(i)?.optJSONObject("videoRenderer") ?: continue
                val videoId = video.optString("videoId").trim()
                if (videoId.isBlank()) continue

                val durationText = video.optJSONObject("lengthText")?.optString("simpleText")?.trim().orEmpty()
                val durationSeconds = parseColonDuration(durationText)
                if (durationSeconds < 45L) continue

                val title = extractRunsText(video.optJSONObject("title")).trim()
                val artist = extractRunsText(video.optJSONObject("ownerText"))
                    .ifBlank { extractRunsText(video.optJSONObject("longBylineText")) }
                    .trim()
                    .ifBlank { "YouTube" }
                if (title.isBlank()) continue

                val thumbs = video.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                val thumbUrl = if (thumbs != null && thumbs.length() > 0) {
                    thumbs.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty()
                } else {
                    "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                }

                tracks.add(
                    MusicTrack(
                        trackKey = "yt_$videoId",
                        title = title,
                        artist = artist,
                        album = "YouTube",
                        source = MusicSource.YOUTUBE,
                        streamUrl = videoId,
                        artworkUrl = thumbUrl.ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" },
                        durationSeconds = durationSeconds,
                        fullTrack = true
                    )
                )
                if (tracks.size >= 12) return tracks
            }
        }
        return tracks
    }

    private fun extractRunsText(obj: JSONObject?): String {
        if (obj == null) return ""
        val simple = obj.optString("simpleText")
        if (simple.isNotBlank()) return simple
        val runs = obj.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
        return sb.toString()
    }

    private fun fetchYouTubeViaPiped(encodedQuery: String): List<MusicTrack> {
        val instances = listOf(
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.adminforge.de"
        )
        for (base in instances) {
            val body = runCatching {
                executeGet("$base/search?q=$encodedQuery&filter=music_songs")
            }.getOrNull() ?: continue

            val items = runCatching { JSONObject(body).optJSONArray("items") }.getOrNull() ?: continue
            val tracks = mutableListOf<MusicTrack>()
            for (i in 0 until minOf(items.length(), 12)) {
                val item = items.optJSONObject(i) ?: continue
                val url = item.optString("url").trim()
                val videoId = url.substringAfter("v=", "").substringBefore("&").trim()
                if (videoId.isBlank()) continue
                val duration = item.optLong("duration", 0L)
                if (duration < 45L) continue
                val title = item.optString("title").trim()
                val artist = item.optString("uploaderName").trim().ifBlank { "YouTube" }
                val thumb = item.optString("thumbnail").trim()
                    .ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" }
                if (title.isBlank()) continue

                tracks.add(
                    MusicTrack(
                        trackKey = "yt_$videoId",
                        title = title,
                        artist = artist,
                        album = "YouTube",
                        source = MusicSource.YOUTUBE,
                        streamUrl = videoId,
                        artworkUrl = thumb,
                        durationSeconds = duration,
                        fullTrack = true
                    )
                )
            }
            if (tracks.isNotEmpty()) return tracks
        }
        return emptyList()
    }

    private fun fetchItunes(encodedQuery: String): List<MusicTrack> {
        val body = executeGet(
            "https://itunes.apple.com/search?term=$encodedQuery&media=music&entity=song&limit=15"
        ) ?: return emptyList()

        val root = JSONObject(body)
        val results = root.optJSONArray("results") ?: return emptyList()
        val tracks = mutableListOf<MusicTrack>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val previewUrl = item.optString("previewUrl").trim()
            if (previewUrl.isBlank()) continue
            val trackId = item.optLong("trackId", i.toLong())
            val title = item.optString("trackName").trim()
            val artist = item.optString("artistName").trim()
            if (title.isBlank() || artist.isBlank()) continue
            val album = item.optString("collectionName").trim()
            val rawArt = item.optString("artworkUrl100").trim()
            val highResArt = rawArt.replace("100x100bb", "600x600bb")

            tracks.add(
                MusicTrack(
                    trackKey = "itunes_$trackId",
                    title = title,
                    artist = artist,
                    album = album,
                    source = MusicSource.ITUNES,
                    streamUrl = previewUrl,
                    artworkUrl = highResArt,
                    durationSeconds = 30L,
                    fullTrack = false
                )
            )
        }
        return tracks
    }

    private fun fetchDeezer(encodedQuery: String): List<MusicTrack> {
        val body = executeGet(
            "https://api.deezer.com/search?q=$encodedQuery&limit=15"
        ) ?: return emptyList()

        val root = JSONObject(body)
        val data = root.optJSONArray("data") ?: return emptyList()
        val tracks = mutableListOf<MusicTrack>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val preview = item.optString("preview").trim()
            if (preview.isBlank()) continue
            val id = item.optLong("id", i.toLong())
            val title = item.optString("title").trim()
            val artistObj = item.optJSONObject("artist")
            val artist = artistObj?.optString("name")?.trim().orEmpty()
            if (title.isBlank() || artist.isBlank()) continue
            val albumObj = item.optJSONObject("album")
            val album = albumObj?.optString("title")?.trim().orEmpty()
            val artwork = albumObj?.optString("cover_big")?.trim()
                ?.ifBlank { albumObj.optString("cover_medium").trim() }
                .orEmpty()

            tracks.add(
                MusicTrack(
                    trackKey = "deezer_$id",
                    title = title,
                    artist = artist,
                    album = album,
                    source = MusicSource.DEEZER,
                    streamUrl = preview,
                    artworkUrl = artwork,
                    durationSeconds = 30L,
                    fullTrack = false
                )
            )
        }
        return tracks
    }

    private suspend fun fetchAudius(encodedQuery: String): List<MusicTrack> {
        val body = executeGet(
            "https://api.audius.co/v1/tracks/search?query=$encodedQuery&app_name=Tzlilim"
        ) ?: return emptyList()

        val root = JSONObject(body)
        val data = root.optJSONArray("data") ?: return emptyList()
        val candidates = mutableListOf<MusicTrack>()
        val limit = minOf(data.length(), 8)
        for (i in 0 until limit) {
            val item = data.optJSONObject(i) ?: continue
            val id = item.optString("id").trim()
            if (id.isBlank()) continue
            val streamable = item.optBoolean("is_streamable", true)
            if (!streamable) continue
            val title = item.optString("title").trim()
            val userObj = item.optJSONObject("user")
            val artist = userObj?.optString("name")?.trim().orEmpty()
            if (title.isBlank() || artist.isBlank()) continue
            val genre = item.optString("genre").trim()
            val duration = item.optLong("duration", 0L)
            if (duration < 45L) continue
            val artworkObj = item.optJSONObject("artwork")
            val artwork = artworkObj?.optString("480x480")?.trim()
                ?.ifBlank { artworkObj.optString("150x150").trim() }
                .orEmpty()

            val streamUrl = "https://api.audius.co/v1/tracks/$id/stream?app_name=Tzlilim"
            candidates.add(
                MusicTrack(
                    trackKey = "audius_$id",
                    title = title,
                    artist = artist,
                    album = genre,
                    source = MusicSource.AUDIUS,
                    streamUrl = streamUrl,
                    artworkUrl = artwork,
                    durationSeconds = duration.coerceAtLeast(0L),
                    fullTrack = true
                )
            )
        }
        return coroutineScope {
            candidates.map { track ->
                async(Dispatchers.IO) {
                    if (isAudioUrlReachable(track.streamUrl)) track else null
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun fetchInternetArchive(rawQuery: String): List<MusicTrack> {
        val cleanTerms = rawQuery.replace(Regex("[\"():]"), " ").trim()
        if (cleanTerms.isBlank()) return emptyList()
        val encodedSearch = URLEncoder.encode(
            "(title:($cleanTerms) OR creator:($cleanTerms)) AND mediatype:(audio) AND format:(VBR MP3)",
            "UTF-8"
        )
        val searchBody = executeGet(
            "https://archive.org/advancedsearch.php?q=$encodedSearch&fl[]=identifier,title,creator&rows=6&output=json"
        ) ?: return emptyList()

        val root = JSONObject(searchBody)
        val response = root.optJSONObject("response") ?: return emptyList()
        val docs = response.optJSONArray("docs") ?: return emptyList()

        val items = mutableListOf<Triple<String, String, String>>()
        for (i in 0 until minOf(docs.length(), 5)) {
            val doc = docs.optJSONObject(i) ?: continue
            val id = doc.optString("identifier").trim()
            val title = doc.optString("title").trim()
            val creatorRaw = doc.opt("creator")
            val artist = when (creatorRaw) {
                is JSONArray -> creatorRaw.optString(0).trim()
                is String -> creatorRaw.trim()
                else -> ""
            }.ifBlank { "Internet Archive" }
            if (id.isNotBlank() && title.isNotBlank()) {
                items.add(Triple(id, title, artist))
            }
        }

        return coroutineScope {
            items.map { (identifier, fallbackTitle, artist) ->
                async(Dispatchers.IO) {
                    runCatching {
                        val track = resolveArchiveAudioFile(identifier, fallbackTitle, artist)
                        if (track != null && isAudioUrlReachable(track.streamUrl)) track else null
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun resolveArchiveAudioFile(
        identifier: String,
        fallbackTitle: String,
        artist: String
    ): MusicTrack? {
        val metaBody = executeGet("https://archive.org/metadata/$identifier/files") ?: return null
        val root = JSONObject(metaBody)
        val files = root.optJSONArray("result") ?: return null

        var chosenFile: JSONObject? = null
        for (i in 0 until files.length()) {
            val file = files.optJSONObject(i) ?: continue
            val name = file.optString("name").lowercase()
            if (name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".ogg")) {
                chosenFile = file
                if (name.endsWith(".mp3")) break
            }
        }

        val fileObj = chosenFile ?: return null
        val fileName = fileObj.optString("name").trim()
        if (fileName.isBlank()) return null

        val encodedFile = fileName.split("/").joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
        val streamUrl = "https://archive.org/download/$identifier/$encodedFile"
        val artworkUrl = "https://archive.org/services/img/$identifier"
        val title = fileObj.optString("title").trim().ifBlank { fallbackTitle }
        val lengthStr = fileObj.optString("length").trim()
        val durationSec = parseArchiveDuration(lengthStr)

        return MusicTrack(
            trackKey = "archive_${identifier}_${fileName.hashCode().toUInt()}",
            title = title,
            artist = artist,
            album = "Internet Archive",
            source = MusicSource.ARCHIVE,
            streamUrl = streamUrl,
            artworkUrl = artworkUrl,
            durationSeconds = durationSec,
            fullTrack = true
        )
    }

    private fun parseColonDuration(raw: String): Long {
        if (raw.isBlank()) return 0L
        val parts = raw.split(":").mapNotNull { it.trim().toLongOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0L
        }
    }

    private fun parseArchiveDuration(raw: String): Long {
        if (raw.isBlank()) return 0L
        raw.toDoubleOrNull()?.let { return it.toLong().coerceAtLeast(0L) }
        return parseColonDuration(raw)
    }

    companion object {
        private const val TAG = "MultiSourceMusicRepo"

        fun interleaveAndDeduplicate(
            bySource: Map<MusicSource, List<MusicTrack>>,
            fullTracksOnly: Boolean = false
        ): List<MusicTrack> {
            val priorityOrder = if (fullTracksOnly) {
                listOf(
                    MusicSource.SOUNDCLOUD,
                    MusicSource.JIOSAAVN,
                    MusicSource.YOUTUBE,
                    MusicSource.AUDIUS,
                    MusicSource.ARCHIVE
                )
            } else {
                listOf(
                    MusicSource.SOUNDCLOUD,
                    MusicSource.JIOSAAVN,
                    MusicSource.YOUTUBE,
                    MusicSource.AUDIUS,
                    MusicSource.ARCHIVE,
                    MusicSource.ITUNES,
                    MusicSource.DEEZER
                )
            }

            val queues = priorityOrder.mapNotNull { source ->
                bySource[source]
                    ?.filter { !fullTracksOnly || it.fullTrack }
                    ?.takeIf { it.isNotEmpty() }
                    ?.toMutableList()
            }

            val merged = mutableListOf<MusicTrack>()
            val seenKeys = mutableSetOf<String>()
            var addedAny: Boolean
            do {
                addedAny = false
                for (q in queues) {
                    if (q.isNotEmpty()) {
                        val track = q.removeAt(0)
                        val dedupSig = "${track.source.name}|${track.title.lowercase()}|${track.artist.lowercase()}"
                        if (seenKeys.add(dedupSig)) {
                            merged.add(track)
                        }
                        addedAny = true
                    }
                }
            } while (addedAny)
            return merged
        }
    }
}
