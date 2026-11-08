package com.mochyy.mochymusic.ui.screens.settings.importsync

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.ui.component.SettingsGroup
import com.mochyy.mochymusic.ui.component.SettingsGroupStyle
import com.mochyy.mochymusic.ui.component.SettingsItem
import com.mochyy.mochymusic.ui.component.SubSettingsScaffold
import com.mochyy.mochymusic.viewmodels.ImportSyncViewModel

private const val TUNEMYMUSIC_URL = "https://www.tunemymusic.com/transfer"

@Composable
fun TuneMyMusicImportScreen(
    navController: NavController,
    viewModel: ImportSyncViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.loadFile(uri, sourceLabel = "TuneMyMusic")
    }

    SubSettingsScaffold(
        title = "TuneMyMusic CSV",
        onBack = { navController.navigateUp() },
    ) {
        ImportInfoCard {
            Text(
                "Hoạt động với mọi dịch vụ mà TuneMyMusic hỗ trợ, bao gồm cả danh sách phát riêng tư và bài hát đã thích.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Step(1, "Mở TuneMyMusic và chọn dịch vụ nguồn của bạn (Spotify, Apple Music, Tidal, Amazon Music…).")
                Step(2, "Đăng nhập và chọn các danh sách phát cùng bài hát đã thích cần chuyển.")
                Step(3, "Ở bước chọn đích đến, chọn \"Export to file\", sau đó chọn CSV.")
                Step(4, "Quay lại đây và chọn tệp .csv vừa tải về.")
            }
        }

        Spacer(Modifier.height(16.dp))

        SettingsGroup(
            style = SettingsGroupStyle.Grouped,
            items = listOf(
                SettingsItem(
                    title = { Text("Mở TuneMyMusic") },
                    descriptionText = "Mở tunemymusic.com trong trình duyệt",
                    icon = painterResource(R.drawable.web_link),
                    iconShape = CircleShape,
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TUNEMYMUSIC_URL)))
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, "Không tìm thấy trình duyệt", Toast.LENGTH_SHORT).show()
                        }
                    },
                ),
                SettingsItem(
                    title = { Text("Chọn tệp CSV") },
                    descriptionText = "Cũng hỗ trợ CSV từ Exportify và các công cụ khác",
                    icon = painterResource(R.drawable.playlist_add),
                    iconShape = CircleShape,
                    onClick = { csvPicker.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) },
                ),
            ),
        )
    }

    PendingImportHost(viewModel = viewModel, navController = navController)
}

@Composable
private fun Step(number: Int, text: String) {
    Row {
        Text("$number.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
