package com.mochyy.mochymusic.cloud

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.ui.component.LocalZoomOutOverlayState
import com.mochyy.mochymusic.ui.component.PopupSheetHandle
import com.mochyy.mochymusic.ui.component.PopupSheetState
import com.mochyy.mochymusic.ui.component.RemoteFocus
import com.mochyy.mochymusic.ui.component.pressBounce
import com.mochyy.mochymusic.ui.player.menu.MenuRowSurface
import com.mochyy.mochymusic.ui.screens.setup.onboarding.Onb
import com.mochyy.mochymusic.ui.screens.setup.onboarding.OnbButton
import com.mochyy.mochymusic.ui.screens.setup.onboarding.OnbButtonStyle
import com.mochyy.mochymusic.ui.screens.setup.onboarding.OnbPillInput
import com.mochyy.mochymusic.ui.screens.setup.onboarding.PageBadge
import com.mochyy.mochymusic.ui.screens.update.SheetPrimaryButton
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SheetErrorRed = Color(0xFFE0736B)
private val BackupOpenMimeTypes = arrayOf(
    "application/gzip",
    "application/x-gzip",
    "application/octet-stream",
    "*/*",
)

@Stable
private class CloudSheetUiController {
    var authTab by mutableIntStateOf(0) // 0 = Đăng nhập, 1 = Đăng ký
    var usernameInput by mutableStateOf("")
    var displayNameInput by mutableStateOf("")
    var passwordInput by mutableStateOf("")
    var busy by mutableStateOf(false)
    var statusNotice by mutableStateOf<String?>(null)
}

/** Mở bảng tài khoản Mochy Cloud qua [PopupSheetState] chuẩn Mochy 8.4.2. */
fun PopupSheetState.showMochyCloudSheet(
    onClosed: (() -> Unit)? = null,
): PopupSheetHandle {
    val controller = CloudSheetUiController()
    var handle: PopupSheetHandle? = null
    val created = showWithHandle(
        onClosed = onClosed,
        buttonBar = {
            MochyCloudButtonBar(
                controller = controller,
                onDismiss = { handle?.dismiss() },
            )
        },
    ) {
        MochyCloudSheetContent(
            controller = controller,
            onLockChanged = { locked -> handle?.setLocked(locked) },
        )
    }
    handle = created
    return created
}

/** Cầu nối tương thích cho các màn hình vẫn gọi composable inline. */
@Composable
fun MochyCloudSheet(onDismiss: () -> Unit) {
    val zoomOutOverlay = LocalZoomOutOverlayState.current
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(zoomOutOverlay) {
        val handle = zoomOutOverlay.showMochyCloudSheet(onClosed = { currentOnDismiss() })
        onDispose { handle.dismiss() }
    }
}

@Composable
private fun MochyCloudButtonBar(
    controller: CloudSheetUiController,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val state by MochyCloudSyncManager.state.collectAsState()

    when {
        !state.isConfigured -> {
            SheetPrimaryButton(
                text = stringResource(R.string.done),
                onClick = onDismiss,
            )
        }
        state.signedIn -> {
            val enabled = !controller.busy && !state.syncing
            Box(
                modifier = Modifier.graphicsLayer { alpha = if (enabled) 1f else 0.55f },
            ) {
                SheetPrimaryButton(
                    text = if (state.syncing) "Đang đồng bộ…" else "Đồng bộ ngay",
                    onClick = {
                        if (enabled) {
                            controller.statusNotice = null
                            MochyCloudSyncManager.syncNow(context, fromUser = true)
                        }
                    },
                )
            }
        }
        else -> {
            val formReady = controller.usernameInput.length >= 3 && controller.passwordInput.length >= 6
            val enabled = !controller.busy
            val buttonLabel = when {
                controller.busy && controller.authTab == 1 -> "Đang đăng ký & đồng bộ…"
                controller.busy -> "Đang đăng nhập & đồng bộ…"
                controller.authTab == 1 -> "Đăng ký & Đồng bộ"
                else -> "Đăng nhập & Đồng bộ"
            }
            Box(
                modifier = Modifier.graphicsLayer { alpha = if (enabled && formReady) 1f else 0.58f },
            ) {
                SheetPrimaryButton(
                    text = buttonLabel,
                    onClick = {
                        if (!enabled) return@SheetPrimaryButton
                        if (!formReady) {
                            controller.statusNotice = "Nhập tên đăng nhập từ 3 ký tự và mật khẩu từ 6 ký tự."
                            return@SheetPrimaryButton
                        }
                        focusManager.clearFocus()
                        scope.launch {
                            controller.busy = true
                            controller.statusNotice = null
                            val res = if (controller.authTab == 1) {
                                MochyCloudSyncManager.signUp(
                                    context = context,
                                    username = controller.usernameInput,
                                    password = controller.passwordInput,
                                    displayName = controller.displayNameInput,
                                )
                            } else {
                                MochyCloudSyncManager.signIn(
                                    context = context,
                                    username = controller.usernameInput,
                                    password = controller.passwordInput,
                                )
                            }
                            controller.busy = false
                            res.onSuccess {
                                controller.passwordInput = ""
                                controller.statusNotice = "Đã kết nối và đồng bộ Mochy Cloud."
                            }.onFailure { err ->
                                controller.statusNotice = err.message ?: "Không thể kết nối Mochy Cloud."
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun MochyCloudSheetContent(
    controller: CloudSheetUiController,
    onLockChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val state by MochyCloudSyncManager.state.collectAsState()

    var editingName by remember(state.displayName) { mutableStateOf(state.displayName) }
    var showNameEditor by remember { mutableStateOf(false) }
    var showPasswordEditor by remember { mutableStateOf(false) }
    var newPasswordInput by remember { mutableStateOf("") }
    var confirmPasswordInput by remember { mutableStateOf("") }
    var confirmWipeCloud by remember { mutableStateOf(false) }

    LaunchedEffect(controller.busy) {
        onLockChanged(controller.busy)
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            controller.busy = true
            controller.statusNotice = null
            val res = MochyCloudSyncManager.updateProfileAndAvatar(context, null, uri)
            controller.busy = false
            controller.statusNotice = res.fold(
                onSuccess = { "Đã cập nhật ảnh đại diện Mochy Cloud." },
                onFailure = { it.message ?: "Không thể cập nhật ảnh đại diện." },
            )
        }
    }

    val backupExportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            controller.busy = true
            controller.statusNotice = null
            val res = MochyOfflineBackupCodec.exportToUri(context, uri)
            controller.busy = false
            controller.statusNotice = res.fold(
                onSuccess = { s ->
                    "Đã xuất sao lưu: ${s.favoritesCount} yêu thích, ${s.playlistsCount} playlist, " +
                        "${s.albumsCount} album, ${s.artistsCount} nghệ sĩ, ${s.historyCount} lịch sử."
                },
                onFailure = { it.message ?: "Không thể xuất tệp sao lưu." },
            )
        }
    }

    val backupImportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            controller.busy = true
            controller.statusNotice = null
            val res = MochyOfflineBackupCodec.importFromUri(context, uri)
            controller.busy = false
            controller.statusNotice = res.fold(
                onSuccess = { s ->
                    val eqTag = if (s.eqRestored) " & EQ" else ""
                    "Đã khôi phục: ${s.favoritesCount} yêu thích, ${s.playlistsCount} playlist, " +
                        "${s.albumsCount} album, ${s.artistsCount} nghệ sĩ, ${s.historyCount} lịch sử$eqTag."
                },
                onFailure = { it.message ?: "Không thể khôi phục tệp sao lưu." },
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(2.dp))

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
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.035).em,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Đồng bộ thư viện nhạc cá nhân trên đám mây",
                    color = Onb.Subtitle,
                    fontFamily = InterFontFamily,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
        }

        if (!state.isConfigured) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MenuRowSurface)
                    .padding(16.dp),
            ) {
                Text(
                    text = "Bản dựng này chưa gắn khóa Mochy Cloud hoặc chữ ký ứng dụng không hợp lệ.",
                    color = SheetErrorRed,
                    fontFamily = InterFontFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        } else {
            AnimatedContent(
                targetState = state.signedIn,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "mochyCloudAuth",
            ) { signedIn ->
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
                                    text = "Đăng ký nhanh bằng tên đăng nhập và mật khẩu, không cần email OTP.",
                                    color = Onb.Subtitle,
                                    fontFamily = InterFontFamily,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
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
                                val selected = controller.authTab == idx
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(CircleShape)
                                        .background(if (selected) Onb.ButtonPrimary else Color.Transparent)
                                        .clickable(enabled = !controller.busy) {
                                            controller.authTab = idx
                                            controller.statusNotice = null
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
                            value = controller.usernameInput,
                            onValueChange = {
                                controller.usernameInput = it.lowercase()
                                    .filter { ch -> ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '_' }
                                    .take(24)
                                controller.statusNotice = null
                            },
                            placeholder = "tên đăng nhập",
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
                            text = "Dùng 3–24 ký tự chữ thường, số, dấu chấm hoặc gạch dưới",
                            color = Onb.Muted,
                            fontFamily = InterFontFamily,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 10.dp),
                        )

                        if (controller.authTab == 1) {
                            OnbPillInput(
                                value = controller.displayNameInput,
                                onValueChange = { controller.displayNameInput = it.take(40) },
                                placeholder = "tên hiển thị (mặc định dùng tên đăng nhập)",
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
                            value = controller.passwordInput,
                            onValueChange = {
                                controller.passwordInput = it
                                controller.statusNotice = null
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

                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Sao lưu ngoại tuyến",
                            color = Color.White,
                            fontFamily = InterFontFamily,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.3).sp,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CloudActionRow(
                                icon = R.drawable.backup,
                                label = "Xuất tệp sao lưu (.mochy.gz)",
                                enabled = !controller.busy,
                                onClick = {
                                    backupExportPicker.launch(MochyOfflineBackupCodec.suggestedFileName())
                                },
                            )
                            CloudActionRow(
                                icon = R.drawable.restore,
                                label = "Khôi phục từ tệp sao lưu",
                                enabled = !controller.busy,
                                onClick = {
                                    backupImportPicker.launch(BackupOpenMimeTypes)
                                },
                            )
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Thẻ hồ sơ
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(22.dp))
                                .background(MenuRowSurface)
                                .padding(16.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(Onb.ButtonSecondary)
                                        .border(1.5.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                                        .clickable(enabled = !controller.busy) {
                                            photoPicker.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                            )
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val avatarFile = state.avatarLocalPath?.let(::File)?.takeIf { it.exists() }
                                    if (avatarFile != null) {
                                        AsyncImage(
                                            model = avatarFile,
                                            contentDescription = "Ảnh đại diện Mochy Cloud",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    } else {
                                        Icon(
                                            painter = painterResource(R.drawable.person),
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(26.dp),
                                        )
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = state.displayName.ifBlank { state.username },
                                        color = Color.White,
                                        fontFamily = InterFontFamily,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = "@${state.username}",
                                        color = Onb.Subtitle,
                                        fontFamily = InterFontFamily,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        CloudProfileChip(
                                            label = "Đổi ảnh",
                                            highlighted = true,
                                            enabled = !controller.busy,
                                            onClick = {
                                                photoPicker.launch(
                                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                                )
                                            },
                                        )
                                        CloudProfileChip(
                                            label = "Đổi tên",
                                            highlighted = showNameEditor,
                                            enabled = !controller.busy,
                                            onClick = { showNameEditor = !showNameEditor },
                                        )
                                    }
                                }
                            }
                        }

                        AnimatedVisibility(
                            visible = showNameEditor,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
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
                                    enabled = !controller.busy && editingName.isNotBlank(),
                                    onClick = {
                                        scope.launch {
                                            controller.busy = true
                                            val res = MochyCloudSyncManager.updateProfileAndAvatar(context, editingName, null)
                                            controller.busy = false
                                            showNameEditor = false
                                            controller.statusNotice = res.fold(
                                                onSuccess = { "Đã cập nhật tên hiển thị." },
                                                onFailure = { it.message ?: "Không thể lưu tên." },
                                            )
                                        }
                                    },
                                    modifier = Modifier.width(88.dp),
                                )
                            }
                        }

                        // Lưới thống kê 5 mục
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CloudStatTile(state.favoritesCount.toString(), "Yêu thích", Modifier.weight(1f))
                                CloudStatTile(state.playlistsCount.toString(), "Playlist", Modifier.weight(1f))
                                CloudStatTile(state.albumsCount.toString(), "Album", Modifier.weight(1f))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CloudStatTile(state.artistsCount.toString(), "Nghệ sĩ", Modifier.weight(1f))
                                CloudStatTile(state.historyCount.toString(), "Lịch sử", Modifier.weight(1f))
                            }
                        }

                        if (state.lastSyncEpochMs > 0L) {
                            val formatted = remember(state.lastSyncEpochMs) {
                                SimpleDateFormat("HH:mm · dd/MM", Locale.getDefault())
                                    .format(Date(state.lastSyncEpochMs))
                            }
                            Text(
                                text = "Đồng bộ lúc $formatted",
                                color = Onb.Muted,
                                fontFamily = InterFontFamily,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }

                        // Danh sách tác vụ chuẩn ActionRow
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CloudActionRow(
                                icon = R.drawable.backup,
                                label = "Xuất tệp sao lưu (.mochy.gz)",
                                enabled = !controller.busy,
                                onClick = {
                                    backupExportPicker.launch(MochyOfflineBackupCodec.suggestedFileName())
                                },
                            )
                            CloudActionRow(
                                icon = R.drawable.restore,
                                label = "Khôi phục từ tệp sao lưu",
                                enabled = !controller.busy,
                                onClick = {
                                    backupImportPicker.launch(BackupOpenMimeTypes)
                                },
                            )
                            CloudActionRow(
                                icon = R.drawable.lock,
                                label = "Đổi mật khẩu",
                                enabled = !controller.busy,
                                onClick = {
                                    showPasswordEditor = !showPasswordEditor
                                    confirmWipeCloud = false
                                    controller.statusNotice = null
                                },
                            )

                            AnimatedVisibility(
                                visible = showPasswordEditor,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut(),
                            ) {
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
                                        fontSize = 15.sp,
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
                                    if (confirmPasswordInput.isNotEmpty() && newPasswordInput != confirmPasswordInput) {
                                        Text(
                                            text = "Mật khẩu nhập lại chưa khớp.",
                                            color = SheetErrorRed,
                                            fontFamily = InterFontFamily,
                                            fontSize = 12.sp,
                                        )
                                    }
                                    OnbButton(
                                        text = if (controller.busy) "Đang cập nhật…" else "Cập nhật mật khẩu",
                                        enabled = !controller.busy &&
                                            newPasswordInput.length >= 6 &&
                                            newPasswordInput == confirmPasswordInput,
                                        onClick = {
                                            focusManager.clearFocus()
                                            scope.launch {
                                                controller.busy = true
                                                val res = MochyCloudSyncManager.changePassword(context, newPasswordInput)
                                                controller.busy = false
                                                res.onSuccess {
                                                    newPasswordInput = ""
                                                    confirmPasswordInput = ""
                                                    showPasswordEditor = false
                                                    controller.statusNotice = "Đã đổi mật khẩu Mochy Cloud thành công."
                                                }.onFailure {
                                                    controller.statusNotice = it.message ?: "Không thể đổi mật khẩu."
                                                }
                                            }
                                        },
                                    )
                                }
                            }

                            CloudActionRow(
                                icon = R.drawable.logout,
                                label = "Đăng xuất tài khoản",
                                enabled = !controller.busy,
                                onClick = {
                                    scope.launch {
                                        controller.busy = true
                                        MochyCloudSyncManager.signOut(context)
                                        controller.busy = false
                                        showPasswordEditor = false
                                        confirmWipeCloud = false
                                        controller.statusNotice = "Đã đăng xuất tài khoản Mochy Cloud."
                                    }
                                },
                            )
                            CloudActionRow(
                                icon = R.drawable.delete,
                                label = "Xóa dữ liệu trên đám mây",
                                danger = true,
                                enabled = !controller.busy,
                                onClick = {
                                    confirmWipeCloud = !confirmWipeCloud
                                    showPasswordEditor = false
                                    controller.statusNotice = null
                                },
                            )

                            AnimatedVisibility(
                                visible = confirmWipeCloud,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut(),
                            ) {
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
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = "Toàn bộ thư viện đã đồng bộ và ảnh đại diện trên đám mây sẽ bị xóa. Nhạc trên thiết bị này vẫn được giữ nguyên.",
                                        color = Onb.Subtitle,
                                        fontFamily = InterFontFamily,
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp,
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        OnbButton(
                                            text = "Hủy",
                                            style = OnbButtonStyle.Secondary,
                                            enabled = !controller.busy,
                                            onClick = { confirmWipeCloud = false },
                                            modifier = Modifier.weight(1f),
                                        )
                                        OnbButton(
                                            text = if (controller.busy) "Đang xóa…" else "Xác nhận xóa",
                                            enabled = !controller.busy,
                                            onClick = {
                                                scope.launch {
                                                    controller.busy = true
                                                    val res = MochyCloudSyncManager.clearAllCloudData(context)
                                                    controller.busy = false
                                                    confirmWipeCloud = false
                                                    controller.statusNotice = res.fold(
                                                        onSuccess = { "Đã xóa sạch dữ liệu trên đám mây." },
                                                        onFailure = { it.message ?: "Không thể xóa dữ liệu trên đám mây." },
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
        }

        val notice = controller.statusNotice ?: state.lastError
        if (!notice.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MenuRowSurface)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    text = notice,
                    color = if (state.lastError != null && controller.statusNotice == null) SheetErrorRed else Color.White,
                    fontFamily = InterFontFamily,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun CloudProfileChip(
    label: String,
    highlighted: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .pressBounce(interaction, pressedScale = 0.96f)
            .clip(CircleShape)
            .background(if (highlighted) Onb.ButtonPrimary else Color.White.copy(alpha = 0.12f))
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = RemoteFocus,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = Color.White,
            fontFamily = InterFontFamily,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
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
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = value,
            color = Color.White,
            fontFamily = InterFontFamily,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            color = Onb.Muted,
            fontFamily = InterFontFamily,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CloudActionRow(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val contentAlpha = if (enabled) 1f else 0.45f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .pressBounce(interaction, pressedScale = 0.97f)
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MenuRowSurface.copy(alpha = if (enabled) 1f else 0.6f))
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = RemoteFocus,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .graphicsLayer { alpha = contentAlpha }
                .size(28.dp)
                .clip(CircleShape)
                .background(if (danger) SheetErrorRed.copy(alpha = 0.18f) else Color(0xFFDADADA)),
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = if (danger) SheetErrorRed else Color(0xFF1C1C1E),
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            fontFamily = InterFontFamily,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp,
            color = (if (danger) SheetErrorRed else Color.White).copy(alpha = contentAlpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
