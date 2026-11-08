package com.mochyy.mochymusic.ui.screens.settings.importsync

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.importer.LinkSource
import com.mochyy.mochymusic.importer.account.AccountService
import com.mochyy.mochymusic.ui.component.SettingsGroup
import com.mochyy.mochymusic.ui.component.SettingsGroupStyle
import com.mochyy.mochymusic.ui.component.SettingsItem
import com.mochyy.mochymusic.ui.component.SubSettingsScaffold
import com.mochyy.mochymusic.viewmodels.ImportSyncViewModel

@Composable
fun ImportProvidersScreen(
    navController: NavController,
    viewModel: ImportSyncViewModel = hiltViewModel(),
) {
    var linkSource by rememberSaveable { mutableStateOf<LinkSource?>(null) }

    // some file managers label .csv as text/plain or octet-stream, so don't filter too hard
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.loadFile(uri, sourceLabel = "Bản sao lưu Mochy")
    }

    SubSettingsScaffold(
        title = "Nhập nhạc",
        onBack = { navController.navigateUp() },
    ) {
        SettingsGroup(
            title = "Đăng nhập và chọn",
            style = SettingsGroupStyle.Grouped,
            items = listOf(
                AccountService.SPOTIFY to "Bài hát đã thích và danh sách phát của bạn",
                AccountService.APPLE_MUSIC to "Danh sách phát, Bài hát yêu thích và toàn bộ thư viện",
                AccountService.YOUTUBE_MUSIC to "Nhạc đã thích và danh sách phát, khớp chính xác bài hát · hỗ trợ đồng bộ",
                AccountService.TIDAL to "Bài hát của tôi và danh sách phát của bạn",
            ).map { (service, description) ->
                SettingsItem(
                    title = { Text(service.label) },
                    highlightKey = "${service.label} account",
                    descriptionText = description,
                    icon = painterResource(R.drawable.login),
                    iconShape = CircleShape,
                    onClick = { navController.navigate(accountImportRoute(service)) },
                )
            },
        )

        Spacer(Modifier.height(16.dp))

        SettingsGroup(
            title = "Hoặc dán liên kết công khai",
            style = SettingsGroupStyle.Grouped,
            items = LinkSource.entries.map { source ->
                SettingsItem(
                    title = { Text(source.label) },
                    highlightKey = source.label,
                    descriptionText = when (source) {
                        LinkSource.SPOTIFY -> "Danh sách phát công khai (100 bài đầu) và album"
                        LinkSource.APPLE_MUSIC -> "Danh sách phát và album công khai"
                        LinkSource.DEEZER -> "Danh sách phát và album công khai, không giới hạn số bài"
                        LinkSource.YOUTUBE -> "Danh sách phát và album, khớp chính xác bài hát"
                    },
                    icon = painterResource(R.drawable.link),
                    iconShape = CircleShape,
                    onClick = { linkSource = source },
                )
            },
        )


        Spacer(Modifier.height(16.dp))

        SettingsGroup(
            title = "Từ tệp tin",
            style = SettingsGroupStyle.Grouped,
            items = listOf(
                SettingsItem(
                    title = { Text("Bản sao lưu Mochy") },
                    highlightKey = "Mochy backup",
                    descriptionText = "Tệp CSV từ mục Xuất ra CSV Mochy. Khôi phục chính xác bài hát",
                    icon = painterResource(R.drawable.backup),
                    iconShape = CircleShape,
                    onClick = { backupPicker.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) },
                ),
                SettingsItem(
                    title = { Text("TuneMyMusic CSV") },
                    highlightKey = "TuneMyMusic CSV",
                    descriptionText = "Danh sách phát riêng tư, bài hát đã thích và mọi dịch vụ khác",
                    icon = painterResource(R.drawable.web_link),
                    iconShape = CircleShape,
                    onClick = { navController.navigate(ImportTuneMyMusicRoute) },
                ),
            ),
        )

        Spacer(Modifier.height(16.dp))

        SettingsGroup(
            title = "Dịch vụ khác",
            style = SettingsGroupStyle.Grouped,
            items = listOf(
                SettingsItem(
                    title = { Text("Amazon Music, SoundCloud, Pandora…") },
                    descriptionText = "Thông qua TuneMyMusic",
                    detail = "Thực hiện qua TuneMyMusic để đăng nhập và chuyển nhạc giúp bạn",
                    icon = painterResource(R.drawable.library_music),
                    iconShape = CircleShape,
                    onClick = { navController.navigate(ImportTuneMyMusicRoute) },
                ),
            ),
        )
    }

    linkSource?.let { source ->
        LinkInputDialog(
            source = source,
            onDismiss = { linkSource = null },
            onSubmit = { link ->
                linkSource = null
                viewModel.loadLink(link)
            },
        )
    }

    PendingImportHost(viewModel = viewModel, navController = navController)
}
