package com.mochyy.mochymusic.ui.screens.update

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mochyy.mochymusic.BuildConfig
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.core.updater.GithubRelease
import com.mochyy.mochymusic.core.updater.downloadApk
import com.mochyy.mochymusic.core.updater.formatBytes
import com.mochyy.mochymusic.core.updater.installApk
import com.mochyy.mochymusic.ui.component.RemoteFocus
import com.mochyy.mochymusic.ui.component.pressBounce
import com.mochyy.mochymusic.ui.player.menu.MenuRowSurface
import com.mochyy.mochymusic.ui.player.menu.MenuSurface
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import kotlinx.coroutines.launch

private val CardSurface = MenuRowSurface
private val PopupAccentGreen = Color(0xFF9CF58A)

@Composable
fun UpdatePromptSheet(
    release: GithubRelease,
    userName: String,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onReveal: ((Float) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var downloading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val progress = remember { mutableFloatStateOf(0f) }

    var readyToInstall by remember(release.apkName) {
        mutableStateOf(com.mochyy.mochymusic.core.updater.isDownloaded(context, release))
    }

    var needsPermission by remember { mutableStateOf(false) }
    var appeared by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        appeared = true
        onReveal?.invoke(1f)
    }

    val popupScale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.90f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 420f),
        label = "updatePopupScale",
    )
    val popupAlpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "updatePopupAlpha",
    )

    val startInstall: (java.io.File) -> Unit = { file ->
        if (installApk(context, file)) {
            needsPermission = false
        } else {
            needsPermission = true
            readyToInstall = true
            error = "Hãy cấp quyền \"Cài đặt ứng dụng không xác định\" cho Mochy, sau đó chạm Cài đặt ngay lại lần nữa."
        }
    }

    Dialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !downloading,
            dismissOnClickOutside = !downloading,
        ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.68f * popupAlpha))
                .clickable(
                    enabled = !downloading,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .padding(horizontal = 20.dp, vertical = 28.dp),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = popupScale
                        scaleY = popupScale
                        alpha = popupAlpha
                    }
                    .clip(RoundedCornerShape(28.dp))
                    .background(MenuSurface)
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(28.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(horizontal = 22.dp, vertical = 22.dp)
                    .animateContentSize(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(CardSurface)
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp)),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_mochy_mark),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(30.dp),
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (userName.isBlank()) "Đã có bản cập nhật mới!" else "Chào $userName, có bản mới!",
                            fontFamily = InterFontFamily,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(PopupAccentGreen.copy(alpha = 0.16f))
                                    .border(1.dp, PopupAccentGreen.copy(alpha = 0.45f), CircleShape)
                                    .padding(horizontal = 10.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    text = "Mới · v${shortVersion(release.version)}",
                                    fontFamily = InterFontFamily,
                                    color = PopupAccentGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Text(
                                text = "Hiện tại: v${shortVersion(BuildConfig.VERSION_NAME)}",
                                fontFamily = InterFontFamily,
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    if (!downloading) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(CardSurface)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = RemoteFocus,
                                    onClick = onDismiss,
                                ),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.close),
                                contentDescription = "Đóng",
                                tint = Color.White.copy(alpha = 0.75f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 90.dp, max = 250.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(CardSurface)
                        .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(20.dp))
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = "Có gì mới trong bản ${shortVersion(release.version)}",
                        fontFamily = InterFontFamily,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.3).sp,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    ChangelogMarkdown(
                        markdown = release.body.ifBlank { "Cập nhật giao diện Tiếng Việt đồng bộ 100%, bổ sung chia sẻ nhanh ở menu 3 chấm và tối ưu hiệu năng." },
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(CardSurface)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    AppIconImage(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(11.dp)),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = release.apkName ?: "Mochy-Music-vi-arm64-release.apk",
                            fontFamily = InterFontFamily,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${BuildConfig.APPLICATION_ID} · ${formatBytes(release.apkSizeBytes)}",
                            fontFamily = InterFontFamily,
                            color = Color(0xFF9A9A9A),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                error?.let {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = it,
                        fontFamily = InterFontFamily,
                        color = Color(0xFFE0736B),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                val animatedProgress by animateFloatAsState(
                    targetValue = progress.floatValue,
                    animationSpec = tween(durationMillis = 120),
                    label = "promptDownloadProgress",
                )

                PromptButton(
                    enabled = !downloading && release.apkUrl != null,
                    progress = animatedProgress,
                    primary = true,
                    label = when {
                        downloading -> "Đang tải xuống… ${(animatedProgress * 100).toInt()}%"
                        release.apkUrl == null -> "Chưa có tệp APK"
                        needsPermission -> "Cài đặt ngay (Cấp quyền)"
                        readyToInstall -> "Cài đặt bản cập nhật ngay"
                        else -> "Cập nhật ngay"
                    },
                    dimmed = release.apkUrl == null,
                    onClick = {
                        error = null
                        val existing = com.mochyy.mochymusic.core.updater.apkFileFor(context, release)
                        if (com.mochyy.mochymusic.core.updater.isDownloaded(context, release)) {
                            startInstall(existing)
                        } else {
                            downloading = true
                            progress.floatValue = 0f
                            scope.launch {
                                val result = downloadApk(context, release) { progress.floatValue = it }
                                downloading = false
                                result.fold(
                                    onSuccess = {
                                        readyToInstall = true
                                        startInstall(it)
                                    },
                                    onFailure = { error = it.message ?: "Tải xuống thất bại, vui lòng thử lại." },
                                )
                            }
                        }
                    },
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    PromptButton(
                        enabled = !downloading,
                        label = "Tải trên web",
                        dimmed = downloading,
                        onClick = { context.openReleaseUrl(release.htmlUrl) },
                        modifier = Modifier.weight(1f),
                    )
                    PromptButton(
                        enabled = !downloading,
                        label = "Để sau",
                        dimmed = downloading,
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PromptButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dimmed: Boolean = false,
    primary: Boolean = false,
    progress: Float = 0f,
) {
    val interaction = remember { MutableInteractionSource() }
    val bgColor = if (primary) Color.White.copy(alpha = 0.24f) else CardSurface
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .pressBounce(interaction, pressedScale = 0.97f)
            .fillMaxWidth()
            .height(50.dp)
            .clip(CircleShape)
            .background(bgColor)
            .border(
                width = 1.dp,
                color = if (primary) Color.White.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f),
                shape = CircleShape,
            )
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = RemoteFocus,
                onClick = onClick,
            ),
    ) {
        if (progress > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = progress.coerceIn(0f, 1f))
                    .background(PopupAccentGreen.copy(alpha = 0.35f)),
            )
        }
        Text(
            text = label,
            fontFamily = InterFontFamily,
            color = Color.White.copy(alpha = if (dimmed) 0.5f else 1f),
            fontSize = if (primary) 15.sp else 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

private fun android.content.Context.openReleaseUrl(url: String) {
    runCatching {
        startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}