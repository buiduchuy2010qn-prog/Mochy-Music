package com.mochyy.mochymusic.cloud

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.R
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MochyCloudSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by MochyCloudSyncManager.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var authTab by remember { mutableIntStateOf(0) } // 0 = Đăng nhập, 1 = Đăng ký
    var usernameInput by remember { mutableStateOf("") }
    var displayNameInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var statusNotice by remember { mutableStateOf<String?>(null) }

    var editingName by remember(state.displayName) { mutableStateOf(state.displayName) }
    var showNameEditor by remember { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            statusNotice = null
            val res = MochyCloudSyncManager.updateProfileAndAvatar(context, null, uri)
            busy = false
            statusNotice = res.fold(
                onSuccess = { "Đã nén WebP siêu nhẹ (< 24 KB) và lưu ảnh đại diện lên Mochy Cloud." },
                onFailure = { it.message ?: "Không thể cập nhật ảnh đại diện." },
            )
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.cloud),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Tài khoản Mochy Cloud",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Chỉ đồng bộ Yêu thích, Lịch sử nghe & Avatar nhẹ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!state.isConfigured) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Bản dựng này chưa gắn khóa Mochy Cloud hoặc chữ ký APK không hợp lệ.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            } else {
                AnimatedContent(targetState = state.signedIn, label = "mochyCloudAuth") { signedIn ->
                    if (!signedIn) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.lock),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = "Tạo tài khoản nhanh bằng tên đăng nhập và mật khẩu, không cần email OTP. Phiên được mã hóa bằng Android Keystore.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                listOf("Đăng nhập", "Đăng ký mới").forEachIndexed { idx, label ->
                                    SegmentedButton(
                                        selected = authTab == idx,
                                        onClick = {
                                            authTab = idx
                                            statusNotice = null
                                        },
                                        shape = SegmentedButtonDefaults.itemShape(idx, 2),
                                    ) {
                                        Text(label)
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = usernameInput,
                                onValueChange = { usernameInput = it.lowercase().filter { ch -> ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '_' }.take(24) },
                                label = { Text("Tên đăng nhập (@username)") },
                                supportingText = { Text("3–24 ký tự thường, số, dấu . hoặc _") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                                modifier = Modifier.fillMaxWidth(),
                            )

                            if (authTab == 1) {
                                OutlinedTextField(
                                    value = displayNameInput,
                                    onValueChange = { displayNameInput = it.take(40) },
                                    label = { Text("Tên hiển thị") },
                                    placeholder = { Text("Mặc định dùng tên đăng nhập") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            OutlinedTextField(
                                value = passwordInput,
                                onValueChange = { passwordInput = it },
                                label = { Text("Mật khẩu") },
                                supportingText = { Text("Tối thiểu 6 ký tự") },
                                singleLine = true,
                                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { showPassword = !showPassword }) {
                                        Text(
                                            text = if (showPassword) "Ẩn" else "Hiện",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                                modifier = Modifier.fillMaxWidth(),
                            )

                            Button(
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        statusNotice = null
                                        val res = if (authTab == 1) {
                                            MochyCloudSyncManager.signUp(context, usernameInput, passwordInput, displayNameInput)
                                        } else {
                                            MochyCloudSyncManager.signIn(context, usernameInput, passwordInput)
                                        }
                                        busy = false
                                        res.onSuccess {
                                            passwordInput = ""
                                            statusNotice = "Đã kết nối và đồng bộ Mochy Cloud."
                                        }.onFailure {
                                            statusNotice = it.message ?: "Không thể kết nối Mochy Cloud."
                                        }
                                    }
                                },
                                enabled = !busy && usernameInput.length >= 3 && passwordInput.length >= 6,
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                            ) {
                                if (busy) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(if (authTab == 1) "Đăng ký & Đồng bộ ngay" else "Đăng nhập & Đồng bộ")
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(58.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primaryContainer)
                                            .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape)
                                            .clickable(enabled = !busy) {
                                                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        val avatarFile = state.avatarLocalPath?.let(::File)?.takeIf { it.exists() }
                                        if (avatarFile != null) {
                                            AsyncImage(
                                                model = avatarFile,
                                                contentDescription = "Avatar Mochy Cloud",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.size(58.dp),
                                            )
                                        } else {
                                            Icon(
                                                painter = painterResource(R.drawable.person),
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(28.dp),
                                            )
                                        }
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = state.displayName.ifBlank { state.username },
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "@${state.username} · Avatar WebP ≤ 24 KB",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                                modifier = Modifier.clickable(enabled = !busy) {
                                                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                                },
                                            ) {
                                                Text(
                                                    text = "Đổi avatar nhẹ",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                )
                                            }
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                modifier = Modifier.clickable(enabled = !busy) {
                                                    showNameEditor = !showNameEditor
                                                },
                                            ) {
                                                Text(
                                                    text = "Đổi tên",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (showNameEditor) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedTextField(
                                        value = editingName,
                                        onValueChange = { editingName = it.take(40) },
                                        label = { Text("Tên hiển thị mới") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Button(
                                        enabled = !busy && editingName.isNotBlank(),
                                        onClick = {
                                            scope.launch {
                                                busy = true
                                                val res = MochyCloudSyncManager.updateProfileAndAvatar(context, editingName, null)
                                                busy = false
                                                showNameEditor = false
                                                statusNotice = res.fold(
                                                    onSuccess = { "Đã cập nhật tên hiển thị." },
                                                    onFailure = { it.message ?: "Không thể lưu tên." },
                                                )
                                            }
                                        },
                                    ) {
                                        Text("Lưu")
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = "${state.favoritesCount}",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            text = "Bài hát yêu thích",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = "${state.historyCount}",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.secondary,
                                        )
                                        Text(
                                            text = "Lịch sử nghe Cloud",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }

                            if (state.lastSyncEpochMs > 0L) {
                                val formatted = remember(state.lastSyncEpochMs) {
                                    SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault())
                                        .format(Date(state.lastSyncEpochMs))
                                }
                                Text(
                                    text = "Đồng bộ gần nhất: $formatted · Giới hạn 500 lịch sử & 2.000 yêu thích",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                FilledTonalButton(
                                    onClick = { MochyCloudSyncManager.syncNow(context) },
                                    enabled = !busy && !state.syncing,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    if (state.syncing) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Đang đồng bộ...")
                                    } else {
                                        Icon(
                                            painter = painterResource(R.drawable.sync),
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text("Đồng bộ ngay")
                                    }
                                }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            MochyCloudSyncManager.signOut(context)
                                            busy = false
                                            statusNotice = "Đã đăng xuất tài khoản Mochy Cloud."
                                        }
                                    },
                                    enabled = !busy,
                                ) {
                                    Text("Đăng xuất")
                                }
                            }
                        }
                    }
                }
            }

            val notice = statusNotice ?: state.lastError
            if (!notice.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}
