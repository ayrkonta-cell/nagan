package com.example.model

enum class MusicSource(
    val id: String,
    val displayName: String,
    val isFullStream: Boolean
) {
    SOUNDCLOUD("SOUNDCLOUD", "SoundCloud", true),
    JIOSAAVN("JIOSAAVN", "JioSaavn", true),
    YOUTUBE("YOUTUBE", "YouTube", true),
    AUDIUS("AUDIUS", "Audius", true),
    ARCHIVE("ARCHIVE", "Archive", true),
    ITUNES("ITUNES", "iTunes", false),
    DEEZER("DEEZER", "Deezer", false);

    companion object {
        fun fromRaw(raw: String?): MusicSource =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SOUNDCLOUD
    }
}

data class MusicTrack(
    val trackKey: String,
    val title: String,
    val artist: String,
    val album: String,
    val source: MusicSource,
    val streamUrl: String,
    val artworkUrl: String,
    val durationSeconds: Long,
    val fullTrack: Boolean,
    val playlistTrackId: String? = null
)

data class Playlist(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val trackCount: Long = 0L,
    val coverUrl: String = "",
    val createdAtMs: Long = 0L
)

data class PlaylistTrack(
    val id: String = "",
    val playlistId: String = "",
    val trackKey: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val source: String = MusicSource.SOUNDCLOUD.name,
    val streamUrl: String = "",
    val artworkUrl: String = "",
    val durationSeconds: Long = 0L,
    val fullTrack: Boolean = true,
    val addedAtMs: Long = 0L
) {
    fun toMusicTrack(): MusicTrack = MusicTrack(
        trackKey = trackKey,
        title = title,
        artist = artist,
        album = album,
        source = MusicSource.fromRaw(source),
        streamUrl = streamUrl,
        artworkUrl = artworkUrl,
        durationSeconds = durationSeconds,
        fullTrack = fullTrack,
        playlistTrackId = id
    )
}

enum class SourceQueryState {
    IDLE,
    SEARCHING,
    DONE,
    FAILED
}

data class SourceSearchStatus(
    val source: MusicSource,
    val state: SourceQueryState = SourceQueryState.IDLE,
    val count: Int = 0
)

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
