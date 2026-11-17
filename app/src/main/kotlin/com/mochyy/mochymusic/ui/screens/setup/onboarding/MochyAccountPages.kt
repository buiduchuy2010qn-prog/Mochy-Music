// Mochy Cloud welcome & account screen styled identically to Mochy's native dark onboarding.

package com.mochyy.mochymusic.ui.screens.setup.onboarding

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.cloud.MochyCloudSyncManager
import com.mochyy.mochymusic.ui.screens.setup.PhotoCropperContainer
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import kotlinx.coroutines.launch
import java.io.File

private enum class AccountSubStep { Hub, SignUp, SignIn }

private fun filterUsernameInput(raw: String): String =
    raw.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' }.take(24)

@Composable
internal fun MochyWelcomeAccountStep(
    standaloneWelcome: Boolean,
    onAuthenticated: (displayName: String, avatarUri: Uri?) -> Unit,
    onGuest: () -> Unit,
    onBack: (() -> Unit)? = null,
    onPickAvatarRequest: (() -> Unit)? = null,
    pickedAvatarUri: Uri? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val cloudState by MochyCloudSyncManager.state.collectAsState()

    var subStep by remember { mutableStateOf(AccountSubStep.Hub) }
    var forward by remember { mutableStateOf(true) }

    var usernameInput by rememberSaveable { mutableStateOf("") }
    var displayNameInput by rememberSaveable { mutableStateOf("") }
    var passwordInput by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    fun navigateTo(target: AccountSubStep) {
        forward = target != AccountSubStep.Hub
        errorMsg = null
        subStep = target
    }

    BackHandler(enabled = subStep != AccountSubStep.Hub && !busy) {
        navigateTo(AccountSubStep.Hub)
    }

    fun submitSignUp() {
        if (busy || usernameInput.length < 3 || passwordInput.length < 6) return
        focusManager.clearFocus()
        scope.launch {
            busy = true
            errorMsg = null
            val res = MochyCloudSyncManager.signUp(
                context = context,
                username = usernameInput,
                password = passwordInput,
                displayName = displayNameInput,
                avatarUri = pickedAvatarUri,
            )
            busy = false
            res.onSuccess {
                val st = MochyCloudSyncManager.state.value
                val resolvedName = st.displayName.ifBlank { displayNameInput.trim().ifBlank { usernameInput } }
                val resolvedAvatar = st.avatarLocalPath?.let(::File)?.takeIf { it.exists() }?.let(Uri::fromFile)
                    ?: pickedAvatarUri
                passwordInput = ""
                onAuthenticated(resolvedName, resolvedAvatar)
            }.onFailure { err ->
                errorMsg = err.message ?: "Không thể tạo tài khoản."
            }
        }
    }

    fun submitSignIn() {
        if (busy || usernameInput.length < 3 || passwordInput.length < 6) return
        focusManager.clearFocus()
        scope.launch {
            busy = true
            errorMsg = null
            val res = MochyCloudSyncManager.signIn(
                context = context,
                username = usernameInput,
                password = passwordInput,
            )
            busy = false
            res.onSuccess {
                val st = MochyCloudSyncManager.state.value
                val resolvedName = st.displayName.ifBlank { st.username.ifBlank { usernameInput } }
                val resolvedAvatar = st.avatarLocalPath?.let(::File)?.takeIf { it.exists() }?.let(Uri::fromFile)
                passwordInput = ""
                onAuthenticated(resolvedName, resolvedAvatar)
            }.onFailure { err ->
                errorMsg = err.message ?: "Sai tên đăng nhập hoặc mật khẩu."
            }
        }
    }

    AnimatedContent(
        targetState = subStep,
        transitionSpec = {
            if (forward) {
                slideInHorizontally(tween(380)) { it / 3 } + fadeIn(tween(320)) togetherWith
                    slideOutHorizontally(tween(380)) { -it / 4 } + fadeOut(tween(220))
            } else {
                slideInHorizontally(tween(380)) { -it / 3 } + fadeIn(tween(320)) togetherWith
                    slideOutHorizontally(tween(380)) { it / 4 } + fadeOut(tween(220))
            }.using(SizeTransform(clip = false))
        },
        label = "mochyAccountStep",
        modifier = Modifier.fillMaxSize(),
    ) { current ->
        when (current) {
            AccountSubStep.Hub -> OnboardingPage(
                badge = { PageBadge(painterResource(R.drawable.cloud)) },
                title = if (standaloneWelcome) "Chào mừng đến với mochy!" else "Tài khoản Mochy",
                subtitle = "Chọn cách bắt đầu. Tài khoản đám mây chỉ lưu Yêu thích, Lịch sử nghe và ảnh đại diện siêu nhẹ.",
                footer = {
                    if (cloudState.signedIn) {
                        OnbButton(
                            text = "Tiếp tục với @${cloudState.username}",
                            onClick = {
                                val resolvedName = cloudState.displayName.ifBlank { cloudState.username }
                                val resolvedAvatar = cloudState.avatarLocalPath?.let(::File)?.takeIf { it.exists() }?.let(Uri::fromFile)
                                onAuthenticated(resolvedName, resolvedAvatar)
                            },
                        )
                    }
                    if (onBack != null) {
                        OnbButton("thôi, quay lại", onClick = onBack, style = OnbButtonStyle.Secondary)
                    } else {
                        OnbButton("Bỏ qua, dùng chế độ Khách", onClick = onGuest, style = OnbButtonStyle.Secondary)
                    }
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (cloudState.signedIn) {
                        OnbCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val avatarFile = cloudState.avatarLocalPath?.let(::File)?.takeIf { it.exists() }
                                Box(
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(CircleShape)
                                        .background(Onb.ButtonSecondary),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (avatarFile != null) {
                                        AsyncImage(
                                            model = avatarFile,
                                            contentDescription = null,
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
                                Spacer(Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = cloudState.displayName.ifBlank { cloudState.username },
                                        color = Color.White,
                                        fontFamily = InterFontFamily,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        text = "@${cloudState.username} · Đã kết nối Mochy Cloud",
                                        color = Onb.Subtitle,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.5.sp,
                                    )
                                }
                            }
                        }
                    }

                    OnbChoiceCard(
                        icon = painterResource(R.drawable.group_add),
                        title = "Tạo tài khoản mới",
                        description = "đăng ký nhanh bằng @username và mật khẩu · kèm ảnh đại diện nhẹ (≤ 24 KB), không cần email",
                        onClick = { navigateTo(AccountSubStep.SignUp) },
                    )
                    OnbChoiceCard(
                        icon = painterResource(R.drawable.login),
                        title = if (cloudState.signedIn) "Đổi tài khoản khác" else "Đăng nhập",
                        description = "đã có tài khoản Mochy? đăng nhập để khôi phục ngay bài hát yêu thích, lịch sử nghe và hồ sơ",
                        onClick = { navigateTo(AccountSubStep.SignIn) },
                    )
                    OnbChoiceCard(
                        icon = painterResource(R.drawable.person),
                        title = "Tiếp tục với tư cách Khách",
                        description = "vào thẳng ứng dụng không cần đăng ký · dữ liệu lưu trên máy, có thể tạo tài khoản bất cứ lúc nào",
                        onClick = onGuest,
                    )
                }
            }

            AccountSubStep.SignUp -> OnboardingPage(
                badge = { PageBadge(painterResource(R.drawable.group_add)) },
                title = "Tạo tài khoản",
                subtitle = "Không cần email hay mã OTP. Chỉ đồng bộ Yêu thích, Lịch sử nghe và Avatar nhẹ.",
                footer = {
                    OnbButton(
                        text = if (busy) "Đang tạo tài khoản…" else "Tạo tài khoản & Bắt đầu",
                        onClick = ::submitSignUp,
                        enabled = !busy && usernameInput.length >= 3 && passwordInput.length >= 6,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OnbButton(
                            text = "thôi, quay lại",
                            onClick = { navigateTo(AccountSubStep.Hub) },
                            style = OnbButtonStyle.Secondary,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        )
                        OnbButton(
                            text = "Chế độ Khách",
                            onClick = onGuest,
                            style = OnbButtonStyle.Secondary,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        )
                    }
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (onPickAvatarRequest != null) {
                        OnbCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = if (!busy) onPickAvatarRequest else null,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(CircleShape)
                                        .background(Onb.ButtonSecondary)
                                        .border(
                                            width = 1.5.dp,
                                            color = if (pickedAvatarUri != null) Color.White else Color.White.copy(alpha = 0.14f),
                                            shape = CircleShape,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (pickedAvatarUri != null) {
                                        AsyncImage(
                                            model = pickedAvatarUri,
                                            contentDescription = "Ảnh đại diện",
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
                                Spacer(Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (pickedAvatarUri != null) "Đã chọn ảnh đại diện" else "Chọn ảnh đại diện nhẹ (tùy chọn)",
                                        color = Color.White,
                                        fontFamily = InterFontFamily,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        text = "Tự động cắt vuông & nén WebP ≤ 24 KB",
                                        color = Onb.Muted,
                                        fontFamily = InterFontFamily,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "tên đăng nhập",
                            color = Onb.Subtitle,
                            fontFamily = InterFontFamily,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                        OnbPillInput(
                            value = usernameInput,
                            onValueChange = {
                                usernameInput = filterUsernameInput(it)
                                errorMsg = null
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
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "tên hiển thị",
                            color = Onb.Subtitle,
                            fontFamily = InterFontFamily,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                        OnbPillInput(
                            value = displayNameInput,
                            onValueChange = { displayNameInput = it.take(40) },
                            placeholder = "mặc định giống tên đăng nhập",
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Next,
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Down) },
                            ),
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "mật khẩu",
                            color = Onb.Subtitle,
                            fontFamily = InterFontFamily,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                        OnbPillInput(
                            value = passwordInput,
                            onValueChange = {
                                passwordInput = it
                                errorMsg = null
                            },
                            placeholder = "tối thiểu 6 ký tự",
                            isPassword = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { submitSignUp() },
                            ),
                        )
                    }

                    if (!errorMsg.isNullOrBlank()) {
                        Text(
                            text = errorMsg.orEmpty(),
                            color = Color(0xFFE0736B),
                            fontFamily = InterFontFamily,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }
                }
            }

            AccountSubStep.SignIn -> OnboardingPage(
                badge = { PageBadge(painterResource(R.drawable.login)) },
                title = "Đăng nhập",
                subtitle = "Khôi phục ngay danh sách Yêu thích, Lịch sử nghe và Hồ sơ Mochy của bạn.",
                footer = {
                    OnbButton(
                        text = if (busy) "Đang đăng nhập & đồng bộ…" else "Đăng nhập & Đồng bộ",
                        onClick = ::submitSignIn,
                        enabled = !busy && usernameInput.length >= 3 && passwordInput.length >= 6,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OnbButton(
                            text = "thôi, quay lại",
                            onClick = { navigateTo(AccountSubStep.Hub) },
                            style = OnbButtonStyle.Secondary,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        )
                        OnbButton(
                            text = "Chế độ Khách",
                            onClick = onGuest,
                            style = OnbButtonStyle.Secondary,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        )
                    }
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "tên đăng nhập",
                            color = Onb.Subtitle,
                            fontFamily = InterFontFamily,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                        OnbPillInput(
                            value = usernameInput,
                            onValueChange = {
                                usernameInput = filterUsernameInput(it)
                                errorMsg = null
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
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "mật khẩu",
                            color = Onb.Subtitle,
                            fontFamily = InterFontFamily,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                        OnbPillInput(
                            value = passwordInput,
                            onValueChange = {
                                passwordInput = it
                                errorMsg = null
                            },
                            placeholder = "nhập mật khẩu",
                            isPassword = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { submitSignIn() },
                            ),
                        )
                    }

                    Text(
                        text = "Chưa có tài khoản? Tạo tài khoản mới →",
                        color = Onb.Subtitle,
                        fontFamily = InterFontFamily,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(enabled = !busy) { navigateTo(AccountSubStep.SignUp) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )

                    if (!errorMsg.isNullOrBlank()) {
                        Text(
                            text = errorMsg.orEmpty(),
                            color = Color(0xFFE0736B),
                            fontFamily = InterFontFamily,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MochyAccountStandaloneSheet(
    onFinished: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragRelease: () -> Unit,
) {
    var selectedUncroppedUri by remember { mutableStateOf<Uri?>(null) }
    var pickedAvatarUri by remember { mutableStateOf<Uri?>(null) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        if (picked != null) {
            selectedUncroppedUri = picked
        }
    }

    PhotoCropperContainer(
        uri = selectedUncroppedUri,
        onDone = { cropped ->
            pickedAvatarUri = cropped
            selectedUncroppedUri = null
        },
        onCancel = { selectedUncroppedUri = null },
        onSelectNewImage = {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .background(Onb.Page),
        ) {
            MochyWelcomeAccountStep(
                standaloneWelcome = true,
                onAuthenticated = { _, _ -> onFinished() },
                onGuest = onFinished,
                onPickAvatarRequest = {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                pickedAvatarUri = pickedAvatarUri,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, amount ->
                                change.consume()
                                onDrag(amount)
                            },
                            onDragEnd = onDragRelease,
                            onDragCancel = onDragRelease,
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(100.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.5f)),
                )
            }
        }
    }
}
