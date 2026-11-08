package com.mochyy.mochymusic.ui.screens.settings.importsync

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.ui.component.SettingsGroup
import com.mochyy.mochymusic.ui.component.SettingsGroupStyle
import com.mochyy.mochymusic.ui.component.SettingsItem
import com.mochyy.mochymusic.ui.component.SubSettingsScaffold
import com.mochyy.mochymusic.viewmodels.ImportSyncViewModel

private const val UNMATCHED_SHOWN = 200

@Composable
fun ImportProgressScreen(
    navController: NavController,
    viewModel: ImportSyncViewModel = hiltViewModel(),
) {
    val progress by viewModel.importProgress.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    fun leave() {
        if (!navController.popBackStack(ImportSyncRoute, inclusive = false)) navController.navigateUp()
    }

    val resync = progress.resync
    SubSettingsScaffold(
        title = when {
            resync != null -> "Đồng bộ lại"
            progress.isRunning -> "Đang nhập"
            else -> "Nhập nhạc"
        },
        onBack = { navController.navigateUp() },
    ) {
        ImportInfoCard {
            val fraction = if (progress.totalTracks > 0) progress.processedTracks.toFloat() / progress.totalTracks else 0f
            Text(
                text = when {
                    progress.isRunning -> "${progress.processedTracks} / ${progress.totalTracks} bài hát"
                    progress.cancelled -> "Đã dừng"
                    progress.error != null -> if (resync != null) "Đồng bộ lại thất bại" else "Nhập nhạc thất bại"
                    progress.isDone -> "Hoàn tất"
                    else -> "Không có tiến trình nhập nào đang chạy"
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            if (progress.isRunning) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text(
                    text = progress.currentLabel.ifEmpty { "Đang bắt đầu…" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Bạn có thể rời khỏi trang này; quá trình nhập vẫn tiếp tục chạy nền và sẽ gửi thông báo khi hoàn tất.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (progress.isDone || progress.processedTracks > 0) {
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SummaryLine("Tìm thấy trên YouTube Music", "${progress.matchedTracks} / ${progress.totalTracks}")
                    if (resync != null && progress.isDone) SummaryLine("Đã thêm vào \"${resync.playlistName}\"", resync.added.toString())
                    if (progress.playlistsCreated > 0) SummaryLine("Danh sách phát mới", progress.playlistsCreated.toString())
                    if (progress.likedAdded > 0) SummaryLine("Bài hát đã thích", progress.likedAdded.toString())
                    if (progress.unmatched.isNotEmpty()) SummaryLine("Không tìm thấy", progress.unmatched.size.toString())
                }
            }
            progress.error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            if (progress.youtubeFailures.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Đã nhập vào ứng dụng nhưng chưa đầy đủ trên YouTube: ${progress.youtubeFailures.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        SettingsGroup(
            style = SettingsGroupStyle.Grouped,
            items = buildList {
                if (progress.isRunning) {
                    add(
                        SettingsItem(
                            title = { Text("Dừng nhập") },
                            descriptionText = "Các bài hát và danh sách phát đã nhập tính đến hiện tại sẽ được giữ lại",
                            icon = painterResource(R.drawable.close),
                            iconShape = CircleShape,
                            onClick = viewModel::cancelImport,
                        )
                    )
                } else {
                    add(
                        SettingsItem(
                            title = { Text("Xong") },
                            icon = painterResource(R.drawable.check),
                            iconShape = CircleShape,
                            onClick = {
                                viewModel.clearFinishedImport()
                                leave()
                            },
                        )
                    )
                }
                if (progress.unmatched.isNotEmpty()) {
                    add(
                        SettingsItem(
                            title = { Text("Sao chép danh sách bài hát không tìm thấy") },
                            descriptionText = "Để tự tìm kiếm thủ công",
                            icon = painterResource(R.drawable.share),
                            iconShape = CircleShape,
                            onClick = {
                                val text = progress.unmatched.joinToString("\n") { miss ->
                                    buildString {
                                        append(miss.track.title)
                                        if (miss.track.artist.isNotEmpty()) append(" - ").append(miss.track.artist)
                                        miss.playlistName?.let { append("  [").append(it).append(']') }
                                    }
                                }
                                clipboard.setText(AnnotatedString(text))
                                Toast.makeText(context, "Đã sao chép ${progress.unmatched.size} bài hát", Toast.LENGTH_SHORT).show()
                            },
                        )
                    )
                }
            },
        )

        // listed once finished: re-laying out hundreds of rows on every progress tick is wasted work
        if (progress.unmatched.isNotEmpty() && !progress.isRunning) {
            Spacer(Modifier.height(16.dp))
            SettingsGroup(
                title = "Không tìm thấy",
                style = SettingsGroupStyle.Grouped,
                items = progress.unmatched.take(UNMATCHED_SHOWN).map { miss ->
                    SettingsItem(
                        title = { Text(miss.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        descriptionText = listOfNotNull(miss.track.artist.ifEmpty { null }, miss.playlistName ?: "Bài hát đã thích")
                            .joinToString(" · "),
                    )
                },
            )
            if (progress.unmatched.size > UNMATCHED_SHOWN) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "và ${progress.unmatched.size - UNMATCHED_SHOWN} bài khác. Hãy sao chép danh sách để xem toàn bộ.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    androidx.compose.foundation.layout.Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
