// WhatsNewPromptSheet.kt

package com.mochyy.mochymusic.ui.screens.update

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mochyy.mochymusic.BuildConfig
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.ui.component.LocalZoomOutOverlayState
import com.mochyy.mochymusic.ui.component.PopupSheetHandle
import com.mochyy.mochymusic.ui.component.PopupSheetState
import com.mochyy.mochymusic.ui.component.press3D
import com.mochyy.mochymusic.ui.component.rememberRevealSeenState
import com.mochyy.mochymusic.ui.component.revealOnAppear
import com.mochyy.mochymusic.ui.player.menu.MenuRowSurface
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import com.mochyy.mochymusic.ui.utils.AppMotion

private data class WhatsNewHighlight(
    @DrawableRes val icon: Int,
    val title: String,
    val summary: String,
)

private val V113Highlights = listOf(
    WhatsNewHighlight(
        icon = R.drawable.music_note,
        title = "Chia sẻ TikTok / Reels tự bắt nhạc bằng Shazam",
        summary = "Chia sẻ video TikTok, Reels hay Shorts vào Mochy để tự động tách tên bài hoặc dùng Shazam nhận diện giai điệu và phát ngay.",
    ),
    WhatsNewHighlight(
        icon = R.drawable.lyrics,
        title = "Nhận diện nhạc trên Thanh trạng thái & Lời nhạc Việt .lrc",
        summary = "Mở nhanh nhận diện nhạc từ ô Thanh trạng thái hay phím tắt biểu tượng app; bổ sung nguồn lời NhacCuaTui và Zing MP3 khớp từng câu.",
    ),
    WhatsNewHighlight(
        icon = R.drawable.cloud,
        title = "Nghe tiếp giữa các máy & Gửi Playlist .mochypl",
        summary = "Phát tiếp đúng giây đang nghe dở kèm hàng đợi từ thiết bị khác qua Mochy Cloud và chia sẻ từng playlist gọn nhẹ bằng tệp .mochypl.",
    ),
    WhatsNewHighlight(
        icon = R.drawable.sleep_timer,
        title = "Hẹn giờ giảm êm âm lượng 25s & Hiệu ứng 1:1 Mochy",
        summary = "Hẹn giờ ngủ hạ dần âm lượng mượt mà trong 25 giây cuối rồi tự trả mức cũ; đồng bộ hiệu ứng nhấn nảy, nghiêng 3D và chuyển động 1:1.",
    ),
)

fun PopupSheetState.showWhatsNewSheet(
    userName: String,
    onClosed: (() -> Unit)? = null,
): PopupSheetHandle {
    var handle: PopupSheetHandle? = null
    handle = showWithHandle(
        buttonBar = {
            SheetPrimaryButton(
                text = "Đã hiểu",
                onClick = { handle?.dismiss() ?: dismiss() },
            )
        },
        onClosed = onClosed,
    ) {
        WhatsNewSheetContent(userName = userName)
    }
    return handle
}

@Composable
fun WhatsNewPromptSheet(
    userName: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val overlay = LocalZoomOutOverlayState.current
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    DisposableEffect(overlay) {
        var disposed = false
        val handle = overlay.showWhatsNewSheet(
            userName = userName,
            onClosed = {
                if (!disposed) currentOnDismiss()
            },
        )
        onDispose {
            disposed = true
            handle.dismiss()
        }
    }
}

@Composable
private fun WhatsNewSheetContent(
    userName: String,
) {
    val currentVersion = remember { shortVersion(BuildConfig.VERSION_NAME) }
    val revealSeen = rememberRevealSeenState()

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
                "Có gì mới trên v$currentVersion"
            } else {
                "Có gì mới trên v$currentVersion, $userName!"
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            V113Highlights.forEachIndexed { index, item ->
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier
                        .fillMaxWidth()
                        .revealOnAppear(
                            key = "v113_highlight_$index",
                            seenState = revealSeen,
                            cascade = true,
                        )
                        .press3D(
                            maxTilt = 5f,
                            pressedScale = 0.98f,
                            enabled = AppMotion.enabled,
                        )
                        .clip(RoundedCornerShape(18.dp))
                        .background(MenuRowSurface)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFDADADA)),
                    ) {
                        Icon(
                            painter = painterResource(item.icon),
                            contentDescription = null,
                            tint = Color(0xFF1C1C1E),
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            fontFamily = InterFontFamily,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.2).sp,
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = item.summary,
                            fontFamily = InterFontFamily,
                            color = Color(0xFF9A9A9A),
                            fontSize = 12.5.sp,
                            lineHeight = 17.sp,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
    }
}
