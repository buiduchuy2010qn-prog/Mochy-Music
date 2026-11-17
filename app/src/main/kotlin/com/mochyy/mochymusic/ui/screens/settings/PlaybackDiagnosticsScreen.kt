package com.mochyy.mochymusic.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.ui.component.SettingsGroup
import com.mochyy.mochymusic.ui.component.SettingsGroupStyle
import com.mochyy.mochymusic.ui.component.SettingsItem
import com.mochyy.mochymusic.ui.component.SubSettingsScaffold
import com.mochyy.mochymusic.utils.ConnectivityDiagnostics
import com.mochyy.mochymusic.utils.NetworkProbeReport
import com.mochyy.mochymusic.utils.PlaybackLogEntry
import com.mochyy.mochymusic.utils.PlaybackLogLevel
import com.mochyy.mochymusic.utils.PlaybackLogManager
import com.mochyy.mochymusic.utils.YTPlayerUtils
import kotlinx.coroutines.launch

/** newest entries first, capped so the scrolling column stays responsive */
private const val VISIBLE_ENTRIES = 200

@Composable
fun PlaybackDiagnosticsScreen(navController: NavController) {
    val context = LocalContext.current
    val logs by PlaybackLogManager.logs.collectAsStateWithLifecycle()

    val recent = remember(logs) { logs.asReversed().take(VISIBLE_ENTRIES) }

    val lastValidated = remember(logs) { logs.lastOrNull { it.message == "Stream validated" } }
    val hasPoToken = YTPlayerUtils.hasPoToken
    val scope = rememberCoroutineScope()
    var probingNetwork by remember { mutableStateOf(false) }
    var probeReport by remember { mutableStateOf<NetworkProbeReport?>(null) }

    SubSettingsScaffold(
        title = "Chẩn đoán phát nhạc",
        onBack = { navController.navigateUp() },
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (hasPoToken) "Đã có PoToken" else "Chưa có PoToken",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (hasPoToken) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )

                Spacer(Modifier.height(8.dp))

                DiagnosticsRow("Luồng phát gần nhất", lastValidated?.details ?: "—")
            }
        }

        SettingsGroup(
            style = SettingsGroupStyle.Grouped,
            items = listOf(
                SettingsItem(
                    title = { Text(if (probingNetwork) "Đang dò kết nối đa chặng..." else "Kiểm tra mạng đa chặng") },
                    descriptionText = "Đo độ trễ DNS, cổng Google 204 và máy chủ YouTube Music",
                    icon = painterResource(R.drawable.wifi_proxy),
                    iconShape = CircleShape,
                    onClick = {
                        if (!probingNetwork) {
                            scope.launch {
                                probingNetwork = true
                                probeReport = ConnectivityDiagnostics.runMultiHopProbe(context)
                                probingNetwork = false
                            }
                        }
                    },
                ),
                SettingsItem(
                    title = { Text("Sao chép nhật ký") },
                    descriptionText = "Sao chép toàn bộ nhật ký phát nhạc vào bộ nhớ tạm",
                    icon = painterResource(R.drawable.key),
                    iconShape = CircleShape,
                    onClick = {
                        copyLogs(context, logs)
                        Toast.makeText(context, "Đã sao chép nhật ký phát nhạc", Toast.LENGTH_SHORT).show()
                    },
                ),
                SettingsItem(
                    title = { Text("Xóa nhật ký") },
                    descriptionText = "Xóa toàn bộ nhật ký đã ghi lại",
                    icon = painterResource(R.drawable.delete),
                    iconShape = CircleShape,
                    onClick = { PlaybackLogManager.clearLogs() },
                ),
            ),
        )

        probeReport?.let { report ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (report.overallOk) "Kết nối khỏe mạnh" else "Phát hiện nghẽn mạng",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (report.overallOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(6.dp))
                    DiagnosticsRow("Mạng hệ thống", report.transportSummary)
                    DiagnosticsRow("Phân giải DNS", report.dnsSummary)
                    DiagnosticsRow("Cổng Google 204", report.google204Summary)
                    DiagnosticsRow("YouTube Music", report.ytMusicSummary)
                    DiagnosticsRow("Chẩn đoán", report.summaryVi)
                }
            }
        }

        if (recent.isEmpty()) {
            Text(
                text = "Chưa có dữ liệu. Hãy phát một bài hát rồi quay lại đây.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }

        recent.forEach { entry ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "${entry.timestamp}  ${entry.message}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = entry.level.tint(),
                )
                entry.details?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PlaybackLogLevel.tint(): Color = when (this) {
    PlaybackLogLevel.ERROR -> MaterialTheme.colorScheme.error
    PlaybackLogLevel.WARNING -> MaterialTheme.colorScheme.tertiary
    PlaybackLogLevel.INFO -> MaterialTheme.colorScheme.primary
    PlaybackLogLevel.BOT -> MaterialTheme.colorScheme.secondary
    PlaybackLogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun DiagnosticsRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun copyLogs(context: Context, logs: List<PlaybackLogEntry>) {
    val text = logs.joinToString("\n") { entry ->
        buildString {
            append(entry.timestamp)
            append(' ')
            append(entry.level.name)
            append(' ')
            append(entry.message)
            entry.details?.let {
                append(" | ")
                append(it)
            }
        }
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("mochy playback log", text))
}
