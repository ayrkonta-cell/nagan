package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.MusicSource
import com.example.model.MusicTrack
import com.example.model.SourceQueryState
import com.example.model.SourceSearchStatus
import com.example.player.PlayerUiState
import com.example.ui.components.TrackRowCard
import com.example.ui.theme.HarmonicTeal
import com.example.ui.theme.StudioCard
import com.example.ui.theme.StudioCardElevated
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.VinylCoral

private val QuickDiscoverQueries = listOf(
    "עומר אדם",
    "עידן רייכל",
    "Lo-Fi Chill",
    "Acoustic Guitar",
    "Jazz Piano",
    "Electronic Beats",
    "Ambient Space"
)

@Composable
fun DiscoverTab(
    query: String,
    hasSearched: Boolean,
    isSearching: Boolean,
    sourceStatuses: List<SourceSearchStatus>,
    selectedSourceFilter: MusicSource?,
    fullTracksOnly: Boolean,
    results: List<MusicTrack>,
    savedTrackKeys: Set<String>,
    playerState: PlayerUiState,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: (String?) -> Unit,
    onSelectSourceFilter: (MusicSource?) -> Unit,
    onToggleFullTracksOnly: () -> Unit,
    onPlayTrack: (MusicTrack, List<MusicTrack>) -> Unit,
    onAddTrackClick: (MusicTrack) -> Unit,
    bottomContentPadding: androidx.compose.ui.unit.Dp
) {
    val keyboardController = LocalSoftwareKeyboardController.current

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
            SearchHeaderCard(
                query = query,
                isSearching = isSearching,
                onQueryChange = onQueryChange,
                onSubmit = {
                    keyboardController?.hide()
                    onSearchSubmit(null)
                }
            )
        }

        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(QuickDiscoverQueries) { suggestion ->
                    Surface(
                        shape = CircleShape,
                        color = StudioCardElevated,
                        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.08f)),
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable {
                                keyboardController?.hide()
                                onSearchSubmit(suggestion)
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = HarmonicTeal,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = suggestion,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        item {
            AnimatedVisibility(visible = hasSearched) {
                MultiSourceScannerRow(
                    sourceStatuses = sourceStatuses,
                    selectedFilter = selectedSourceFilter,
                    fullTracksOnly = fullTracksOnly,
                    totalCount = sourceStatuses.sumOf { it.count },
                    onSelectFilter = onSelectSourceFilter,
                    onToggleFullTracksOnly = onToggleFullTracksOnly
                )
            }
        }

        if (isSearching && results.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = VinylCoral)
                        Text(
                            text = stringResource(R.string.search_all_sources),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else if (!hasSearched) {
            item {
                EmptyDiscoverState(
                    title = stringResource(R.string.empty_search_title),
                    subtitle = stringResource(R.string.empty_search_subtitle)
                )
            }
        } else if (results.isEmpty() && !isSearching) {
            item {
                EmptyDiscoverState(
                    title = stringResource(R.string.no_results_title),
                    subtitle = stringResource(R.string.no_results_subtitle)
                )
            }
        } else {
            items(results, key = { it.trackKey }) { track ->
                TrackRowCard(
                    track = track,
                    isCurrentTrack = playerState.currentTrack?.trackKey == track.trackKey,
                    isPlaying = playerState.isPlaying,
                    isInPlaylist = track.trackKey in savedTrackKeys,
                    onPlayClick = { onPlayTrack(track, results) },
                    onAddClick = { onAddTrackClick(track) }
                )
            }
        }
    }
}

@Composable
private fun SearchHeaderCard(
    query: String,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit
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
                            VinylCoral.copy(alpha = 0.14f),
                            StudioCard,
                            HarmonicTeal.copy(alpha = 0.09f)
                        )
                    )
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = VinylCoral
                )
                Text(
                    text = "חיפוש שירים מלאים בכל המקורות (SoundCloud, YouTube ועוד)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.search_placeholder),
                            maxLines = 1
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            tint = VinylCoral
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.cancel)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VinylCoral,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
                        focusedContainerColor = StudioObsidian.copy(alpha = 0.65f),
                        unfocusedContainerColor = StudioObsidian.copy(alpha = 0.45f)
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("search_input")
                )

                Button(
                    onClick = onSubmit,
                    enabled = query.trim().isNotEmpty() && !isSearching,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VinylCoral,
                        contentColor = StudioObsidian
                    ),
                    modifier = Modifier
                        .height(54.dp)
                        .testTag("search_button")
                ) {
                    if (isSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = StudioObsidian,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.search_action),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MultiSourceScannerRow(
    sourceStatuses: List<SourceSearchStatus>,
    selectedFilter: MusicSource?,
    fullTracksOnly: Boolean,
    totalCount: Int,
    onSelectFilter: (MusicSource?) -> Unit,
    onToggleFullTracksOnly: () -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        item {
            FilterChip(
                selected = fullTracksOnly && selectedFilter == null,
                onClick = onToggleFullTracksOnly,
                leadingIcon = if (fullTracksOnly) {
                    {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = HarmonicTeal,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else null,
                label = {
                    Text(
                        text = "שירים מלאים בלבד",
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = HarmonicTeal.copy(alpha = 0.2f),
                    selectedLabelColor = HarmonicTeal
                ),
                modifier = Modifier.testTag("full_tracks_only_chip")
            )
        }

        item {
            FilterChip(
                selected = selectedFilter == null && !fullTracksOnly,
                onClick = { onSelectFilter(null) },
                label = {
                    Text(
                        text = "${stringResource(R.string.source_all)} ($totalCount)",
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = VinylCoral.copy(alpha = 0.2f),
                    selectedLabelColor = VinylCoral
                )
            )
        }

        items(sourceStatuses, key = { it.source.name }) { status ->
            FilterChip(
                selected = selectedFilter == status.source,
                onClick = { onSelectFilter(status.source) },
                leadingIcon = {
                    when (status.state) {
                        SourceQueryState.SEARCHING -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = HarmonicTeal
                            )
                        }
                        SourceQueryState.DONE -> {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = HarmonicTeal,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        SourceQueryState.FAILED -> {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        SourceQueryState.IDLE -> Unit
                    }
                },
                label = {
                    Text(
                        text = "${status.source.displayName} (${status.count})",
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = HarmonicTeal.copy(alpha = 0.18f),
                    selectedLabelColor = HarmonicTeal
                )
            )
        }
    }
}

@Composable
private fun EmptyDiscoverState(
    title: String,
    subtitle: String
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = StudioCard,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = VinylCoral,
                modifier = Modifier.size(44.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
