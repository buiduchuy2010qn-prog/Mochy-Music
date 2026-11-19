// LibraryPlaylistsScreen.kt

package com.mochyy.mochymusic.ui.screens.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mochyy.mochymusic.LocalDatabase
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.cloud.MochyOfflineBackupCodec
import com.mochyy.mochymusic.importer.ImportSource
import com.mochyy.mochymusic.ui.component.CreatePlaylistDialog
import com.mochyy.mochymusic.ui.component.LocalMenuState
import com.mochyy.mochymusic.ui.component.TopToaster
import com.mochyy.mochymusic.ui.menu.PlaylistMenu
import com.mochyy.mochymusic.ui.screens.search.AvatarSize
import com.mochyy.mochymusic.ui.screens.search.SearchColors
import com.mochyy.mochymusic.ui.screens.settings.importsync.ImportProvidersRoute
import com.mochyy.mochymusic.viewmodels.LibraryHomeViewModel
import com.mochyy.mochymusic.viewmodels.LibraryShelfKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The library's playlists, or with a [shelf] the Local or Cached tab's: only the playlists that
 * have one of that tab's songs in them. With [source], only the playlists imported from that service.
 */
@Composable
fun LibraryPlaylistsScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
    pureBlack: Boolean = false,
    shelf: LibraryShelfKind? = null,
    source: ImportSource? = null,
) {
    val viewModel: LibraryHomeViewModel = hiltViewModel()
    val all by (shelf?.let(viewModel::shelf)?.playlists ?: viewModel.playlists).collectAsStateWithLifecycle()
    val playlistSources by viewModel.playlistSources.collectAsState()
    val playlists = remember(all, playlistSources, source) {
        if (source == null) all else all.filter { playlistSources[it.id] == source }
    }
    val menuState = LocalMenuState.current
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val appContext = context.applicationContext
    val database = LocalDatabase.current
    val resources = context.resources

    var showCreateDialog by remember { mutableStateOf(false) }

    val mochyplPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            TopToaster.show(
                text = appContext.getString(R.string.playlist_hub_importing_mochypl),
                iconRes = R.drawable.playlist_add,
                durationMs = 5000L,
            )
            val result = withContext(Dispatchers.IO) {
                MochyOfflineBackupCodec.importSinglePlaylistFromUri(appContext, uri)
            }
            result.onSuccess { imported ->
                val songsLabel = appContext.resources.getQuantityString(
                    R.plurals.n_song,
                    imported.trackCount,
                    imported.trackCount,
                )
                TopToaster.show(
                    text = appContext.getString(
                        R.string.playlist_hub_imported_toast,
                        imported.name,
                        songsLabel,
                    ),
                    iconRes = R.drawable.playlist_play,
                )
                navController.navigate("local_playlist/${imported.playlistId}") {
                    launchSingleTop = true
                }
            }.onFailure { err ->
                TopToaster.error(
                    err.message ?: appContext.getString(R.string.playlist_hub_import_mochypl_failed),
                )
            }
        }
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onPlaylistCreated = { id ->
                navController.navigate("local_playlist/$id") {
                    launchSingleTop = true
                }
            },
        )
    }

    LibraryEntityListScreen(
        title = stringResource(R.string.lib_playlist),
        subtitle = when {
            source != null -> stringResource(R.string.lib_playlists_imported_subtitle, source.label)
            shelf == LibraryShelfKind.Local -> stringResource(R.string.lib_playlists_local_subtitle)
            shelf == LibraryShelfKind.Cached -> stringResource(R.string.lib_playlists_cached_subtitle)
            else -> stringResource(R.string.lib_playlists_all_subtitle)
        },
        searchPlaceholder = stringResource(R.string.lib_playlists_search),
        items = playlists,
        idOf = { it.id },
        nameOf = { it.playlist.name },
        subtitleOf = { resources.getQuantityString(R.plurals.lib_song_count, it.songCount, it.songCount) },
        thumbnailOf = { it.thumbnails.firstOrNull() },
        // the same key Home's cards use, so the playlist opens growing out of its cover
        sharedElementKeyOf = { "playlist-${it.id}" },
        onClick = { playlist -> navController.navigate("local_playlist/${playlist.id}") },
        onLongClick = { playlist ->
            menuState.show {
                PlaylistMenu(
                    playlist = playlist,
                    coroutineScope = coroutineScope,
                    onDismiss = menuState::dismiss,
                    navController = navController,
                )
            }
        },
        trailing = if (shelf == null && source == null) {
            {
                Box(
                    modifier = Modifier
                        .size(AvatarSize)
                        .clip(CircleShape)
                        .background(SearchColors.TileHigh)
                        .clickable(
                            role = Role.Button,
                            onClick = {
                                menuState.showPlaylistHubSheet(
                                    context = context,
                                    database = database,
                                    onCreatePlaylist = { showCreateDialog = true },
                                    onOpenMochyplFile = { mochyplPicker.launch(arrayOf("*/*")) },
                                    onImportFromServices = { navController.navigate(ImportProvidersRoute) },
                                    onPlaylistImported = { playlistId ->
                                        navController.navigate("local_playlist/$playlistId") {
                                            launchSingleTop = true
                                        }
                                    },
                                )
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.add),
                        contentDescription = stringResource(R.string.playlist_hub_open_desc),
                        tint = SearchColors.Primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        } else null,
        modifier = modifier,
        pureBlack = pureBlack,
    )
}
