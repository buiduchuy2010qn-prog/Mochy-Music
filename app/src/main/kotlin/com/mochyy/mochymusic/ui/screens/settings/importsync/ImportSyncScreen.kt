package com.mochyy.mochymusic.ui.screens.settings.importsync

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.ui.component.DefaultDialog
import com.mochyy.mochymusic.ui.component.SettingsGroup
import com.mochyy.mochymusic.ui.component.SettingsGroupStyle
import com.mochyy.mochymusic.ui.component.SettingsItem
import com.mochyy.mochymusic.ui.component.SubSettingsScaffold
import com.mochyy.mochymusic.viewmodels.ImportSyncViewModel
import java.time.LocalDate

/** experimental hub: import from other services, sync a youtube account, tunemymusic csv, and backup */
@Composable
fun ImportSyncScreen(
    navController: NavController,
    viewModel: ImportSyncViewModel = hiltViewModel(),
) {
    val progress by viewModel.importProgress.collectAsState()
    val exportMessage by viewModel.exportMessage.collectAsState()
    val signedIn = rememberYouTubeSignedIn()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.export(uri)
    }

    SubSettingsScaffold(
        title = "Nhập & đồng bộ",
        onBack = { navController.navigateUp() },
    ) {
        if (progress.isRunning || progress.isDone) {
            val resync = progress.resync
            ImportInfoCard {
                Text(
                    text = when {
                        resync != null && progress.isRunning -> "Đang đồng bộ lại \"${resync.playlistName}\"…"
                        progress.isRunning -> "Đang nhập… ${progress.processedTracks} / ${progress.totalTracks}"
                        progress.cancelled -> "Đã dừng nhập"
                        resync != null && resync.failed.size < resync.playlists ->
                            (if (resync.playlists == 1) "Đã đồng bộ lại \"${resync.playlistName}\": " else "Đã đồng bộ lại ${resync.playlists} danh sách phát: ") +
                                "${resync.added} bài hát mới"
                        progress.error != null -> "Nhập nhạc thất bại"
                        else -> "Hoàn tất nhập: ${progress.matchedTracks} / ${progress.totalTracks} bài hát"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (progress.isRunning && progress.totalTracks > 0) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { progress.processedTracks.toFloat() / progress.totalTracks },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            SettingsGroup(
                style = SettingsGroupStyle.Grouped,
                items = listOf(
                    SettingsItem(
                        title = { Text(if (progress.isRunning) "Xem tiến độ" else "Xem kết quả") },
                        icon = painterResource(R.drawable.playlist_play),
                        iconShape = CircleShape,
                        onClick = { navController.navigate(ImportProgressRoute) { launchSingleTop = true } },
                    )
                ),
            )
            Spacer(Modifier.height(16.dp))
        }

        SettingsGroup(
            style = SettingsGroupStyle.Grouped,
            items = listOf(
                SettingsItem(
                    title = { Text("Nhập nhạc") },
                    highlightKey = "Import",
                    descriptionText = "Đăng nhập và chọn danh sách phát",
                    detail = "Đăng nhập vào Spotify, Apple Music, YouTube Music hoặc Tidal và chọn danh sách phát",
                    icon = painterResource(R.drawable.playlist_add),
                    iconShape = CircleShape,
                    onClick = { navController.navigate(ImportProvidersRoute) },
                ),
                SettingsItem(
                    title = { Text("Đồng bộ") },
                    highlightKey = "Sync",
                    descriptionText = if (signedIn) "Đã kết nối tài khoản YouTube: danh sách phát, bài hát đã thích và lịch sử"
                    else "Kết nối tài khoản YouTube để đồng bộ danh sách phát, bài hát đã thích và lịch sử",
                    icon = painterResource(R.drawable.sync),
                    iconShape = CircleShape,
                    onClick = { navController.navigate(YouTubeSyncRoute) },
                ),
                SettingsItem(
                    title = { Text(stringResource(R.string.tv_link_title)) },
                    highlightKey = "Mochy on your TV",
                    descriptionText = stringResource(R.string.tv_link_row_detail),
                    icon = painterResource(R.drawable.smartphone),
                    iconShape = CircleShape,
                    onClick = { navController.navigate(com.mochyy.mochymusic.ui.screens.settings.tvlink.TvLinkRoute) },
                ),
                SettingsItem(
                    title = { Text("TuneMyMusic CSV") },
                    highlightKey = "TuneMyMusic CSV",
                    descriptionText = "Amazon Music, SoundCloud, Deezer và nhiều dịch vụ khác",
                    detail = "Mọi dịch vụ khác như thư viện Amazon Music, SoundCloud hoặc Deezer",
                    icon = painterResource(R.drawable.web_link),
                    iconShape = CircleShape,
                    onClick = { navController.navigate(ImportTuneMyMusicRoute) },
                ),
            ),
        )

        Spacer(Modifier.height(16.dp))

        SettingsGroup(
            title = "Sao lưu",
            style = SettingsGroupStyle.Grouped,
            items = listOf(
                SettingsItem(
                    title = { Text("Xuất ra tệp CSV Mochy") },
                    highlightKey = "Export to Mochy CSV",
                    descriptionText = "Bài hát đã thích và danh sách phát",
                    detail = "Bài hát đã thích và danh sách phát, có thể nhập lại từ mục Nhập nhạc → Bản sao lưu Mochy",
                    icon = painterResource(R.drawable.backup),
                    iconShape = CircleShape,
                    onClick = { exportLauncher.launch("mochy-library-${LocalDate.now()}.csv") },
                ),
            ),
        )
    }

    exportMessage?.let { message ->
        val working = message == "Exporting…" || message == "Đang xuất…"
        DefaultDialog(
            onDismiss = { if (!working) viewModel.clearExportMessage() },
            buttons = {
                if (!working) TextButton(onClick = viewModel::clearExportMessage) { Text("OK") }
            },
        ) {
            Text(message, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
