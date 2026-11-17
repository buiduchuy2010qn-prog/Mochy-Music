package com.mochyy.mochymusic.ui.screens.update

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import com.mochyy.mochymusic.BuildConfig
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.core.updater.GithubRelease
import com.mochyy.mochymusic.core.updater.apkFileFor
import com.mochyy.mochymusic.core.updater.downloadApk
import com.mochyy.mochymusic.core.updater.formatBytes
import com.mochyy.mochymusic.core.updater.installApk
import com.mochyy.mochymusic.core.updater.isDownloaded
import com.mochyy.mochymusic.ui.component.LocalZoomOutOverlayState
import com.mochyy.mochymusic.ui.component.PopupSheetHandle
import com.mochyy.mochymusic.ui.component.PopupSheetState
import com.mochyy.mochymusic.ui.component.RemoteFocus
import com.mochyy.mochymusic.ui.component.pressBounce
import com.mochyy.mochymusic.ui.player.menu.MenuRowSurface
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import kotlinx.coroutines.launch
import java.io.File

@Stable
private class UpdatePromptController {
    var downloading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var progress by mutableFloatStateOf(0f)
    var readyToInstall by mutableStateOf(false)
    var needsPermission by mutableStateOf(false)
    var handle: PopupSheetHandle? = null
}

fun PopupSheetState.showUpdatePromptSheet(
    release: GithubRelease,
    userName: String,
    onSnooze: () -> Unit,
    onClosed: (() -> Unit)? = null,
): PopupSheetHandle {
    val controller = UpdatePromptController()
    val handle = showWithHandle(
        buttonBar = {
            UpdatePromptButtons(
                controller = controller,
                release = release,
                onSnooze = onSnooze,
            )
        },
        onClosed = onClosed,
    ) {
        UpdatePromptContent(
            controller = controller,
            release = release,
            userName = userName,
        )
    }
    controller.handle = handle
    return handle
}

@Composable
fun UpdatePromptSheet(
    release: GithubRelease,
    userName: String,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit,
    @Suppress("UNUSED_PARAMETER") modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") onReveal: ((Float) -> Unit)? = null,
) {
    val overlay = LocalZoomOutOverlayState.current
    val currentOnSnooze by rememberUpdatedState(onSnooze)
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    DisposableEffect(overlay, release.tag, release.version) {
        var disposed = false
        var snoozed = false
        val handle = overlay.showUpdatePromptSheet(
            release = release,
            userName = userName,
            onSnooze = {
                snoozed = true
                currentOnSnooze()
            },
            onClosed = {
                if (!disposed && !snoozed) currentOnDismiss()
            },
        )
        onDispose {
            disposed = true
            handle.dismiss()
        }
    }
}

@Composable
private fun UpdatePromptContent(
    controller: UpdatePromptController,
    release: GithubRelease,
    userName: String,
) {
    val context = LocalContext.current

    LaunchedEffect(release.apkName) {
        controller.readyToInstall = isDownloaded(context, release)
    }

    LaunchedEffect(controller.downloading) {
        controller.handle?.setLocked(controller.downloading)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .animateContentSize(),
    ) {
        Spacer(modifier = Modifier.height(6.dp))

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(MenuRowSurface),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_mochy_mark),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(38.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (userName.isBlank()) {
                "Đã có bản cập nhật mới!"
            } else {
                "Đã có bản cập nhật mới, $userName!"
            },
            fontFamily = InterFontFamily,
            color = Color.White,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MenuRowSurface)
                .padding(16.dp),
        ) {
            Text(
                text = "Thay đổi trên ${shortVersion(release.version)}",
                fontFamily = InterFontFamily,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
            )
            Spacer(modifier = Modifier.height(4.dp))
            ChangelogMarkdown(markdown = release.body)
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = "Gói cài đặt —",
            fontFamily = InterFontFamily,
            color = Color(0xFF8A8A8A),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MenuRowSurface)
                .padding(14.dp),
        ) {
            AppIconImage(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = release.apkName ?: "mochy.apk",
                    fontFamily = InterFontFamily,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${BuildConfig.APPLICATION_ID} · ${formatBytes(release.apkSizeBytes)}",
                    fontFamily = InterFontFamily,
                    color = Color(0xFF9A9A9A),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        controller.error?.let { err ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = err,
                fontFamily = InterFontFamily,
                color = Color(0xFFE0736B),
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
    }
}

@Composable
private fun UpdatePromptButtons(
    controller: UpdatePromptController,
    release: GithubRelease,
    onSnooze: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val startInstall: (File) -> Unit = { file ->
        if (installApk(context, file)) {
            controller.needsPermission = false
        } else {
            controller.needsPermission = true
            controller.readyToInstall = true
            controller.error = "Hãy cấp quyền \"Cài đặt ứng dụng không xác định\" cho Mochy, rồi chạm Cài đặt ngay."
        }
    }

    val animatedProgress by animateFloatAsState(
        targetValue = controller.progress,
        animationSpec = tween(durationMillis = 120),
        label = "promptDownloadProgress",
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val primaryEnabled = !controller.downloading && release.apkUrl != null
        val primaryInteraction = remember { MutableInteractionSource() }
        val primaryLabel = when {
            controller.downloading -> "Đang tải… ${(animatedProgress * 100).toInt()}%"
            release.apkUrl == null -> "Không có tệp APK trong bản phát hành này"
            controller.needsPermission -> "Cài đặt ngay"
            controller.readyToInstall -> "Cài đặt ngay"
            else -> "Cài đặt ngay"
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .pressBounce(primaryInteraction, pressedScale = 0.97f)
                .fillMaxWidth()
                .height(50.dp)
                .graphicsLayer {
                    alpha = if (release.apkUrl == null) 0.45f else 1f
                }
                .clip(CircleShape)
                .background(
                    if (controller.downloading) {
                        Color.White.copy(alpha = 0.18f)
                    } else {
                        Color.White
                    },
                )
                .clickable(
                    enabled = primaryEnabled,
                    interactionSource = primaryInteraction,
                    indication = RemoteFocus,
                    onClick = {
                        controller.error = null
                        val existing = apkFileFor(context, release)
                        if (isDownloaded(context, release)) {
                            startInstall(existing)
                        } else {
                            controller.downloading = true
                            controller.handle?.setLocked(true)
                            controller.progress = 0f
                            scope.launch {
                                try {
                                    val result = downloadApk(context, release) { controller.progress = it }
                                    result.fold(
                                        onSuccess = { startInstall(it) },
                                        onFailure = {
                                            controller.error = it.message ?: "Tải xuống thất bại"
                                        },
                                    )
                                } finally {
                                    controller.downloading = false
                                    controller.handle?.setLocked(false)
                                }
                            }
                        }
                    },
                ),
        ) {
            if (controller.downloading && animatedProgress > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .fillMaxWidth(fraction = animatedProgress.coerceIn(0f, 1f))
                        .background(Color.White.copy(alpha = 0.28f)),
                )
            }
            Text(
                text = primaryLabel,
                fontFamily = InterFontFamily,
                color = if (controller.downloading) Color.White else Color(0xFF111113),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        val secondaryInteraction = remember { MutableInteractionSource() }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .pressBounce(secondaryInteraction, pressedScale = 0.97f)
                .fillMaxWidth()
                .height(44.dp)
                .graphicsLayer { alpha = if (controller.downloading) 0.45f else 1f }
                .clip(CircleShape)
                .background(MenuRowSurface)
                .clickable(
                    enabled = !controller.downloading,
                    interactionSource = secondaryInteraction,
                    indication = RemoteFocus,
                    onClick = { context.openReleaseUrl(release.htmlUrl) },
                ),
        ) {
            Text(
                text = "Mở trang phát hành",
                fontFamily = InterFontFamily,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.2).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        val snoozeInteraction = remember { MutableInteractionSource() }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .pressBounce(snoozeInteraction, pressedScale = 0.97f)
                .fillMaxWidth()
                .height(42.dp)
                .graphicsLayer { alpha = if (controller.downloading) 0.35f else 1f }
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.06f))
                .clickable(
                    enabled = !controller.downloading,
                    interactionSource = snoozeInteraction,
                    indication = RemoteFocus,
                    onClick = {
                        onSnooze()
                        controller.handle?.dismiss()
                    },
                ),
        ) {
            Text(
                text = "Nhắc lại sau 24 giờ",
                fontFamily = InterFontFamily,
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.2).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

private fun android.content.Context.openReleaseUrl(url: String) {
    runCatching {
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
