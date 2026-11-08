package com.mochyy.mochymusic.ui.screens.settings.importsync

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.constants.AccountEmailKey
import com.mochyy.mochymusic.constants.AccountNameKey
import com.mochyy.mochymusic.constants.YtExcludeImportsKey
import com.mochyy.mochymusic.constants.YtPullHistoryKey
import com.mochyy.mochymusic.constants.YtReportPlaysKey
import com.mochyy.mochymusic.constants.YtSyncNewPlaylistsKey
import com.mochyy.mochymusic.constants.YtmSyncKey
import com.mochyy.mochymusic.ui.component.AppSwitch
import com.mochyy.mochymusic.ui.component.DefaultDialog
import com.mochyy.mochymusic.ui.component.SettingsGroup
import com.mochyy.mochymusic.ui.component.SettingsGroupStyle
import com.mochyy.mochymusic.ui.component.SettingsItem
import com.mochyy.mochymusic.ui.component.SubSettingsScaffold
import com.mochyy.mochymusic.utils.rememberPreference
import com.mochyy.mochymusic.viewmodels.ImportSyncViewModel

@Composable
fun YouTubeSyncScreen(
    navController: NavController,
    viewModel: ImportSyncViewModel = hiltViewModel(),
) {
    val signedIn = rememberYouTubeSignedIn()
    val accountName by rememberPreference(AccountNameKey, "")
    val accountEmail by rememberPreference(AccountEmailKey, "")
    val (autoSync, onAutoSyncChange) = rememberPreference(YtmSyncKey, true)
    val (syncNewPlaylists, onSyncNewPlaylistsChange) = rememberPreference(YtSyncNewPlaylistsKey, false)
    val (reportPlays, onReportPlaysChange) = rememberPreference(YtReportPlaysKey, true)
    val (pullHistory, onPullHistoryChange) = rememberPreference(YtPullHistoryKey, false)
    val (excludeImports, onExcludeImportsChange) = rememberPreference(YtExcludeImportsKey, false)
    val state by viewModel.youTubeState.collectAsState()

    var confirmSignOut by remember { mutableStateOf(false) }
    var confirmUpload by remember { mutableStateOf(false) }
    var confirmUndo by remember { mutableStateOf(false) }

    SubSettingsScaffold(
        title = "Đồng bộ",
        onBack = { navController.navigateUp() },
    ) {
        SettingsGroup(
            title = "Tài khoản YouTube",
            style = SettingsGroupStyle.Grouped,
            items = listOf(
                if (signedIn) {
                    SettingsItem(
                        title = { Text(accountName.ifBlank { "Đã đăng nhập" }) },
                        descriptionText = accountEmail.ifBlank { "YouTube Music" },
                        icon = painterResource(R.drawable.account),
                        iconShape = CircleShape,
                        trailingContent = {
                            TextButton(onClick = { confirmSignOut = true }) { Text("Đăng xuất") }
                        },
                    )
                } else {
                    SettingsItem(
                        title = { Text("Đăng nhập vào YouTube") },
                        descriptionText = "Cần thiết để đồng bộ. Thông tin đăng nhập chỉ lưu trên điện thoại này",
                        icon = painterResource(R.drawable.login),
                        iconShape = CircleShape,
                        onClick = { navController.navigate(YouTubeLoginRoute) },
                    )
                }
            ),
        )

        Spacer(Modifier.height(16.dp))

        SettingsGroup(
            title = "Duy trì đồng bộ",
            style = SettingsGroupStyle.Grouped,
            items = listOf(
                toggleItem(
                    title = "Đồng bộ thư viện khi mở ứng dụng",
                    description = "Bài hát đã thích, thư viện, nghệ sĩ và danh sách phát đã lưu từ YouTube",
                    icon = R.drawable.sync,
                    checked = autoSync,
                    enabled = signedIn,
                    onChange = onAutoSyncChange,
                ),
                toggleItem(
                    title = "Tạo danh sách phát mới trên YouTube",
                    description = "Danh sách phát bạn tạo tại đây cũng sẽ được tạo trên YouTube",
                    icon = R.drawable.playlist_add,
                    checked = syncNewPlaylists,
                    enabled = signedIn,
                    onChange = onSyncNewPlaylistsChange,
                ),
                toggleItem(
                    title = "Gửi lượt phát vào lịch sử YouTube",
                    description = "Bài hát bạn nghe tại đây sẽ hiển thị trong lịch sử YouTube",
                    icon = R.drawable.history,
                    checked = reportPlays,
                    enabled = signedIn,
                    onChange = onReportPlaysChange,
                ),
                toggleItem(
                    title = "Sao chép lịch sử YouTube về đây",
                    description = "Lượt nghe từ các thiết bị khác sẽ được gộp vào lịch sử Mochy sau mỗi lần đồng bộ",
                    icon = R.drawable.history,
                    checked = pullHistory,
                    enabled = signedIn,
                    onChange = onPullHistoryChange,
                ),
            ),
        )

        Spacer(Modifier.height(16.dp))

        SettingsGroup(
            title = "Ứng dụng khác",
            style = SettingsGroupStyle.Grouped,
            items = listOf(
                toggleItem(
                    title = stringResource(R.string.yt_sync_exclude_title),
                    description = stringResource(R.string.yt_sync_exclude_desc),
                    icon = R.drawable.cloud,
                    checked = excludeImports,
                    enabled = true,
                    onChange = onExcludeImportsChange,
                ),
            ),
        )

        Spacer(Modifier.height(16.dp))

        SettingsGroup(
            title = "Tác vụ tức thì",
            style = SettingsGroupStyle.Grouped,
            items = listOf(
                SettingsItem(
                    title = { Text("Đồng bộ ngay") },
                    descriptionText = if (pullHistory) "Thư viện, danh sách phát và lịch sử" else "Thư viện và danh sách phát",
                    icon = painterResource(R.drawable.refresh),
                    iconShape = CircleShape,
                    enabled = signedIn && !state.running,
                    onClick = viewModel::syncNow,
                ),
                SettingsItem(
                    title = { Text("Sao chép lịch sử YouTube ngay") },
                    descriptionText = "Chỉ thêm những lượt phát chưa có trong Mochy",
                    icon = painterResource(R.drawable.history),
                    iconShape = CircleShape,
                    enabled = signedIn && !state.running,
                    onClick = viewModel::pullHistoryNow,
                ),
                SettingsItem(
                    title = { Text(stringResource(R.string.yt_sync_undo_title)) },
                    descriptionText = stringResource(R.string.yt_sync_undo_desc),
                    icon = painterResource(R.drawable.undo),
                    iconShape = CircleShape,
                    enabled = signedIn && state.canUndo && !state.running,
                    onClick = { confirmUndo = true },
                ),
                SettingsItem(
                    title = { Text("Đưa danh sách phát cục bộ lên YouTube") },
                    descriptionText = "Sao chép danh sách phát trên máy lên YouTube",
                    detail = "Tạo bản sao trên YouTube cho mọi danh sách phát chỉ có trên thiết bị này",
                    icon = painterResource(R.drawable.cloud),
                    iconShape = CircleShape,
                    enabled = signedIn && !state.running,
                    onClick = { confirmUpload = true },
                ),
            ),
        )

        if (state.running || state.message != null) {
            Spacer(Modifier.height(16.dp))
            ImportInfoCard {
                if (state.running) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(state.label, style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    Text(state.message.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = viewModel::clearYouTubeMessage) { Text("OK") }
                }
            }
        }

        if (!signedIn) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Nhập từ liên kết danh sách phát YouTube không cần tài khoản. Hãy dùng Nhập nhạc → YouTube Music.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmSignOut) {
        DefaultDialog(
            onDismiss = { confirmSignOut = false },
            horizontalAlignment = Alignment.Start,
            title = { Text("Đăng xuất khỏi YouTube?") },
            buttons = {
                TextButton(onClick = { confirmSignOut = false }) { Text("Hủy") }
                TextButton(onClick = {
                    confirmSignOut = false
                    viewModel.signOut()
                }) { Text("Đăng xuất") }
            },
        ) {
            Text(
                "Mọi nội dung đã có trong thư viện vẫn được giữ nguyên. Quá trình đồng bộ sẽ dừng cho đến khi bạn đăng nhập lại.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    if (confirmUndo) {
        DefaultDialog(
            onDismiss = { confirmUndo = false },
            horizontalAlignment = Alignment.Start,
            title = { Text(stringResource(R.string.yt_sync_undo_dialog_title)) },
            buttons = {
                TextButton(onClick = { confirmUndo = false }) { Text("Hủy") }
                TextButton(onClick = {
                    confirmUndo = false
                    viewModel.undoLastSync(withYouTube = false)
                }) { Text(stringResource(R.string.yt_sync_undo_phone)) }
                TextButton(
                    enabled = signedIn,
                    onClick = {
                        confirmUndo = false
                        viewModel.undoLastSync(withYouTube = true)
                    },
                ) { Text(stringResource(R.string.yt_sync_undo_both)) }
            },
        ) {
            Text(
                stringResource(R.string.yt_sync_undo_dialog_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.yt_sync_undo_both_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmUpload) {
        DefaultDialog(
            onDismiss = { confirmUpload = false },
            horizontalAlignment = Alignment.Start,
            title = { Text("Đưa danh sách phát cục bộ lên YouTube?") },
            buttons = {
                TextButton(onClick = { confirmUpload = false }) { Text("Hủy") }
                TextButton(onClick = {
                    confirmUpload = false
                    viewModel.uploadLocalPlaylists()
                }) { Text("Tạo") }
            },
        ) {
            Text(
                "Mỗi danh sách phát chỉ có trên Mochy sẽ được tạo một bản sao riêng tư trên tài khoản YouTube của bạn. Sau đó, mọi thay đổi sẽ được đồng bộ cả hai bên.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun toggleItem(
    title: String,
    description: String,
    icon: Int,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) = SettingsItem(
    title = { Text(title) },
    highlightKey = title,
    descriptionText = description,
    icon = painterResource(icon),
    iconShape = CircleShape,
    enabled = enabled,
    onClick = { onChange(!checked) },
    trailingContent = { AppSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
)
