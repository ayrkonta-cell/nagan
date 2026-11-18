package com.example.data.remote

import android.util.Base64
import android.util.Log
import com.example.model.MusicSource
import com.example.model.MusicTrack
import com.example.player.ResolvedAudioStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class JioSaavnClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    suspend fun searchFullTracks(query: String, limit: Int = 15): List<MusicTrack> =
        withContext(Dispatchers.IO) {
            val clean = query.trim()
            if (clean.isEmpty()) return@withContext emptyList()
            val encoded = URLEncoder.encode(clean, "UTF-8")
            val body = runCatching {
                val req = Request.Builder()
                    .url(
                        "https://www.jiosaavn.com/api.php?__call=search.getResults" +
                            "&_format=json&_marker=0&api_version=4&ctx=web6dot0&n=$limit&q=$encoded"
                    )
                    .header("User-Agent", WEB_UA)
                    .build()
                client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) null else res.body?.string()
                }
            }.onFailure {
                Log.d(TAG, "Search error: ${it.message}")
            }.getOrNull() ?: return@withContext emptyList()

            parseTracksResponse(body)
        }

    suspend fun searchAndResolveBestMatch(title: String, artist: String): List<ResolvedAudioStream> =
        withContext(Dispatchers.IO) {
            val queries = SoundCloudClient.buildCandidateQueries(title, artist)
            for (q in queries) {
                val tracks = runCatching { searchFullTracks(q, limit = 4) }.getOrDefault(emptyList())
                val first = tracks.firstOrNull() ?: continue
                return@withContext expandQualityFallbacks(first.streamUrl).map { url ->
                    ResolvedAudioStream(url, WEB_UA, "JioSaavn")
                }
            }
            emptyList()
        }

    companion object {
        private const val TAG = "JioSaavnClient"
        private const val DES_KEY = "38346591"
        const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        fun parseTracksResponse(jsonText: String): List<MusicTrack> {
            val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return emptyList()
            val results = root.optJSONArray("results") ?: return emptyList()
            val tracks = mutableListOf<MusicTrack>()

            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val id = item.optString("id").trim()
                if (id.isBlank()) continue

                val moreInfo = item.optJSONObject("more_info") ?: continue
                val duration = moreInfo.optString("duration").toLongOrNull() ?: 0L
                if (duration < 45L) continue

                val encUrl = moreInfo.optString("encrypted_media_url").trim()
                if (encUrl.isBlank()) continue
                val is320 = moreInfo.optString("320kbps").equals("true", ignoreCase = true)
                val decrypted = decryptStreamUrl(encUrl, is320) ?: continue

                val title = decodeHtml(item.optString("title")).trim()
                if (title.isBlank()) continue

                val primaryArtists = moreInfo.optJSONObject("artistMap")
                    ?.optJSONArray("primary_artists")
                val artistName = buildString {
                    if (primaryArtists != null) {
                        for (a in 0 until minOf(primaryArtists.length(), 2)) {
                            val name = decodeHtml(
                                primaryArtists.optJSONObject(a)?.optString("name").orEmpty()
                            ).trim()
                            if (name.isNotBlank()) {
                                if (isNotEmpty()) append(", ")
                                append(name)
                            }
                        }
                    }
                }.ifBlank {
                    decodeHtml(item.optString("subtitle")).trim().ifBlank { "JioSaavn" }
                }

                val album = decodeHtml(moreInfo.optString("album")).trim().ifBlank { "JioSaavn" }
                val art = item.optString("image").trim().replace("150x150", "500x500")

                tracks.add(
                    MusicTrack(
                        trackKey = "jio_$id",
                        title = title,
                        artist = artistName,
                        album = album,
                        source = MusicSource.JIOSAAVN,
                        streamUrl = decrypted,
                        artworkUrl = art,
                        durationSeconds = duration,
                        fullTrack = true
                    )
                )
            }
            return tracks
        }

        fun decryptStreamUrl(encryptedBase64: String, prefer320: Boolean): String? =
            runCatching {
                val keySpec = SecretKeySpec(DES_KEY.toByteArray(Charsets.UTF_8), "DES")
                val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, keySpec)
                val rawBytes = Base64.decode(encryptedBase64, Base64.DEFAULT)
                val decrypted = String(cipher.doFinal(rawBytes), Charsets.UTF_8).trim()
                if (!decrypted.startsWith("http")) return@runCatching null
                val targetQuality = if (prefer320) "_320.mp4" else "_160.mp4"
                decrypted.replace("_96.mp4", targetQuality)
            }.getOrNull()

        fun expandQualityFallbacks(url: String): List<String> {
            val list = linkedSetOf(url)
            if (url.contains("_320.mp4")) {
                list.add(url.replace("_320.mp4", "_160.mp4"))
                list.add(url.replace("_320.mp4", "_96.mp4"))
            } else if (url.contains("_160.mp4")) {
                list.add(url.replace("_160.mp4", "_96.mp4"))
            }
            return list.toList()
        }

        private fun decodeHtml(input: String): String =
            input.replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&#039;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
    }
}
