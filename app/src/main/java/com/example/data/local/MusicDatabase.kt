package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import com.example.model.MusicTrack
import com.example.model.Playlist
import com.example.model.PlaylistTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val trackCount: Long,
    val coverUrl: String,
    val createdAtMs: Long,
    val updatedAtMs: Long
) {
    fun toDomain(): Playlist = Playlist(
        id = id,
        name = name,
        description = description,
        trackCount = trackCount,
        coverUrl = coverUrl,
        createdAtMs = createdAtMs
    )
}

@Entity(
    tableName = "playlist_tracks",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["playlistId"]),
        Index(value = ["playlistId", "trackKey"], unique = true)
    ]
)
data class PlaylistTrackEntity(
    @PrimaryKey val id: String,
    val playlistId: String,
    val trackKey: String,
    val title: String,
    val artist: String,
    val album: String,
    val source: String,
    val streamUrl: String,
    val artworkUrl: String,
    val durationSeconds: Long,
    val fullTrack: Boolean,
    val addedAtMs: Long
) {
    fun toDomain(): PlaylistTrack = PlaylistTrack(
        id = id,
        playlistId = playlistId,
        trackKey = trackKey,
        title = title,
        artist = artist,
        album = album,
        source = source,
        streamUrl = streamUrl,
        artworkUrl = artworkUrl,
        durationSeconds = durationSeconds,
        fullTrack = fullTrack,
        addedAtMs = addedAtMs
    )
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAtMs ASC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY createdAtMs ASC LIMIT 1")
    suspend fun getFirstPlaylist(): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    suspend fun getPlaylistById(playlistId: String): PlaylistEntity?

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY addedAtMs DESC")
    fun observePlaylistTracks(playlistId: String): Flow<List<PlaylistTrackEntity>>

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY addedAtMs DESC")
    suspend fun getPlaylistTracksSync(playlistId: String): List<PlaylistTrackEntity>

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId AND trackKey = :trackKey LIMIT 1")
    suspend fun findTrackByKey(playlistId: String, trackKey: String): PlaylistTrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: PlaylistTrackEntity)

    @Query("UPDATE playlists SET name = :name, description = :description, updatedAtMs = :updatedAtMs WHERE id = :playlistId")
    suspend fun updatePlaylistInfo(playlistId: String, name: String, description: String, updatedAtMs: Long)

    @Query("UPDATE playlists SET trackCount = :count, coverUrl = :coverUrl, updatedAtMs = :updatedAtMs WHERE id = :playlistId")
    suspend fun updatePlaylistStats(playlistId: String, count: Long, coverUrl: String, updatedAtMs: Long)

    @Query("DELETE FROM playlist_tracks WHERE id = :trackId")
    suspend fun deleteTrackById(trackId: String)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylistById(playlistId: String)

    @Transaction
    suspend fun addTrackAndRefreshPlaylist(track: PlaylistTrackEntity) {
        insertTrack(track)
        val tracks = getPlaylistTracksSync(track.playlistId)
        val nextCover = tracks.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl ?: ""
        updatePlaylistStats(
            playlistId = track.playlistId,
            count = tracks.size.toLong(),
            coverUrl = nextCover,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    @Transaction
    suspend fun deleteTrackAndRefreshPlaylist(playlistId: String, trackId: String) {
        deleteTrackById(trackId)
        val tracks = getPlaylistTracksSync(playlistId)
        val nextCover = tracks.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl ?: ""
        updatePlaylistStats(
            playlistId = playlistId,
            count = tracks.size.toLong(),
            coverUrl = nextCover,
            updatedAtMs = System.currentTimeMillis()
        )
    }
}

@Database(
    entities = [PlaylistEntity::class, PlaylistTrackEntity::class],
    version = 1,
    exportSchema = false
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao

    companion object {
        @Volatile
        private var INSTANCE: MusicDatabase? = null

        fun getInstance(context: Context): MusicDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "tzlilim_music.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

class LocalPlaylistRepository(private val dao: PlaylistDao) {

    fun observePlaylists(): Flow<List<Playlist>> =
        dao.observePlaylists().map { list -> list.map { it.toDomain() } }

    fun observePlaylistTracks(playlistId: String): Flow<List<PlaylistTrack>> =
        dao.observePlaylistTracks(playlistId).map { list -> list.map { it.toDomain() } }

    suspend fun ensureDefaultPlaylist(defaultName: String, defaultDesc: String): Result<String> =
        runCatching {
            val existing = dao.getFirstPlaylist()
            if (existing != null) return@runCatching existing.id
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            dao.insertPlaylist(
                PlaylistEntity(
                    id = id,
                    name = defaultName,
                    description = defaultDesc,
                    trackCount = 0L,
                    coverUrl = "",
                    createdAtMs = now,
                    updatedAtMs = now
                )
            )
            id
        }

    suspend fun createPlaylist(name: String, description: String = ""): Result<String> =
        runCatching {
            val trimmedName = name.trim().take(60)
            require(trimmedName.isNotEmpty()) { "יש להזין שם לרשימת ההשמעה" }
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            dao.insertPlaylist(
                PlaylistEntity(
                    id = id,
                    name = trimmedName,
                    description = description.trim().take(200),
                    trackCount = 0L,
                    coverUrl = "",
                    createdAtMs = now,
                    updatedAtMs = now
                )
            )
            id
        }

    suspend fun renamePlaylist(playlistId: String, name: String, description: String): Result<Unit> =
        runCatching {
            val trimmedName = name.trim().take(60)
            require(trimmedName.isNotEmpty()) { "שם רשימת ההשמעה לא יכול להיות ריק" }
            dao.updatePlaylistInfo(
                playlistId = playlistId,
                name = trimmedName,
                description = description.trim().take(200),
                updatedAtMs = System.currentTimeMillis()
            )
        }

    suspend fun addTrackToPlaylist(playlistId: String, track: MusicTrack): Result<String> =
        runCatching {
            require(track.streamUrl.isNotBlank()) { "חסר קישור שמע תקין לשיר" }
            val existing = dao.findTrackByKey(playlistId, track.trackKey)
            if (existing != null) return@runCatching existing.id

            val id = UUID.randomUUID().toString()
            val entity = PlaylistTrackEntity(
                id = id,
                playlistId = playlistId,
                trackKey = track.trackKey,
                title = track.title.trim().take(200).ifBlank { "שיר ללא שם" },
                artist = track.artist.trim().take(200).ifBlank { "אמן לא ידוע" },
                album = track.album.trim().take(200),
                source = track.source.name,
                streamUrl = track.streamUrl.trim(),
                artworkUrl = track.artworkUrl.trim(),
                durationSeconds = track.durationSeconds.coerceAtLeast(0L),
                fullTrack = track.fullTrack,
                addedAtMs = System.currentTimeMillis()
            )
            dao.addTrackAndRefreshPlaylist(entity)
            id
        }

    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String): Result<Unit> =
        runCatching {
            dao.deleteTrackAndRefreshPlaylist(playlistId, trackId)
        }

    suspend fun deletePlaylist(playlistId: String): Result<Unit> =
        runCatching {
            dao.deletePlaylistById(playlistId)
        }
}
