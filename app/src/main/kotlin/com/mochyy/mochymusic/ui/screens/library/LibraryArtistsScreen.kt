// LibraryArtistsScreen.kt

package com.mochyy.mochymusic.ui.screens.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mochyy.mochymusic.ui.component.LocalMenuState
import com.mochyy.mochymusic.ui.menu.ArtistMenu
import com.mochyy.mochymusic.viewmodels.LibraryHomeViewModel
import com.mochyy.mochymusic.viewmodels.LibraryShelfKind

/**
 * The library's artists, or with a [shelf] the Local or Cached tab's: only the artists of that
 * tab's songs, each with how many of its songs are there.
 */
@Composable
fun LibraryArtistsScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
    pureBlack: Boolean = false,
    shelf: LibraryShelfKind? = null,
) {
    val viewModel: LibraryHomeViewModel = hiltViewModel()
    val artists by (shelf?.let(viewModel::shelf)?.artists ?: viewModel.artists).collectAsStateWithLifecycle()
    val menuState = LocalMenuState.current
    val coroutineScope = rememberCoroutineScope()

    LibraryEntityListScreen(
        title = "Nghệ sĩ",
        subtitle = when (shelf) {
            LibraryShelfKind.Local -> "Các nghệ sĩ có nhạc lưu trên thiết bị này."
            LibraryShelfKind.Cached -> "Các nghệ sĩ có bài hát trong bộ nhớ đệm."
            null -> "Tất cả nghệ sĩ trong thư viện của bạn."
        },
        searchPlaceholder = "Tìm nghệ sĩ theo tên",
        items = artists,
        idOf = { it.id },
        nameOf = { it.artist.name },
        subtitleOf = {
            val count = "${it.songCount} bài hát"
            when (shelf) {
                LibraryShelfKind.Local -> "$count trên thiết bị này"
                LibraryShelfKind.Cached -> "$count đã lưu đệm"
                null -> count
            }
        },
        thumbnailOf = { it.artist.thumbnailUrl },
        roundThumbnails = true,
        onClick = { artist -> navController.navigate("artist/${artist.id}") },
        onLongClick = { artist ->
            menuState.show {
                ArtistMenu(
                    originalArtist = artist,
                    coroutineScope = coroutineScope,
                    onDismiss = menuState::dismiss,
                )
            }
        },
        modifier = modifier,
        pureBlack = pureBlack,
    )
}
