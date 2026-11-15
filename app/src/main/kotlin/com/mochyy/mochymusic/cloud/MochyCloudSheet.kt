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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.ui.screens.setup.onboarding.Onb
import com.mochyy.mochymusic.ui.screens.setup.onboarding.OnbButton
import com.mochyy.mochymusic.ui.screens.setup.onboarding.OnbButtonStyle
import com.mochyy.mochymusic.ui.screens.setup.onboarding.OnbCard
import com.mochyy.mochymusic.ui.screens.setup.onboarding.OnbPillInput
import com.mochyy.mochymusic.ui.screens.setup.onboarding.PageBadge
import com.mochyy.mochymusic.ui.theme.InterFontFamily
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
    val focusManager = LocalFocusManager.current
    val state by MochyCloudSyncManager.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var authTab by remember { mutableIntStateOf(0) } // 0 = Đăng nhập, 1 = Đăng ký
    var usernameInput by remember { mutableStateOf("") }
    var displayNameInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
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
        containerColor = Onb.Page,
        contentColor = Color.White,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .width(72.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.35f)),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
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
                        text = "Chỉ đồng bộ Yêu thích, Lịch sử nghe & Avatar nhẹ",
                        color = Onb.Subtitle,
                        fontFamily = InterFontFamily,
                        fontSize = 12.sp,
                    )
                }
            }

            if (!state.isConfigured) {
                OnbCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Text(
                        text = "Bản dựng này chưa gắn khóa Mochy Cloud hoặc chữ ký APK không hợp lệ.",
                        color = Color(0xFFFF8A80),
                        fontFamily = InterFontFamily,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            } else {
                AnimatedContent(targetState = state.signedIn, label = "mochyCloudAuth") { signedIn ->
                    if (!signedIn) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OnbCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
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
                                    .background(Onb.Card)
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
                                fontFamily = InterFontFamily,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
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
                            OnbCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
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
                                            fontFamily = InterFontFamily,
                                            fontSize = 12.sp,
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
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                OnbCard(modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp)) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Text(
                                            text = "${state.favoritesCount}",
                                            color = Color.White,
                                            fontFamily = InterFontFamily,
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            text = "Bài hát yêu thích",
                                            color = Onb.Muted,
                                            fontFamily = InterFontFamily,
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                                OnbCard(modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp)) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Text(
                                            text = "${state.historyCount}",
                                            color = Color.White,
                                            fontFamily = InterFontFamily,
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            text = "Lịch sử nghe Cloud",
                                            color = Onb.Muted,
                                            fontFamily = InterFontFamily,
                                            fontSize = 12.sp,
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
                                    color = Onb.Faint,
                                    fontFamily = InterFontFamily,
                                    fontSize = 11.sp,
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
                        }
                    }
                }
            }

            val notice = statusNotice ?: state.lastError
            if (!notice.isNullOrBlank()) {
                OnbCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Text(
                        text = notice,
                        color = Color.White,
                        fontFamily = InterFontFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}
