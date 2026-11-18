package com.example.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.LocalPlaylistRepository
import com.example.data.remote.MultiSourceMusicRepository
import com.example.data.remote.SearchProgressEvent
import com.example.model.MusicSource
import com.example.model.MusicTrack
import com.example.model.Playlist
import com.example.model.PlaylistTrack
import com.example.model.SourceQueryState
import com.example.model.SourceSearchStatus
import com.example.model.UiState
import com.example.player.MusicPlayerController
import com.example.player.PlayerUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(
    private val playlistRepository: LocalPlaylistRepository,
    private val musicRepository: MultiSourceMusicRepository,
    private val playerController: MusicPlayerController,
    defaultPlaylistName: String,
    defaultPlaylistDesc: String
) : ViewModel() {

    val playbackController: MusicPlayerController = playerController

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _hasSearched = MutableStateFlow(false)
    val hasSearched: StateFlow<Boolean> = _hasSearched.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _selectedSourceFilter = MutableStateFlow<MusicSource?>(null)
    val selectedSourceFilter: StateFlow<MusicSource?> = _selectedSourceFilter.asStateFlow()

    private val _fullTracksOnly = MutableStateFlow(true)
    val fullTracksOnly: StateFlow<Boolean> = _fullTracksOnly.asStateFlow()

    private val _sourceStatuses = MutableStateFlow(
        MusicSource.entries.map { SourceSearchStatus(it) }
    )
    val sourceStatuses: StateFlow<List<SourceSearchStatus>> = _sourceStatuses.asStateFlow()

    private val _resultsBySource = MutableStateFlow<Map<MusicSource, List<MusicTrack>>>(emptyMap())

    val filteredSearchResults: StateFlow<List<MusicTrack>> =
        combine(_resultsBySource, _selectedSourceFilter, _fullTracksOnly) { map, filter, fullOnly ->
            if (filter != null) {
                map[filter].orEmpty()
            } else {
                val primary = MultiSourceMusicRepository.interleaveAndDeduplicate(
                    bySource = map,
                    fullTracksOnly = fullOnly
                )
                if (primary.isEmpty() && fullOnly) {
                    MultiSourceMusicRepository.interleaveAndDeduplicate(map, fullTracksOnly = false)
                } else {
                    primary
                }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    private val _selectedPlaylistId = MutableStateFlow<String?>(null)
    val selectedPlaylistId: StateFlow<String?> = _selectedPlaylistId.asStateFlow()

    val playlistsState: StateFlow<UiState<List<Playlist>>> =
        playlistRepository.observePlaylists()
            .map<List<Playlist>, UiState<List<Playlist>>> { playlists ->
                val activeId = _selectedPlaylistId.value
                if (playlists.isNotEmpty() && (activeId == null || playlists.none { it.id == activeId })) {
                    _selectedPlaylistId.value = playlists.first().id
                } else if (playlists.isEmpty()) {
                    _selectedPlaylistId.value = null
                }
                UiState.Success(playlists)
            }
            .catch { error ->
                if (error is CancellationException) throw error
                Log.w(TAG, "Error observing playlists", error)
                emit(UiState.Error(error.message ?: "שגיאה בטעינת רשימות ההשמעה"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = UiState.Loading
            )

    val selectedPlaylistTracksState: StateFlow<UiState<List<PlaylistTrack>>> =
        _selectedPlaylistId.flatMapLatest { playlistId ->
            if (playlistId.isNullOrBlank()) {
                flowOf(UiState.Success(emptyList()))
            } else {
                playlistRepository.observePlaylistTracks(playlistId)
                    .map<List<PlaylistTrack>, UiState<List<PlaylistTrack>>> { UiState.Success(it) }
                    .catch { error ->
                        if (error is CancellationException) throw error
                        Log.w(TAG, "Error observing playlist tracks", error)
                        emit(UiState.Error(error.message ?: "שגיאה בטעינת שירי הפלייליסט"))
                    }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    val playerState: StateFlow<PlayerUiState> = playerController.state

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            val res = playlistRepository.ensureDefaultPlaylist(
                defaultName = defaultPlaylistName,
                defaultDesc = defaultPlaylistDesc
            )
            res.onSuccess { id ->
                if (_selectedPlaylistId.value == null) {
                    _selectedPlaylistId.value = id
                }
            }
        }
    }

    fun onQueryChanged(value: String) {
        _searchQuery.value = value
    }

    fun selectSourceFilter(source: MusicSource?) {
        _selectedSourceFilter.value = source
        if (source == null) {
            _fullTracksOnly.value = false
        }
    }

    fun toggleFullTracksOnly() {
        _selectedSourceFilter.value = null
        _fullTracksOnly.update { !it }
    }

    fun performSearch(overrideQuery: String? = null) {
        val queryToRun = (overrideQuery ?: _searchQuery.value).trim()
        if (queryToRun.isEmpty()) return
        if (overrideQuery != null) {
            _searchQuery.value = overrideQuery
        }

        searchJob?.cancel()
        _hasSearched.value = true
        _isSearching.value = true
        _resultsBySource.value = emptyMap()
        _sourceStatuses.value = MusicSource.entries.map {
            SourceSearchStatus(it, SourceQueryState.SEARCHING, 0)
        }

        searchJob = viewModelScope.launch {
            try {
                musicRepository.searchAllSources(queryToRun).collect { event ->
                    when (event) {
                        is SearchProgressEvent.SourceStarted -> {
                            _sourceStatuses.update { list ->
                                list.map {
                                    if (it.source == event.source) {
                                        it.copy(state = SourceQueryState.SEARCHING)
                                    } else it
                                }
                            }
                        }

                        is SearchProgressEvent.SourceCompleted -> {
                            _resultsBySource.update { current ->
                                current + (event.source to event.tracks)
                            }
                            _sourceStatuses.update { list ->
                                list.map {
                                    if (it.source == event.source) {
                                        it.copy(
                                            state = if (event.success) SourceQueryState.DONE else SourceQueryState.FAILED,
                                            count = event.tracks.size
                                        )
                                    } else it
                                }
                            }
                        }
                    }
                }
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun selectPlaylist(playlistId: String) {
        _selectedPlaylistId.value = playlistId
    }

    fun createPlaylist(name: String, description: String) {
        viewModelScope.launch {
            val result = playlistRepository.createPlaylist(name, description)
            result.onSuccess { newId ->
                _selectedPlaylistId.value = newId
                _snackbarEvents.tryEmit("רשימת ההשמעה \"$name\" נוצרה")
            }.onFailure { err ->
                _snackbarEvents.tryEmit(err.message ?: "לא ניתן ליצור רשימת השמעה")
            }
        }
    }

    fun renamePlaylist(playlistId: String, name: String, description: String) {
        viewModelScope.launch {
            val result = playlistRepository.renamePlaylist(playlistId, name, description)
            result.onSuccess {
                _snackbarEvents.tryEmit("רשימת ההשמעה עודכנה")
            }.onFailure { err ->
                _snackbarEvents.tryEmit(err.message ?: "לא ניתן לעדכן רשימת השמעה")
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            val result = playlistRepository.deletePlaylist(playlistId)
            result.onSuccess {
                _snackbarEvents.tryEmit("רשימת ההשמעה נמחקה")
            }.onFailure { err ->
                _snackbarEvents.tryEmit(err.message ?: "לא ניתן למחוק רשימת השמעה")
            }
        }
    }

    fun addTrackToPlaylist(playlistId: String, track: MusicTrack) {
        viewModelScope.launch {
            val result = playlistRepository.addTrackToPlaylist(
                playlistId = playlistId,
                track = track
            )
            result.onSuccess {
                _snackbarEvents.tryEmit("\"${track.title}\" נוסף לרשימת ההשמעה")
            }.onFailure { err ->
                _snackbarEvents.tryEmit(err.message ?: "שגיאה בהוספת השיר")
            }
        }
    }

    fun removeTrackFromPlaylist(playlistId: String, track: PlaylistTrack) {
        viewModelScope.launch {
            val result = playlistRepository.removeTrackFromPlaylist(
                playlistId = playlistId,
                trackId = track.id
            )
            result.onSuccess {
                _snackbarEvents.tryEmit("\"${track.title}\" נמחק מרשימת ההשמעה")
            }.onFailure { err ->
                _snackbarEvents.tryEmit(err.message ?: "שגיאה במחיקת השיר")
            }
        }
    }

    fun playTrackFromList(track: MusicTrack, queue: List<MusicTrack>) {
        playerController.playTrack(track, queue)
    }

    fun playPlaylist(tracks: List<PlaylistTrack>, shuffle: Boolean) {
        if (tracks.isEmpty()) return
        val musicList = tracks.map { it.toMusicTrack() }
        val finalQueue = if (shuffle) musicList.shuffled() else musicList
        playerController.playTrack(finalQueue.first(), finalQueue)
    }

    fun togglePlayPause() = playerController.togglePlayPause()
    fun seekTo(ms: Long) = playerController.seekTo(ms)
    fun playNext() = playerController.playNext()
    fun playPrevious() = playerController.playPrevious()
    fun toggleShuffle() = playerController.toggleShuffle()
    fun toggleRepeat() = playerController.toggleRepeat()
    fun clearPlayerError() = playerController.clearError()

    override fun onCleared() {
        super.onCleared()
        searchJob?.cancel()
    }

    private companion object {
        const val TAG = "MainViewModel"
        const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
