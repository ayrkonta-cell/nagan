package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.MusicTrack
import com.example.model.Playlist
import com.example.model.PlaylistTrack
import com.example.model.UiState
import com.example.player.PlayerUiState
import com.example.ui.components.PlaylistEditorDialog
import com.example.ui.components.TrackArtwork
import com.example.ui.components.TrackRowCard
import com.example.ui.theme.HarmonicTeal
import com.example.ui.theme.StudioCard
import com.example.ui.theme.StudioCardElevated
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.VinylCoral

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsTab(
    playlistsState: UiState<List<Playlist>>,
    selectedPlaylistId: String?,
    tracksState: UiState<List<PlaylistTrack>>,
    playerState: PlayerUiState,
    onSelectPlaylist: (String) -> Unit,
    onCreatePlaylist: (String, String) -> Unit,
    onRenamePlaylist: (String, String, String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onPlayTrack: (MusicTrack, List<MusicTrack>) -> Unit,
    onPlayAll: (List<PlaylistTrack>, Boolean) -> Unit,
    onDeleteTrack: (String, PlaylistTrack) -> Unit,
    onGoToDiscover: () -> Unit,
    bottomContentPadding: Dp
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var playlistToRename by remember { mutableStateOf<Playlist?>(null) }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }

    when (playlistsState) {
        is UiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = VinylCoral)
            }
        }

        is UiState.Error -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = playlistsState.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
        }

        is UiState.Success -> {
            val playlists = playlistsState.data
            val activePlaylist = playlists.firstOrNull { it.id == selectedPlaylistId }
                ?: playlists.firstOrNull()

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = bottomContentPadding + 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item {
                            Surface(
                                shape = CircleShape,
                                color = VinylCoral,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable { showCreateDialog = true }
                                    .testTag("create_playlist_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Add,
                                        contentDescription = null,
                                        tint = StudioObsidian,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.create_playlist),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = StudioObsidian,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        items(playlists, key = { it.id }) { playlist ->
                            val selected = playlist.id == activePlaylist?.id
                            Surface(
                                shape = CircleShape,
                                color = if (selected) HarmonicTeal.copy(alpha = 0.2f) else StudioCard,
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) HarmonicTeal else Color.White.copy(alpha = 0.08f)
                                ),
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable { onSelectPlaylist(playlist.id) }
                                    .testTag("playlist_chip_${playlist.id}")
                            ) {
                                Text(
                                    text = "${playlist.name} (${playlist.trackCount})",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) HarmonicTeal else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }

                if (activePlaylist != null) {
                    val tracks = (tracksState as? UiState.Success)?.data.orEmpty()
                    val queue = tracks.map { it.toMusicTrack() }

                    item {
                        PlaylistHeroCard(
                            playlist = activePlaylist,
                            trackCount = tracks.size,
                            canDeletePlaylist = playlists.size > 1 || tracks.isNotEmpty(),
                            onPlayAll = { onPlayAll(tracks, false) },
                            onShuffleAll = { onPlayAll(tracks, true) },
                            onRenameClick = { playlistToRename = activePlaylist },
                            onDeletePlaylistClick = { playlistToDelete = activePlaylist }
                        )
                    }

                    when (tracksState) {
                        is UiState.Loading -> {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = VinylCoral)
                                }
                            }
                        }

                        is UiState.Error -> {
                            item {
                                Text(
                                    text = tracksState.message,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }

                        is UiState.Success -> {
                            if (tracks.isEmpty()) {
                                item {
                                    EmptyPlaylistCard(onGoToDiscover = onGoToDiscover)
                                }
                            } else {
                                items(tracks, key = { it.id }) { trackEntity ->
                                    val musicTrack = trackEntity.toMusicTrack()
                                    val dismissState = rememberSwipeToDismissBoxState()

                                    LaunchedEffect(dismissState.currentValue) {
                                        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
                                            onDeleteTrack(activePlaylist.id, trackEntity)
                                        }
                                    }

                                    SwipeToDismissBox(
                                        state = dismissState,
                                        enableDismissFromStartToEnd = false,
                                        backgroundContent = {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clip(RoundedCornerShape(18.dp))
                                                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.25f))
                                                    .padding(horizontal = 20.dp),
                                                contentAlignment = Alignment.CenterEnd
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.DeleteOutline,
                                                    contentDescription = stringResource(R.string.remove_from_playlist),
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    ) {
                                        TrackRowCard(
                                            track = musicTrack,
                                            isCurrentTrack = playerState.currentTrack?.trackKey == musicTrack.trackKey,
                                            isPlaying = playerState.isPlaying,
                                            isInPlaylist = true,
                                            onPlayClick = { onPlayTrack(musicTrack, queue) },
                                            onDeleteClick = {
                                                onDeleteTrack(activePlaylist.id, trackEntity)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    item {
                        EmptyPlaylistCard(onGoToDiscover = onGoToDiscover)
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        PlaylistEditorDialog(
            titleText = stringResource(R.string.create_playlist),
            confirmText = stringResource(R.string.create),
            onConfirm = { name, desc ->
                showCreateDialog = false
                onCreatePlaylist(name, desc)
            },
            onDismiss = { showCreateDialog = false }
        )
    }

    playlistToRename?.let { target ->
        PlaylistEditorDialog(
            initialName = target.name,
            initialDescription = target.description,
            titleText = "עריכת רשימת השמעה",
            confirmText = stringResource(R.string.save),
            onConfirm = { name, desc ->
                playlistToRename = null
                onRenamePlaylist(target.id, name, desc)
            },
            onDismiss = { playlistToRename = null }
        )
    }

    playlistToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            containerColor = StudioCard,
            title = { Text(stringResource(R.string.confirm_delete_title)) },
            text = {
                Text(stringResource(R.string.confirm_delete_playlist_msg, target.name))
            },
            confirmButton = {
                Button(
                    onClick = {
                        playlistToDelete = null
                        onDeletePlaylist(target.id)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = StudioObsidian
                    ),
                    modifier = Modifier.testTag("confirm_delete_playlist_button")
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun PlaylistHeroCard(
    playlist: Playlist,
    trackCount: Int,
    canDeletePlaylist: Boolean,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    onRenameClick: () -> Unit,
    onDeletePlaylistClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = StudioCard,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            HarmonicTeal.copy(alpha = 0.14f),
                            StudioCard,
                            VinylCoral.copy(alpha = 0.12f)
                        )
                    )
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                TrackArtwork(
                    artworkUrl = playlist.coverUrl,
                    contentDescription = playlist.name,
                    size = 76.dp,
                    cornerRadius = 16.dp
                )

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = playlist.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (playlist.description.isNotBlank()) {
                        Text(
                            text = playlist.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.tracks_count, trackCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = HarmonicTeal
                    )
                }

                Column {
                    IconButton(
                        onClick = onRenameClick,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "ערוך רשימת השמעה",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (canDeletePlaylist) {
                        IconButton(
                            onClick = onDeletePlaylistClick,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("delete_playlist_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = stringResource(R.string.delete_playlist),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onPlayAll,
                    enabled = trackCount > 0,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VinylCoral,
                        contentColor = StudioObsidian
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("play_all_button")
                ) {
                    Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.play_all), fontWeight = FontWeight.Bold)
                }

                FilledTonalButton(
                    onClick = onShuffleAll,
                    enabled = trackCount > 0,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = StudioCardElevated,
                        contentColor = HarmonicTeal
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("shuffle_play_button")
                ) {
                    Icon(imageVector = Icons.Rounded.Shuffle, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.shuffle_play), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun EmptyPlaylistCard(
    onGoToDiscover: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = StudioCard,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_empty_playlist),
                contentDescription = stringResource(R.string.empty_playlist_title),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .clip(RoundedCornerShape(18.dp))
            )

            Icon(
                imageVector = Icons.Rounded.LibraryMusic,
                contentDescription = null,
                tint = HarmonicTeal,
                modifier = Modifier.size(30.dp)
            )

            Text(
                text = stringResource(R.string.empty_playlist_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Text(
                text = stringResource(R.string.empty_playlist_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Button(
                onClick = onGoToDiscover,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = VinylCoral,
                    contentColor = StudioObsidian
                ),
                modifier = Modifier.height(48.dp)
            ) {
                Icon(imageVector = Icons.Rounded.Search, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.go_to_search),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
