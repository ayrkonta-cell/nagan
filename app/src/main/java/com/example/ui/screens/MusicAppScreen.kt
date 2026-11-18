package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.model.MusicTrack
import com.example.model.UiState
import com.example.ui.MainViewModel
import com.example.ui.components.MiniPlayerBar
import com.example.ui.components.NowPlayingBottomSheet
import com.example.ui.components.PlaylistEditorDialog
import com.example.ui.components.PlaylistPickerSheet
import com.example.ui.theme.HarmonicTeal
import com.example.ui.theme.StudioCard
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSlate
import com.example.ui.theme.VinylCoral

enum class AppTab {
    DISCOVER,
    PLAYLISTS
}

@Composable
fun MusicAppScreen(
    viewModel: MainViewModel
) {
    var activeTab by rememberSaveable { mutableStateOf(AppTab.DISCOVER) }
    var showFullPlayer by remember { mutableStateOf(false) }
    var trackForPicker by remember { mutableStateOf<MusicTrack?>(null) }
    var showCreatePlaylistFromPicker by remember { mutableStateOf(false) }

    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val hasSearched by viewModel.hasSearched.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val sourceStatuses by viewModel.sourceStatuses.collectAsStateWithLifecycle()
    val selectedSourceFilter by viewModel.selectedSourceFilter.collectAsStateWithLifecycle()
    val fullTracksOnly by viewModel.fullTracksOnly.collectAsStateWithLifecycle()
    val searchResults by viewModel.filteredSearchResults.collectAsStateWithLifecycle()
    val playlistsState by viewModel.playlistsState.collectAsStateWithLifecycle()
    val selectedPlaylistId by viewModel.selectedPlaylistId.collectAsStateWithLifecycle()
    val tracksState by viewModel.selectedPlaylistTracksState.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.snackbarEvents.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(playerState.errorMessage) {
        val err = playerState.errorMessage
        if (!err.isNullOrBlank()) {
            snackbarHostState.showSnackbar(err)
            viewModel.clearPlayerError()
        }
    }

    val playlists = (playlistsState as? UiState.Success)?.data.orEmpty()
    val savedTrackKeys = remember(tracksState) {
        (tracksState as? UiState.Success)?.data?.map { it.trackKey }?.toSet().orEmpty()
    }

    val handleAddTrackClick: (MusicTrack) -> Unit = { track ->
        if (playlists.size == 1) {
            viewModel.addTrackToPlaylist(playlists.first().id, track)
        } else {
            trackForPicker = track
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioObsidian)
    ) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            containerColor = StudioObsidian,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (!useRail) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(StudioSlate)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                    ) {
                        if (playerState.currentTrack != null) {
                            MiniPlayerBar(
                                playerState = playerState,
                                onTogglePlayPause = viewModel::togglePlayPause,
                                onNext = viewModel::playNext,
                                onExpand = { showFullPlayer = true }
                            )
                        }
                        NavigationBar(
                            containerColor = StudioSlate,
                            windowInsets = WindowInsets(0, 0, 0, 0)
                        ) {
                            NavigationBarItem(
                                selected = activeTab == AppTab.DISCOVER,
                                onClick = { activeTab = AppTab.DISCOVER },
                                icon = {
                                    Icon(
                                        imageVector = if (activeTab == AppTab.DISCOVER) {
                                            Icons.Rounded.Search
                                        } else Icons.Outlined.Search,
                                        contentDescription = null
                                    )
                                },
                                label = { Text(stringResource(R.string.nav_discover)) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = VinylCoral,
                                    selectedTextColor = VinylCoral,
                                    indicatorColor = VinylCoral.copy(alpha = 0.16f)
                                ),
                                modifier = Modifier.testTag("nav_discover")
                            )
                            NavigationBarItem(
                                selected = activeTab == AppTab.PLAYLISTS,
                                onClick = { activeTab = AppTab.PLAYLISTS },
                                icon = {
                                    Icon(
                                        imageVector = if (activeTab == AppTab.PLAYLISTS) {
                                            Icons.Rounded.LibraryMusic
                                        } else Icons.Outlined.LibraryMusic,
                                        contentDescription = null
                                    )
                                },
                                label = { Text(stringResource(R.string.nav_playlists)) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = HarmonicTeal,
                                    selectedTextColor = HarmonicTeal,
                                    indicatorColor = HarmonicTeal.copy(alpha = 0.16f)
                                ),
                                modifier = Modifier.testTag("nav_playlists")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                if (useRail) {
                    NavigationRail(
                        containerColor = StudioSlate,
                        modifier = Modifier
                            .fillMaxHeight()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                    ) {
                        Spacer(modifier = Modifier.weight(0.2f))
                        NavigationRailItem(
                            selected = activeTab == AppTab.DISCOVER,
                            onClick = { activeTab = AppTab.DISCOVER },
                            icon = {
                                Icon(
                                    imageVector = if (activeTab == AppTab.DISCOVER) {
                                        Icons.Rounded.Search
                                    } else Icons.Outlined.Search,
                                    contentDescription = null
                                )
                            },
                            label = { Text(stringResource(R.string.nav_discover)) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = VinylCoral,
                                selectedTextColor = VinylCoral,
                                indicatorColor = VinylCoral.copy(alpha = 0.16f)
                            ),
                            modifier = Modifier.testTag("nav_discover")
                        )
                        NavigationRailItem(
                            selected = activeTab == AppTab.PLAYLISTS,
                            onClick = { activeTab = AppTab.PLAYLISTS },
                            icon = {
                                Icon(
                                    imageVector = if (activeTab == AppTab.PLAYLISTS) {
                                        Icons.Rounded.LibraryMusic
                                    } else Icons.Outlined.LibraryMusic,
                                    contentDescription = null
                                )
                            },
                            label = { Text(stringResource(R.string.nav_playlists)) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = HarmonicTeal,
                                selectedTextColor = HarmonicTeal,
                                indicatorColor = HarmonicTeal.copy(alpha = 0.16f)
                            ),
                            modifier = Modifier.testTag("nav_playlists")
                        )
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TopStudioBar(totalPlaylists = playlists.size)

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(max = 840.dp)
                            .fillMaxWidth()
                    ) {
                        val bottomExtra = if (useRail && playerState.currentTrack != null) 84.dp else 12.dp
                        when (activeTab) {
                            AppTab.DISCOVER -> {
                                DiscoverTab(
                                    query = searchQuery,
                                    hasSearched = hasSearched,
                                    isSearching = isSearching,
                                    sourceStatuses = sourceStatuses,
                                    selectedSourceFilter = selectedSourceFilter,
                                    fullTracksOnly = fullTracksOnly,
                                    results = searchResults,
                                    savedTrackKeys = savedTrackKeys,
                                    playerState = playerState,
                                    onQueryChange = viewModel::onQueryChanged,
                                    onSearchSubmit = viewModel::performSearch,
                                    onSelectSourceFilter = viewModel::selectSourceFilter,
                                    onToggleFullTracksOnly = viewModel::toggleFullTracksOnly,
                                    onPlayTrack = viewModel::playTrackFromList,
                                    onAddTrackClick = handleAddTrackClick,
                                    bottomContentPadding = bottomExtra
                                )
                            }

                            AppTab.PLAYLISTS -> {
                                PlaylistsTab(
                                    playlistsState = playlistsState,
                                    selectedPlaylistId = selectedPlaylistId,
                                    tracksState = tracksState,
                                    playerState = playerState,
                                    onSelectPlaylist = viewModel::selectPlaylist,
                                    onCreatePlaylist = viewModel::createPlaylist,
                                    onRenamePlaylist = viewModel::renamePlaylist,
                                    onDeletePlaylist = viewModel::deletePlaylist,
                                    onPlayTrack = viewModel::playTrackFromList,
                                    onPlayAll = viewModel::playPlaylist,
                                    onDeleteTrack = viewModel::removeTrackFromPlaylist,
                                    onGoToDiscover = { activeTab = AppTab.DISCOVER },
                                    bottomContentPadding = bottomExtra
                                )
                            }
                        }

                        if (useRail && playerState.currentTrack != null) {
                            MiniPlayerBar(
                                playerState = playerState,
                                onTogglePlayPause = viewModel::togglePlayPause,
                                onNext = viewModel::playNext,
                                onExpand = { showFullPlayer = true },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .windowInsetsPadding(WindowInsets.navigationBars)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showFullPlayer) {
        NowPlayingBottomSheet(
            playerState = playerState,
            onDismiss = { showFullPlayer = false },
            onTogglePlayPause = viewModel::togglePlayPause,
            onSeekTo = viewModel::seekTo,
            onNext = viewModel::playNext,
            onPrevious = viewModel::playPrevious,
            onToggleShuffle = viewModel::toggleShuffle,
            onToggleRepeat = viewModel::toggleRepeat,
            onAddCurrentToPlaylist = { track ->
                showFullPlayer = false
                handleAddTrackClick(track)
            }
        )
    }

    trackForPicker?.let { track ->
        PlaylistPickerSheet(
            track = track,
            playlists = playlists,
            onSelectPlaylist = { playlist ->
                trackForPicker = null
                viewModel.addTrackToPlaylist(playlist.id, track)
            },
            onCreateNewClick = {
                showCreatePlaylistFromPicker = true
            },
            onDismiss = { trackForPicker = null }
        )
    }

    if (showCreatePlaylistFromPicker) {
        PlaylistEditorDialog(
            titleText = stringResource(R.string.create_playlist),
            confirmText = stringResource(R.string.create),
            onConfirm = { name, desc ->
                showCreatePlaylistFromPicker = false
                viewModel.createPlaylist(name, desc)
            },
            onDismiss = { showCreatePlaylistFromPicker = false }
        )
    }
}

@Composable
private fun TopStudioBar(totalPlaylists: Int) {
    Row(
        modifier = Modifier
            .widthIn(max = 840.dp)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(VinylCoral.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = VinylCoral,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Surface(
            shape = CircleShape,
            color = StudioCard
        ) {
            Text(
                text = "$totalPlaylists רשימות השמעה",
                style = MaterialTheme.typography.labelMedium,
                color = HarmonicTeal,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}
