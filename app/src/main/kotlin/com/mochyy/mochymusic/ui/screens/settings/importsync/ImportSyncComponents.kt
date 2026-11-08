package com.mochyy.mochymusic.ui.screens.settings.importsync

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mochyy.mochymusic.constants.InnerTubeCookieKey
import com.mochyy.mochymusic.importer.LinkSource
import com.mochyy.mochymusic.importer.ParsedImport
import com.mochyy.mochymusic.ui.component.AppSwitch
import com.mochyy.mochymusic.ui.component.DefaultDialog
import com.mochyy.mochymusic.utils.rememberPreference
import com.mochyy.mochymusic.viewmodels.ImportSyncViewModel
import com.mochyy.mochymusic.viewmodels.PendingImport
import com.music.innertube.utils.parseCookieString

const val ImportSyncRoute = "import_sync"
const val ImportProvidersRoute = "import_providers"
const val ImportTuneMyMusicRoute = "import_tunemymusic"
const val ImportProgressRoute = "import_progress"
const val YouTubeSyncRoute = "youtube_sync"
const val YouTubeLoginRoute = "youtube_login"

@Composable
fun rememberYouTubeSignedIn(): Boolean {
    val cookie by rememberPreference(InnerTubeCookieKey, "")
    return remember(cookie) { runCatching { "SAPISID" in parseCookieString(cookie) }.getOrDefault(false) }
}

/** same surface as a grouped settings card, for content that isn't a list of rows */
@Composable
fun ImportInfoCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = com.mochyy.mochymusic.ui.screens.search.SearchColors.Field),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp), content = content)
    }
}

@Composable
fun LinkInputDialog(
    source: LinkSource,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var link by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    DefaultDialog(
        onDismiss = onDismiss,
        horizontalAlignment = Alignment.Start,
        title = { Text("Nhập từ ${source.label}") },
        buttons = {
            TextButton(onClick = {
                clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { link = it }
            }) { Text("Dán") }
            TextButton(onClick = onDismiss) { Text("Hủy") }
            TextButton(
                enabled = link.isNotBlank(),
                onClick = { onSubmit(link.trim()) },
            ) { Text("Tiếp tục") }
        },
    ) {
        Text(
            text = "Dán liên kết tới danh sách phát hoặc album công khai. Chia sẻ từ ${source.label} bằng nút \"Sao chép liên kết\".",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            singleLine = true,
            placeholder = { Text(source.example, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (link.isNotBlank()) onSubmit(link.trim()) }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )
    }
}

/** shows what the pending import is doing and starts it on confirm, put one on every screen that loads imports */
@Composable
fun PendingImportHost(
    viewModel: ImportSyncViewModel,
    navController: NavController,
) {
    val pending by viewModel.pending.collectAsState()
    val context = LocalContext.current
    val signedIn = rememberYouTubeSignedIn()

    when (val state = pending) {
        PendingImport.Idle -> Unit
        is PendingImport.Loading -> DefaultDialog(
            onDismiss = viewModel::dismissPending,
            buttons = { TextButton(onClick = viewModel::dismissPending) { Text("Hủy") } },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(16.dp))
                Text(state.label, style = MaterialTheme.typography.bodyLarge)
            }
        }
        is PendingImport.Failed -> DefaultDialog(
            onDismiss = viewModel::dismissPending,
            horizontalAlignment = Alignment.Start,
            title = { Text("Không thể nhập") },
            buttons = { TextButton(onClick = viewModel::dismissPending) { Text("OK") } },
        ) {
            Text(state.message, style = MaterialTheme.typography.bodyMedium)
        }
        is PendingImport.Ready -> ImportReviewDialog(
            parsed = state.parsed,
            sourceLabel = state.sourceLabel,
            signedIn = signedIn,
            onDismiss = viewModel::dismissPending,
            onImport = { mirror ->
                if (!viewModel.startImport(state.parsed, mirror)) {
                    Toast.makeText(context, "Đang có một tiến trình nhập khác chạy", Toast.LENGTH_SHORT).show()
                    viewModel.dismissPending()
                }
                navController.navigate(ImportProgressRoute) { launchSingleTop = true }
            },
        )
    }
}

@Composable
private fun ImportReviewDialog(
    parsed: ParsedImport,
    sourceLabel: String,
    signedIn: Boolean,
    onDismiss: () -> Unit,
    onImport: (mirrorToYouTube: Boolean) -> Unit,
) {
    var mirror by rememberSaveable { mutableStateOf(signedIn) }
    val names = parsed.playlists.keys.toList()

    DefaultDialog(
        onDismiss = onDismiss,
        horizontalAlignment = Alignment.Start,
        title = {
            Text(
                text = names.singleOrNull()?.takeIf { parsed.likedSongs.isEmpty() } ?: "Nhập từ $sourceLabel",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        buttons = {
            TextButton(onClick = onDismiss) { Text("Hủy") }
            TextButton(onClick = { onImport(mirror && signedIn) }) { Text("Nhập") }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (parsed.likedSongs.isNotEmpty()) {
                Text("${parsed.likedSongs.size} bài hát đã thích", style = MaterialTheme.typography.bodyLarge)
            }
            if (names.isNotEmpty()) {
                val songCount = parsed.playlists.values.sumOf { it.size }
                Text(
                    "${names.size} danh sách phát · $songCount bài hát",
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (names.size > 1) {
                    Text(
                        text = names.take(6).joinToString(", ") + if (names.size > 6) " và ${names.size - 6} mục khác" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val needsSearch = parsed.likedSongs.any { it.videoId == null } || parsed.playlists.values.any { list -> list.any { it.videoId == null } }
            if (needsSearch) {
                Text(
                    "Mỗi bài hát sẽ được tìm trên YouTube Music. Thư viện lớn có thể mất vài phút; bạn có thể rời khỏi trang này trong khi ứng dụng xử lý.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            parsed.warnings.forEach { warning ->
                Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            if (signedIn) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Đồng thời tạo trên YouTube", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Danh sách phát và bài hát đã thích cũng sẽ được thêm vào tài khoản YouTube của bạn.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    AppSwitch(checked = mirror, onCheckedChange = { mirror = it })
                }
                if (!mirror && parsed.likedSongs.isNotEmpty()) {
                    Text(
                        "Đồng bộ YouTube giúp đồng nhất các bài hát đã thích ở cả hai nơi, nên chúng vẫn sẽ được thêm vào YouTube ở lần đồng bộ tiếp theo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
