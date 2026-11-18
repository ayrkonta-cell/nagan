package com.example.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.data.remote.JioSaavnClient
import com.example.data.remote.SoundCloudClient
import com.example.model.MusicSource
import com.example.model.MusicTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlayerUiState(
    val currentTrack: MusicTrack? = null,
    val queue: List<MusicTrack> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatEnabled: Boolean = false,
    val errorMessage: String? = null
)

@OptIn(UnstableApi::class)
class MusicPlayerController(
    context: Context,
    private val soundCloudClient: SoundCloudClient = SoundCloudClient(),
    private val youTubeStreamResolver: YouTubeStreamResolver = YouTubeStreamResolver(soundCloudClient)
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var progressJob: Job? = null
    private var resolveJob: Job? = null
    private val pendingFallbackStreams = ArrayDeque<ResolvedAudioStream>()
    private var attemptedCrossSourceFallback = false

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(YouTubeStreamResolver.DEFAULT_UA)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(10_000)
        .setReadTimeoutMs(15_000)

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            true
        )
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()
        .apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
        }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) startProgressLoop() else stopProgressLoop()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                handleTrackCompleted()
                return
            }
            val buffering = playbackState == Player.STATE_BUFFERING
            val duration = exoPlayer.duration.takeIf { it > 0L } ?: _state.value.durationMs
            _state.update {
                it.copy(
                    isBuffering = buffering,
                    durationMs = duration,
                    positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                )
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val current = _state.value.currentTrack
            val nextCandidate = pendingFallbackStreams.removeFirstOrNull()
            if (current != null && nextCandidate != null) {
                Log.w(TAG, "Stream candidate failed (${error.errorCodeName}), trying ${nextCandidate.sourceLabel}")
                playStreamInExoPlayer(current, nextCandidate.url, nextCandidate.userAgent)
                return
            }

            if (current != null && !attemptedCrossSourceFallback) {
                attemptedCrossSourceFallback = true
                resolveJob?.cancel()
                resolveJob = scope.launch {
                    val altStreams = youTubeStreamResolver.resolveCrossSourceFallback(
                        trackTitle = current.title,
                        trackArtist = current.artist
                    )
                    if (_state.value.currentTrack?.trackKey != current.trackKey) return@launch
                    val firstAlt = altStreams.firstOrNull()
                    if (firstAlt != null) {
                        altStreams.drop(1).take(6).forEach { pendingFallbackStreams.addLast(it) }
                        playStreamInExoPlayer(current, firstAlt.url, firstAlt.userAgent)
                    } else {
                        publishPlaybackError(current, error)
                    }
                }
                return
            }

            Log.e(TAG, "ExoPlayer playback error: ${error.errorCodeName}", error)
            publishPlaybackError(current, error)
        }
    }

    init {
        exoPlayer.addListener(listener)
    }

    private fun publishPlaybackError(track: MusicTrack?, error: PlaybackException) {
        val msg = if (track?.source == MusicSource.YOUTUBE) {
            "לא נמצא שיר חלופי להשמעה עבור \"${track.title}\" — נסה תוצאה אחרת"
        } else {
            "לא ניתן לנגן שיר זה כרגע (${error.errorCodeName})"
        }
        _state.update {
            it.copy(
                isPlaying = false,
                isBuffering = false,
                errorMessage = msg
            )
        }
    }

    fun playTrack(track: MusicTrack, queue: List<MusicTrack> = listOf(track)) {
        val normalizedQueue = if (queue.any { it.trackKey == track.trackKey }) queue else listOf(track)
        val startIndex = normalizedQueue.indexOfFirst { it.trackKey == track.trackKey }.coerceAtLeast(0)
        playAtIndex(normalizedQueue, startIndex)
    }

    private fun playAtIndex(queue: List<MusicTrack>, index: Int) {
        val track = queue.getOrNull(index) ?: return
        resolveJob?.cancel()
        pendingFallbackStreams.clear()
        attemptedCrossSourceFallback = false

        _state.update {
            it.copy(
                currentTrack = track,
                queue = queue,
                currentIndex = index,
                positionMs = 0L,
                durationMs = track.durationSeconds * 1000L,
                isPlaying = false,
                isBuffering = true,
                errorMessage = null
            )
        }

        when (track.source) {
            MusicSource.SOUNDCLOUD -> {
                stopProgressLoop()
                exoPlayer.pause()
                exoPlayer.clearMediaItems()
                resolveJob = scope.launch {
                    val streams = soundCloudClient.resolvePlayableStreams(track.streamUrl)
                    if (_state.value.currentTrack?.trackKey != track.trackKey) return@launch
                    val first = streams.firstOrNull()
                    if (first != null) {
                        streams.drop(1).forEach { pendingFallbackStreams.addLast(it) }
                        playStreamInExoPlayer(track, first.url, first.userAgent)
                    } else {
                        tryCrossSourceForTrack(track)
                    }
                }
            }

            MusicSource.YOUTUBE -> {
                stopProgressLoop()
                exoPlayer.pause()
                exoPlayer.clearMediaItems()
                attemptedCrossSourceFallback = true
                val videoId = extractYouTubeId(track)
                resolveJob = scope.launch {
                    val candidates = youTubeStreamResolver.resolveAudioStreams(
                        videoId = videoId,
                        trackTitle = track.title,
                        trackArtist = track.artist
                    )
                    if (_state.value.currentTrack?.trackKey != track.trackKey) return@launch
                    val first = candidates.firstOrNull()
                    if (first != null) {
                        candidates.drop(1).take(6).forEach { pendingFallbackStreams.addLast(it) }
                        playStreamInExoPlayer(track, first.url, first.userAgent)
                    } else {
                        _state.update {
                            it.copy(
                                isPlaying = false,
                                isBuffering = false,
                                errorMessage = "לא נמצא זרם שמע מלא לשיר זה — נסה שיר אחר מהרשימה"
                            )
                        }
                    }
                }
            }

            MusicSource.JIOSAAVN -> {
                val qualityUrls = JioSaavnClient.expandQualityFallbacks(track.streamUrl)
                qualityUrls.drop(1).forEach { url ->
                    pendingFallbackStreams.addLast(ResolvedAudioStream(url, JioSaavnClient.WEB_UA, "JioSaavn"))
                }
                playStreamInExoPlayer(track, qualityUrls.first(), JioSaavnClient.WEB_UA)
            }

            else -> {
                playStreamInExoPlayer(track, track.streamUrl, YouTubeStreamResolver.DEFAULT_UA)
            }
        }
    }

    private suspend fun tryCrossSourceForTrack(track: MusicTrack) {
        attemptedCrossSourceFallback = true
        val fallback = youTubeStreamResolver.resolveCrossSourceFallback(track.title, track.artist)
        if (_state.value.currentTrack?.trackKey != track.trackKey) return
        val first = fallback.firstOrNull()
        if (first != null) {
            fallback.drop(1).take(6).forEach { pendingFallbackStreams.addLast(it) }
            playStreamInExoPlayer(track, first.url, first.userAgent)
        } else {
            _state.update {
                it.copy(
                    isPlaying = false,
                    isBuffering = false,
                    errorMessage = "לא ניתן להשמיע שיר זה כרגע — נסה בחירה אחרת"
                )
            }
        }
    }

    private fun playStreamInExoPlayer(track: MusicTrack, url: String, userAgent: String) {
        httpDataSourceFactory.setUserAgent(userAgent)
        val mediaItem = MediaItem.Builder()
            .setMediaId(track.trackKey)
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .build()
            )
            .build()
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.play()
    }

    private fun extractYouTubeId(track: MusicTrack): String {
        val raw = track.streamUrl.trim()
        if (raw.matches(Regex("^[a-zA-Z0-9_\\-]{11}$"))) return raw
        val match = Regex("(?:v=|/)([a-zA-Z0-9_\\-]{11})").find(raw)
        if (match != null) return match.groupValues[1]
        return track.trackKey.removePrefix("yt_")
    }

    fun togglePlayPause() {
        val current = _state.value.currentTrack ?: return
        if (exoPlayer.mediaItemCount > 0) {
            if (exoPlayer.isPlaying) {
                exoPlayer.pause()
            } else {
                if (exoPlayer.playbackState == Player.STATE_ENDED) {
                    exoPlayer.seekTo(0L)
                }
                exoPlayer.play()
            }
        } else if (current.source == MusicSource.YOUTUBE || current.source == MusicSource.SOUNDCLOUD) {
            playTrack(current, _state.value.queue)
        }
    }

    fun seekTo(positionMs: Long) {
        val safePos = positionMs.coerceAtLeast(0L)
        if (_state.value.currentTrack == null) return
        exoPlayer.seekTo(safePos)
        _state.update { it.copy(positionMs = safePos) }
    }

    fun playNext() {
        val snapshot = _state.value
        val queue = snapshot.queue
        if (queue.isEmpty()) return

        val nextIndex = if (snapshot.shuffleEnabled && queue.size > 1) {
            val candidates = queue.indices.filter { it != snapshot.currentIndex }
            candidates.random()
        } else if (snapshot.currentIndex + 1 < queue.size) {
            snapshot.currentIndex + 1
        } else if (snapshot.repeatEnabled) {
            0
        } else {
            return
        }
        playAtIndex(queue, nextIndex)
    }

    fun playPrevious() {
        val snapshot = _state.value
        val queue = snapshot.queue
        if (queue.isEmpty()) return
        if (snapshot.positionMs > 3500L) {
            seekTo(0L)
            return
        }
        val prevIndex = if (snapshot.currentIndex - 1 >= 0) {
            snapshot.currentIndex - 1
        } else if (snapshot.repeatEnabled) {
            queue.lastIndex
        } else {
            0
        }
        playAtIndex(queue, prevIndex)
    }

    private fun handleTrackCompleted() {
        val snapshot = _state.value
        if (snapshot.repeatEnabled && snapshot.queue.size <= 1) {
            seekTo(0L)
            exoPlayer.play()
            return
        }
        if (snapshot.currentIndex + 1 < snapshot.queue.size || snapshot.repeatEnabled || snapshot.shuffleEnabled) {
            playNext()
        } else {
            _state.update { it.copy(isPlaying = false, isBuffering = false) }
        }
    }

    fun toggleShuffle() {
        _state.update { it.copy(shuffleEnabled = !it.shuffleEnabled) }
    }

    fun toggleRepeat() {
        _state.update { it.copy(repeatEnabled = !it.repeatEnabled) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                val duration = exoPlayer.duration.takeIf { it > 0L } ?: _state.value.durationMs
                _state.update {
                    it.copy(
                        positionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
                        durationMs = duration
                    )
                }
                delay(400L)
            }
        }
    }

    private fun stopProgressLoop() {
        progressJob?.cancel()
        progressJob = null
    }

    fun release() {
        resolveJob?.cancel()
        pendingFallbackStreams.clear()
        stopProgressLoop()
        exoPlayer.removeListener(listener)
        exoPlayer.release()
    }

    companion object {
        private const val TAG = "MusicPlayerController"
    }
}
