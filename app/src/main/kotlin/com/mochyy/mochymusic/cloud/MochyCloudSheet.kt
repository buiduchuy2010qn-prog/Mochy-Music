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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.extensions.toMediaItem
import com.mochyy.mochymusic.models.MediaMetadata
import com.mochyy.mochymusic.playback.queues.ListQueue
import com.mochyy.mochymusic.ui.component.LocalZoomOutOverlayState
import com.mochyy.mochymusic.ui.component.OdometerNumber
import com.mochyy.mochymusic.ui.component.PopupSheetHandle
import com.mochyy.mochymusic.ui.component.PopupSheetState
import com.mochyy.mochymusic.ui.component.RemoteFocus
import com.mochyy.mochymusic.ui.component.RoundedHighlight
import com.mochyy.mochymusic.ui.component.TopToaster
import com.mochyy.mochymusic.ui.component.press3D
import com.mochyy.mochymusic.ui.component.pressBounce
import com.mochyy.mochymusic.ui.component.rememberRevealSeenState
import com.mochyy.mochymusic.ui.component.revealOnAppear
import com.mochyy.mochymusic.ui.player.menu.MenuRowSurface
import com.mochyy.mochymusic.ui.screens.setup.onboarding.Onb
import com.mochyy.mochymusic.ui.screens.setup.onboarding.OnbPillInput
import com.mochyy.mochymusic.ui.screens.setup.onboarding.PageBadge
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SheetErrorRed = Color(0xFFE0736B)
private val ResumeAccentGreen = Color(0xFF9CF58A)
private val CloudRowHighlight = RoundedHighlight(cornerRadius = 18.dp)
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
            popup = this@showMochyCloudSheet,
            controller = controller,
            onLockChanged = { locked -> handle?.setLocked(locked) },
            onDismissSheet = { handle?.dismiss() },
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
            CloudPrimaryButton(
                text = stringResource(R.string.done),
                onClick = onDismiss,
            )
        }
        state.signedIn -> {
            val enabled = !controller.busy && !state.syncing
            CloudPrimaryButton(
                text = if (state.syncing) "Đang đồng bộ…" else "Đồng bộ ngay",
                enabled = enabled,
                dimmed = !enabled,
                onClick = {
                    controller.statusNotice = null
                    MochyCloudSyncManager.syncNow(context, fromUser = true)
                },
            )
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
            CloudPrimaryButton(
                text = buttonLabel,
                enabled = enabled,
                dimmed = !enabled || !formReady,
                onClick = {
                    if (!formReady) {
                        controller.statusNotice = "Nhập tên đăng nhập từ 3 ký tự và mật khẩu từ 6 ký tự."
                        return@CloudPrimaryButton
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

@Composable
private fun MochyCloudSheetContent(
    popup: PopupSheetState,
    controller: CloudSheetUiController,
    onLockChanged: (Boolean) -> Unit,
    onDismissSheet: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    val state by MochyCloudSyncManager.state.collectAsState()
    val playerConnection = LocalPlayerConnection.current
    val currentPlayingSong = if (playerConnection != null) {
        playerConnection.mediaMetadata.collectAsState().value
    } else {
        null
    }
    val revealSeen = rememberRevealSeenState()

    val localDeviceId = remember(context) { MochyCloudSyncManager.localDeviceId(context) }

    LaunchedEffect(controller.busy) {
        onLockChanged(controller.busy)
    }

    LaunchedEffect(state.signedIn) {
        if (state.signedIn) {
            MochyCloudSyncManager.refreshRemoteResume(context)
        }
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
            modifier = Modifier.revealOnAppear("cloud_header", revealSeen, cascade = true),
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
                    .revealOnAppear("cloud_unconfigured", revealSeen, cascade = true)
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
                                .revealOnAppear("cloud_auth_info", revealSeen, cascade = true)
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
                                .revealOnAppear("cloud_auth_tabs", revealSeen, cascade = true)
                                .clip(CircleShape)
                                .background(MenuRowSurface)
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            listOf("Đăng nhập", "Đăng ký mới").forEachIndexed { idx, label ->
                                val selected = controller.authTab == idx
                                val tabInteraction = remember { MutableInteractionSource() }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .pressBounce(tabInteraction, pressedScale = 0.96f)
                                        .clip(CircleShape)
                                        .background(if (selected) Onb.ButtonPrimary else Color.Transparent)
                                        .clickable(
                                            enabled = !controller.busy,
                                            interactionSource = tabInteraction,
                                            indication = RemoteFocus,
                                        ) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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

                        Column(
                            modifier = Modifier.revealOnAppear("cloud_auth_inputs", revealSeen, cascade = true),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
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
                        }

                        Spacer(Modifier.height(4.dp))
                        Column(
                            modifier = Modifier.revealOnAppear("cloud_offline_backup", revealSeen, cascade = true),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Sao lưu ngoại tuyến",
                                color = Color.White,
                                fontFamily = InterFontFamily,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.3).sp,
                            )
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
                        // Thẻ hồ sơ với hiệu ứng nghiêng 3D chuẩn Mochy
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .revealOnAppear("cloud_profile_card", revealSeen, cascade = true)
                                .press3D(maxTilt = 7f, pressedScale = 0.97f)
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
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
                                            highlighted = false,
                                            enabled = !controller.busy,
                                            onClick = {
                                                controller.statusNotice = null
                                                popup.showRenameSheet(
                                                    initialName = state.displayName.ifBlank { state.username },
                                                    onSaved = { msg -> controller.statusNotice = msg },
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        // Thẻ "Nghe tiếp từ thiết bị khác"
                        val otherResume = state.remoteResume?.takeIf { it.deviceId != localDeviceId }
                        var cachedOtherResume by remember { mutableStateOf<CloudPlaybackResume?>(null) }
                        LaunchedEffect(otherResume) {
                            if (otherResume != null) cachedOtherResume = otherResume
                        }
                        AnimatedVisibility(
                            visible = otherResume != null,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            val shownResume = otherResume ?: cachedOtherResume
                            if (shownResume != null) {
                                PlaybackResumeCard(
                                    resume = shownResume,
                                    busy = controller.busy,
                                    onResume = {
                                        val conn = playerConnection
                                        if (conn == null) {
                                            TopToaster.error("Trình phát chưa sẵn sàng.")
                                        } else {
                                            val primaryItem = MediaMetadata(
                                                id = shownResume.songId,
                                                title = shownResume.title,
                                                artists = shownResume.artists
                                                    .split(",")
                                                    .map { it.trim() }
                                                    .filter { it.isNotBlank() }
                                                    .map { MediaMetadata.Artist(id = null, name = it) },
                                                duration = shownResume.durationSec,
                                                thumbnailUrl = shownResume.thumbnailUrl,
                                                album = shownResume.albumName
                                                    ?.takeIf { it.isNotBlank() }
                                                    ?.let { MediaMetadata.Album(id = "", title = it) },
                                            ).toMediaItem()
                                            val upcomingItems = shownResume.queueTracks
                                                .filter { it.songId != shownResume.songId }
                                                .map { track ->
                                                    MediaMetadata(
                                                        id = track.songId,
                                                        title = track.title,
                                                        artists = track.artists
                                                            .split(",")
                                                            .map { it.trim() }
                                                            .filter { it.isNotBlank() }
                                                            .map { MediaMetadata.Artist(id = null, name = it) },
                                                        duration = track.duration,
                                                        thumbnailUrl = track.thumbnailUrl,
                                                        album = track.albumName
                                                            ?.takeIf { it.isNotBlank() }
                                                            ?.let { MediaMetadata.Album(id = "", title = it) },
                                                    ).toMediaItem()
                                                }
                                            val allItems = listOf(primaryItem) + upcomingItems
                                            conn.playQueue(
                                                ListQueue(
                                                    title = "Nghe tiếp từ ${shownResume.deviceName}",
                                                    items = allItems,
                                                    startIndex = 0,
                                                    position = shownResume.positionMs.coerceAtLeast(0L),
                                                ),
                                            )
                                            TopToaster.show(
                                                text = "Đang phát tiếp từ ${shownResume.deviceName}",
                                                thumbnail = shownResume.thumbnailUrl,
                                            )
                                            onDismissSheet()
                                        }
                                    },
                                )
                            }
                        }

                        // Lưới thống kê 5 mục với số cuộn cơ học OdometerNumber
                        Column(
                            modifier = Modifier.revealOnAppear("cloud_stats_grid", revealSeen, cascade = true),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
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
                                modifier = Modifier
                                    .revealOnAppear("cloud_sync_time", revealSeen, cascade = true)
                                    .padding(horizontal = 4.dp),
                            )
                        }

                        // Danh sách tác vụ chuẩn Mochy
                        Column(
                            modifier = Modifier.revealOnAppear("cloud_actions_list", revealSeen, cascade = true),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (currentPlayingSong != null && playerConnection != null) {
                                CloudActionRow(
                                    icon = R.drawable.smartphone,
                                    label = "Lưu vị trí đang nghe lên Cloud",
                                    enabled = !controller.busy,
                                    onClick = {
                                        val posMs = runCatching {
                                            playerConnection.player.currentPosition.coerceAtLeast(0L)
                                        }.getOrDefault(0L)
                                        val snapshot = playerConnection.queueSnapshot.value
                                        val nextIndex = (snapshot.currentIndex + 1).coerceAtLeast(0)
                                        val upcoming = if (nextIndex < snapshot.items.size) {
                                            snapshot.items.subList(nextIndex, snapshot.items.size)
                                        } else {
                                            emptyList()
                                        }
                                        val queueTracks = upcoming
                                            .filter { it.mediaId != currentPlayingSong.id }
                                            .distinctBy { it.mediaId }
                                            .take(25)
                                            .map { item ->
                                                CloudPlaylistTrack(
                                                    songId = item.mediaId,
                                                    title = item.title.ifBlank { "Bài hát" },
                                                    artists = item.artist.ifBlank { "Nghệ sĩ" },
                                                    albumName = null,
                                                    duration = (item.duration / 1000L).toInt().takeIf { it > 0 } ?: -1,
                                                    thumbnailUrl = item.artworkUri?.toString(),
                                                )
                                            }
                                        scope.launch {
                                            controller.busy = true
                                            controller.statusNotice = null
                                            val res = MochyCloudSyncManager.pushPlaybackResume(
                                                context = context,
                                                song = currentPlayingSong,
                                                positionMs = posMs,
                                                queueTracks = queueTracks,
                                            )
                                            controller.busy = false
                                            res.onSuccess {
                                                TopToaster.show(
                                                    text = "Đã lưu vị trí đang nghe lên Cloud",
                                                    thumbnail = currentPlayingSong.thumbnailUrl,
                                                    iconRes = R.drawable.cloud,
                                                )
                                                controller.statusNotice =
                                                    "Đã lưu vị trí \"${currentPlayingSong.title}\" lên Mochy Cloud."
                                            }.onFailure { err ->
                                                controller.statusNotice =
                                                    err.message ?: "Không thể lưu vị trí đang nghe."
                                            }
                                        }
                                    },
                                )
                            }

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
                                    controller.statusNotice = null
                                    popup.showChangePasswordSheet(
                                        onChanged = { msg -> controller.statusNotice = msg },
                                    )
                                },
                            )
                            CloudActionRow(
                                icon = R.drawable.logout,
                                label = "Đăng xuất tài khoản",
                                enabled = !controller.busy,
                                onClick = {
                                    scope.launch {
                                        controller.busy = true
                                        MochyCloudSyncManager.signOut(context)
                                        controller.busy = false
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
                                    controller.statusNotice = null
                                    popup.showConfirmWipeCloudSheet(
                                        onWiped = { msg -> controller.statusNotice = msg },
                                    )
                                },
                            )
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
private fun PlaybackResumeCard(
    resume: CloudPlaybackResume,
    busy: Boolean,
    onResume: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val buttonInteraction = remember { MutableInteractionSource() }
    val queueCount = 1 + resume.queueTracks.count { it.songId != resume.songId }
    val subtitle = remember(resume.positionMs, queueCount) {
        "Dừng ở ${formatResumeTime(resume.positionMs)} · Hàng đợi $queueCount bài"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .press3D(maxTilt = 7f, pressedScale = 0.97f)
            .clip(RoundedCornerShape(22.dp))
            .background(MenuRowSurface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(ResumeAccentGreen),
            )
            Text(
                text = "Nghe tiếp từ ${resume.deviceName}",
                color = ResumeAccentGreen,
                fontFamily = InterFontFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Onb.ButtonSecondary),
                contentAlignment = Alignment.Center,
            ) {
                if (!resume.thumbnailUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = resume.thumbnailUrl,
                        contentDescription = resume.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.music_note),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = resume.title,
                    color = Color.White,
                    fontFamily = InterFontFamily,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = Onb.Subtitle,
                    fontFamily = InterFontFamily,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box(
                modifier = Modifier
                    .pressBounce(buttonInteraction, pressedScale = 0.94f)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f))
                    .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                    .clickable(
                        enabled = !busy,
                        interactionSource = buttonInteraction,
                        indication = RemoteFocus,
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onResume()
                    }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Phát tiếp",
                    color = Color.White,
                    fontFamily = InterFontFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

@Stable
private class RenameSheetState(initialName: String) {
    var nameInput by mutableStateOf(initialName.take(40))
    var saving by mutableStateOf(false)
    var errorMsg by mutableStateOf<String?>(null)
}

/** Tầng sheet con Đổi tên đẩy qua [PopupSheetState] để bảng Cloud thu nhỏ 0.90x phía sau. */
private fun PopupSheetState.showRenameSheet(
    initialName: String,
    onSaved: (String) -> Unit,
) {
    val form = RenameSheetState(initialName)
    var handle: PopupSheetHandle? = null
    handle = showWithHandle(
        buttonBar = {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val focusManager = LocalFocusManager.current
            val canSave = !form.saving && form.nameInput.trim().isNotEmpty()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CloudPrimaryButton(
                    text = if (form.saving) "Đang lưu…" else "Lưu tên hiển thị",
                    enabled = canSave,
                    dimmed = !canSave,
                    onClick = {
                        focusManager.clearFocus()
                        scope.launch {
                            form.saving = true
                            form.errorMsg = null
                            handle?.setLocked(true)
                            val res = MochyCloudSyncManager.updateProfileAndAvatar(context, form.nameInput.trim(), null)
                            form.saving = false
                            handle?.setLocked(false)
                            res.onSuccess {
                                onSaved("Đã cập nhật tên hiển thị.")
                                handle?.dismiss()
                            }.onFailure { err ->
                                form.errorMsg = err.message ?: "Không thể lưu tên hiển thị."
                            }
                        }
                    },
                )
                CloudPrimaryButton(
                    text = "Hủy",
                    enabled = !form.saving,
                    secondary = true,
                    onClick = { handle?.dismiss() },
                )
            }
        },
    ) {
        val focusManager = LocalFocusManager.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PageBadge(painterResource(R.drawable.edit), size = 40.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Đổi tên hiển thị",
                        color = Color.White,
                        fontFamily = InterFontFamily,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.03).em,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Tên này hiển thị trên hồ sơ Mochy Cloud của bạn.",
                        color = Onb.Subtitle,
                        fontFamily = InterFontFamily,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
            }

            OnbPillInput(
                value = form.nameInput,
                onValueChange = {
                    form.nameInput = it.take(40)
                    form.errorMsg = null
                },
                placeholder = "tên hiển thị mới",
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.clearFocus() },
                ),
            )

            val err = form.errorMsg
            if (!err.isNullOrBlank()) {
                Text(
                    text = err,
                    color = SheetErrorRed,
                    fontFamily = InterFontFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Stable
private class PasswordSheetState {
    var newPassword by mutableStateOf("")
    var confirmPassword by mutableStateOf("")
    var saving by mutableStateOf(false)
    var errorMsg by mutableStateOf<String?>(null)
}

/** Tầng sheet con Đổi mật khẩu đẩy qua [PopupSheetState]. */
private fun PopupSheetState.showChangePasswordSheet(
    onChanged: (String) -> Unit,
) {
    val form = PasswordSheetState()
    var handle: PopupSheetHandle? = null
    handle = showWithHandle(
        buttonBar = {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val focusManager = LocalFocusManager.current
            val ready = !form.saving && form.newPassword.length >= 6 && form.newPassword == form.confirmPassword
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CloudPrimaryButton(
                    text = if (form.saving) "Đang cập nhật…" else "Cập nhật mật khẩu",
                    enabled = !form.saving,
                    dimmed = !ready,
                    onClick = {
                        if (form.newPassword.length < 6) {
                            form.errorMsg = "Mật khẩu mới phải có ít nhất 6 ký tự."
                            return@CloudPrimaryButton
                        }
                        if (form.newPassword != form.confirmPassword) {
                            form.errorMsg = "Mật khẩu nhập lại chưa khớp."
                            return@CloudPrimaryButton
                        }
                        focusManager.clearFocus()
                        scope.launch {
                            form.saving = true
                            form.errorMsg = null
                            handle?.setLocked(true)
                            val res = MochyCloudSyncManager.changePassword(context, form.newPassword)
                            form.saving = false
                            handle?.setLocked(false)
                            res.onSuccess {
                                onChanged("Đã đổi mật khẩu Mochy Cloud thành công.")
                                handle?.dismiss()
                            }.onFailure { err ->
                                form.errorMsg = err.message ?: "Không thể đổi mật khẩu."
                            }
                        }
                    },
                )
                CloudPrimaryButton(
                    text = "Hủy",
                    enabled = !form.saving,
                    secondary = true,
                    onClick = { handle?.dismiss() },
                )
            }
        },
    ) {
        val focusManager = LocalFocusManager.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PageBadge(painterResource(R.drawable.lock), size = 40.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Đổi mật khẩu tài khoản",
                        color = Color.White,
                        fontFamily = InterFontFamily,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.03).em,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Dùng tối thiểu 6 ký tự để bảo vệ tài khoản Mochy Cloud.",
                        color = Onb.Subtitle,
                        fontFamily = InterFontFamily,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
            }

            OnbPillInput(
                value = form.newPassword,
                onValueChange = {
                    form.newPassword = it
                    form.errorMsg = null
                },
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
                value = form.confirmPassword,
                onValueChange = {
                    form.confirmPassword = it
                    form.errorMsg = null
                },
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
            if (form.confirmPassword.isNotEmpty() && form.newPassword != form.confirmPassword) {
                Text(
                    text = "Mật khẩu nhập lại chưa khớp.",
                    color = SheetErrorRed,
                    fontFamily = InterFontFamily,
                    fontSize = 12.sp,
                )
            }
            val err = form.errorMsg
            if (!err.isNullOrBlank()) {
                Text(
                    text = err,
                    color = SheetErrorRed,
                    fontFamily = InterFontFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Stable
private class WipeSheetState {
    var wiping by mutableStateOf(false)
    var errorMsg by mutableStateOf<String?>(null)
}

/** Tầng sheet con Xác nhận xóa dữ liệu đám mây đẩy qua [PopupSheetState]. */
private fun PopupSheetState.showConfirmWipeCloudSheet(
    onWiped: (String) -> Unit,
) {
    val form = WipeSheetState()
    var handle: PopupSheetHandle? = null
    handle = showWithHandle(
        buttonBar = {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CloudPrimaryButton(
                    text = if (form.wiping) "Đang xóa…" else "Xác nhận xóa dữ liệu",
                    enabled = !form.wiping,
                    danger = true,
                    onClick = {
                        scope.launch {
                            form.wiping = true
                            form.errorMsg = null
                            handle?.setLocked(true)
                            val res = MochyCloudSyncManager.clearAllCloudData(context)
                            form.wiping = false
                            handle?.setLocked(false)
                            res.onSuccess {
                                onWiped("Đã xóa sạch dữ liệu trên đám mây.")
                                handle?.dismiss()
                            }.onFailure { err ->
                                form.errorMsg = err.message ?: "Không thể xóa dữ liệu trên đám mây."
                            }
                        }
                    },
                )
                CloudPrimaryButton(
                    text = "Hủy",
                    enabled = !form.wiping,
                    secondary = true,
                    onClick = { handle?.dismiss() },
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(SheetErrorRed.copy(alpha = 0.2f)),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.delete),
                        contentDescription = null,
                        tint = SheetErrorRed,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Xác nhận xóa dữ liệu đám mây?",
                        color = SheetErrorRed,
                        fontFamily = InterFontFamily,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.03).em,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MenuRowSurface)
                    .padding(16.dp),
            ) {
                Text(
                    text = "Toàn bộ thư viện đã đồng bộ, vị trí nghe tiếp và ảnh đại diện trên đám mây sẽ bị xóa. Nhạc trên thiết bị này vẫn được giữ nguyên.",
                    color = Onb.Subtitle,
                    fontFamily = InterFontFamily,
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp,
                )
            }

            val err = form.errorMsg
            if (!err.isNullOrBlank()) {
                Text(
                    text = err,
                    color = SheetErrorRed,
                    fontFamily = InterFontFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun CloudPrimaryButton(
    text: String,
    enabled: Boolean = true,
    dimmed: Boolean = false,
    secondary: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val bgColor = when {
        danger -> SheetErrorRed.copy(alpha = 0.22f)
        secondary -> Color.White.copy(alpha = 0.12f)
        else -> Color.White.copy(alpha = 0.26f)
    }
    Box(
        modifier = Modifier
            .pressBounce(interaction, pressedScale = 0.97f)
            .fillMaxWidth()
            .height(50.dp)
            .graphicsLayer { alpha = if (enabled && !dimmed) 1f else 0.56f }
            .clip(CircleShape)
            .background(bgColor)
            .then(
                if (danger) Modifier.border(1.dp, SheetErrorRed.copy(alpha = 0.4f), CircleShape)
                else Modifier
            )
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = RemoteFocus,
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (danger) SheetErrorRed else Color.White,
            fontFamily = InterFontFamily,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .pressBounce(interaction, pressedScale = 0.96f)
            .clip(CircleShape)
            .background(if (highlighted) Onb.ButtonPrimary else Color.White.copy(alpha = 0.12f))
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = RemoteFocus,
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
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
        OdometerNumber(
            value = value,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = InterFontFamily,
            color = Color.White,
            letterSpacing = (-0.4).sp,
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
    val haptic = LocalHapticFeedback.current
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
                indication = CloudRowHighlight,
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
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

private fun formatResumeTime(positionMs: Long): String {
    val totalSec = (positionMs / 1000L).coerceAtLeast(0L)
    val hours = totalSec / 3600L
    val minutes = (totalSec % 3600L) / 60L
    val seconds = totalSec % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
