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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.ui.player.menu.MenuRowSurface
import com.mochyy.mochymusic.ui.player.menu.MenuSheetSurface
import com.mochyy.mochymusic.ui.screens.setup.onboarding.Onb
import com.mochyy.mochymusic.ui.screens.setup.onboarding.OnbButton
import com.mochyy.mochymusic.ui.screens.setup.onboarding.OnbButtonStyle
import com.mochyy.mochymusic.ui.screens.setup.onboarding.OnbPillInput
import com.mochyy.mochymusic.ui.screens.setup.onboarding.PageBadge
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SheetErrorRed = Color(0xFFE0736B)

@Composable
fun MochyCloudSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val state by MochyCloudSyncManager.state.collectAsState()

    var authTab by remember { mutableIntStateOf(0) } // 0 = Đăng nhập, 1 = Đăng ký
    var usernameInput by remember { mutableStateOf("") }
    var displayNameInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var statusNotice by remember { mutableStateOf<String?>(null) }

    var editingName by remember(state.displayName) { mutableStateOf(state.displayName) }
    var showNameEditor by remember { mutableStateOf(false) }
    var showPasswordEditor by remember { mutableStateOf(false) }
    var newPasswordInput by remember { mutableStateOf("") }
    var confirmPasswordInput by remember { mutableStateOf("") }
    var confirmWipeCloud by remember { mutableStateOf(false) }

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

    MenuSheetSurface(
        onDismiss = onDismiss,
        wrapHeight = true,
        fullDetent = 0.92f,
        dismissEnabled = !busy,
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PageBadge(painterResource(R.drawable.cloud), size = 44.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Tài khoản Mochy Cloud",
                        color = Color.White,
                        fontFamily = InterFontFamily,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.035).em,
                    )
                    Text(
                        text = "Đồng bộ Yêu thích, Playlist, Lịch sử nghe, Thẻ ID 3D & Avatar",
                        color = Onb.Subtitle,
                        fontFamily = InterFontFamily,
                        fontSize = 12.sp,
                    )
                }
            }

            if (!state.isConfigured) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MenuRowSurface)
                        .padding(14.dp),
                ) {
                    Text(
                        text = "Bản dựng này chưa gắn khóa Mochy Cloud hoặc chữ ký APK không hợp lệ.",
                        color = SheetErrorRed,
                        fontFamily = InterFontFamily,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                AnimatedContent(targetState = state.signedIn, label = "mochyCloudAuth") { signedIn ->
                    if (!signedIn) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(MenuRowSurface)
                                    .padding(14.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.lock),
                                        contentDescription = null,
                                        tint = Onb.Badge,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = "Tạo tài khoản nhanh bằng tên đăng nhập và mật khẩu, không cần email OTP. Phiên được mã hóa bằng Android Keystore.",
                                        color = Onb.Subtitle,
                                        fontFamily = InterFontFamily,
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp,
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(CircleShape)
                                    .background(MenuRowSurface)
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                listOf("Đăng nhập", "Đăng ký mới").forEachIndexed { idx, label ->
                                    val selected = authTab == idx
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(CircleShape)
                                            .background(if (selected) Onb.ButtonPrimary else Color.Transparent)
                                            .clickable {
                                                authTab = idx
                                                statusNotice = null
                                            }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = label,
                                            color = if (selected) Color.White else Onb.Muted,
                                            fontFamily = InterFontFamily,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }

                            OnbPillInput(
                                value = usernameInput,
                                onValueChange = {
                                    usernameInput = it.lowercase().filter { ch -> ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '_' }.take(24)
                                    statusNotice = null
                                },
                                placeholder = "ten_nguoi_dung",
                                prefix = "@",
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Ascii,
                                    imeAction = ImeAction.Next,
                                ),
                                keyboardActions = KeyboardActions(
                                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                                ),
                            )
                            Text(
                                text = "3–24 ký tự thường, số, dấu . hoặc _",
                                color = Onb.Faint,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.5.sp,
                                modifier = Modifier.padding(start = 10.dp),
                            )

                            if (authTab == 1) {
                                OnbPillInput(
                                    value = displayNameInput,
                                    onValueChange = { displayNameInput = it.take(40) },
                                    placeholder = "tên hiển thị (mặc định dùng username)",
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Text,
                                        imeAction = ImeAction.Next,
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onNext = { focusManager.moveFocus(FocusDirection.Down) },
                                    ),
                                )
                            }

                            OnbPillInput(
                                value = passwordInput,
                                onValueChange = {
                                    passwordInput = it
                                    statusNotice = null
                                },
                                placeholder = "mật khẩu (tối thiểu 6 ký tự)",
                                isPassword = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done,
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = { focusManager.clearFocus() },
                                ),
                            )

                            OnbButton(
                                text = when {
                                    busy && authTab == 1 -> "Đang đăng ký & đồng bộ…"
                                    busy -> "Đang đăng nhập & đồng bộ…"
                                    authTab == 1 -> "Đăng ký & Đồng bộ ngay"
                                    else -> "Đăng nhập & Đồng bộ"
                                },
                                enabled = !busy && usernameInput.length >= 3 && passwordInput.length >= 6,
                                onClick = {
                                    focusManager.clearFocus()
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
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(MenuRowSurface)
                                    .padding(16.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(58.dp)
                                            .clip(CircleShape)
                                            .background(Onb.ButtonSecondary)
                                            .border(1.5.dp, Color.White.copy(alpha = 0.2f), CircleShape)
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
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        } else {
                                            Icon(
                                                painter = painterResource(R.drawable.person),
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(28.dp),
                                            )
                                        }
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = state.displayName.ifBlank { state.username },
                                            color = Color.White,
                                            fontFamily = InterFontFamily,
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "@${state.username} · Avatar WebP ≤ 24 KB",
                                            color = Onb.Subtitle,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(CircleShape)
                                                    .background(Onb.ButtonPrimary)
                                                    .clickable(enabled = !busy) {
                                                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                                    }
                                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                            ) {
                                                Text(
                                                    text = "Đổi avatar nhẹ",
                                                    color = Color.White,
                                                    fontFamily = InterFontFamily,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .clip(CircleShape)
                                                    .background(Onb.ButtonSecondary)
                                                    .clickable(enabled = !busy) {
                                                        showNameEditor = !showNameEditor
                                                    }
                                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                            ) {
                                                Text(
                                                    text = "Đổi tên",
                                                    color = Onb.Subtitle,
                                                    fontFamily = InterFontFamily,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
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
                                    OnbPillInput(
                                        value = editingName,
                                        onValueChange = { editingName = it.take(40) },
                                        placeholder = "tên hiển thị mới",
                                        modifier = Modifier.weight(1f),
                                    )
                                    OnbButton(
                                        text = "Lưu",
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
                                        modifier = Modifier.width(90.dp),
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CloudStatTile(state.favoritesCount.toString(), "Yêu thích", Modifier.weight(1f))
                                CloudStatTile(state.playlistsCount.toString(), "Playlist", Modifier.weight(1f))
                                CloudStatTile(state.historyCount.toString(), "Lịch sử nghe", Modifier.weight(1f))
                            }

                            if (state.lastSyncEpochMs > 0L) {
                                val formatted = remember(state.lastSyncEpochMs) {
                                    SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault())
                                        .format(Date(state.lastSyncEpochMs))
                                }
                                Text(
                                    text = "Đồng bộ gần nhất: $formatted · Tối đa 25 Playlist, 500 lịch sử & 2.000 yêu thích",
                                    color = Onb.Faint,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.5.sp,
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                OnbButton(
                                    text = if (state.syncing) "Đang đồng bộ…" else "Đồng bộ ngay",
                                    enabled = !busy && !state.syncing,
                                    onClick = { MochyCloudSyncManager.syncNow(context) },
                                    modifier = Modifier.weight(1f),
                                )
                                OnbButton(
                                    text = "Đăng xuất",
                                    style = OnbButtonStyle.Secondary,
                                    enabled = !busy,
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            MochyCloudSyncManager.signOut(context)
                                            busy = false
                                            statusNotice = "Đã đăng xuất tài khoản Mochy Cloud."
                                        }
                                    },
                                    modifier = Modifier.width(120.dp),
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(CircleShape)
                                        .background(MenuRowSurface)
                                        .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                                        .clickable(enabled = !busy) {
                                            showPasswordEditor = !showPasswordEditor
                                            confirmWipeCloud = false
                                            statusNotice = null
                                        }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = if (showPasswordEditor) "Đóng đổi mật khẩu" else "Đổi mật khẩu",
                                        color = Color.White,
                                        fontFamily = InterFontFamily,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(CircleShape)
                                        .background(MenuRowSurface)
                                        .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                                        .clickable(enabled = !busy) {
                                            confirmWipeCloud = !confirmWipeCloud
                                            showPasswordEditor = false
                                            statusNotice = null
                                        }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "Xóa dữ liệu Cloud",
                                        color = SheetErrorRed,
                                        fontFamily = InterFontFamily,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }

                            if (showPasswordEditor) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(MenuRowSurface)
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        text = "Đổi mật khẩu tài khoản",
                                        color = Color.White,
                                        fontFamily = InterFontFamily,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    OnbPillInput(
                                        value = newPasswordInput,
                                        onValueChange = { newPasswordInput = it },
                                        placeholder = "mật khẩu mới (tối thiểu 6 ký tự)",
                                        isPassword = true,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Password,
                                            imeAction = ImeAction.Next,
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onNext = { focusManager.moveFocus(FocusDirection.Down) },
                                        ),
                                    )
                                    OnbPillInput(
                                        value = confirmPasswordInput,
                                        onValueChange = { confirmPasswordInput = it },
                                        placeholder = "nhập lại mật khẩu mới",
                                        isPassword = true,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Password,
                                            imeAction = ImeAction.Done,
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onDone = { focusManager.clearFocus() },
                                        ),
                                    )
                                    OnbButton(
                                        text = if (busy) "Đang cập nhật…" else "Cập nhật mật khẩu",
                                        enabled = !busy && newPasswordInput.length >= 6 && newPasswordInput == confirmPasswordInput,
                                        onClick = {
                                            focusManager.clearFocus()
                                            scope.launch {
                                                busy = true
                                                val res = MochyCloudSyncManager.changePassword(context, newPasswordInput)
                                                busy = false
                                                res.onSuccess {
                                                    newPasswordInput = ""
                                                    confirmPasswordInput = ""
                                                    showPasswordEditor = false
                                                    statusNotice = "Đã đổi mật khẩu Mochy Cloud thành công."
                                                }.onFailure {
                                                    statusNotice = it.message ?: "Không thể đổi mật khẩu."
                                                }
                                            }
                                        },
                                    )
                                    if (confirmPasswordInput.isNotEmpty() && newPasswordInput != confirmPasswordInput) {
                                        Text(
                                            text = "Mật khẩu nhập lại chưa khớp.",
                                            color = SheetErrorRed,
                                            fontFamily = InterFontFamily,
                                            fontSize = 11.sp,
                                        )
                                    }
                                }
                            }

                            if (confirmWipeCloud) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(MenuRowSurface)
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        text = "Xác nhận xóa dữ liệu đám mây?",
                                        color = SheetErrorRed,
                                        fontFamily = InterFontFamily,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = "Toàn bộ Yêu thích, Playlist cá nhân, Lịch sử nghe và Avatar lưu trên Cloud sẽ bị xóa. Nhạc trên thiết bị này vẫn được giữ nguyên.",
                                        color = Onb.Subtitle,
                                        fontFamily = InterFontFamily,
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp,
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        OnbButton(
                                            text = "Hủy",
                                            style = OnbButtonStyle.Secondary,
                                            enabled = !busy,
                                            onClick = { confirmWipeCloud = false },
                                            modifier = Modifier.weight(1f),
                                        )
                                        OnbButton(
                                            text = if (busy) "Đang xóa…" else "Xác nhận xóa",
                                            enabled = !busy,
                                            onClick = {
                                                scope.launch {
                                                    busy = true
                                                    val res = MochyCloudSyncManager.clearAllCloudData(context)
                                                    busy = false
                                                    confirmWipeCloud = false
                                                    statusNotice = res.fold(
                                                        onSuccess = { "Đã xóa sạch dữ liệu trên Mochy Cloud." },
                                                        onFailure = { it.message ?: "Không thể xóa dữ liệu Cloud." },
                                                    )
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val notice = statusNotice ?: state.lastError
            if (!notice.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MenuRowSurface)
                        .padding(12.dp),
                ) {
                    Text(
                        text = notice,
                        color = Color.White,
                        fontFamily = InterFontFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun CloudStatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MenuRowSurface)
            .padding(12.dp),
    ) {
        Text(
            text = value,
            color = Color.White,
            fontFamily = InterFontFamily,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            color = Onb.Muted,
            fontFamily = InterFontFamily,
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}
