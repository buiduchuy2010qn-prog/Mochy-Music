// WhatsNewPromptSheet.kt

package com.mochyy.mochymusic.ui.screens.update

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mochyy.mochymusic.BuildConfig
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.core.updater.getInstalledLocketInfo
import com.mochyy.mochymusic.core.updater.openDuchiLocketApp
import com.mochyy.mochymusic.ui.component.RemoteFocus
import com.mochyy.mochymusic.ui.player.menu.MenuRowSurface
import com.mochyy.mochymusic.ui.player.menu.MenuSheetSurface

private data class WhatsNewHighlight(
    @DrawableRes val icon: Int,
    val title: String,
    val summary: String,
)

private val V110Highlights = listOf(
    WhatsNewHighlight(
        icon = R.drawable.lyrics,
        title = "Hợp nhất Mochy 8.4.2 gốc mới nhất",
        summary = "Lời bài hát nổi (Floating Lyrics), nền động Spotify Canvas, hàng đợi kiểu Spotify (FIFO), kết nối tới 10 thiết bị & ý nghĩa bài hát.",
    ),
    WhatsNewHighlight(
        icon = R.drawable.equalizer,
        title = "Android Auto, TV, Xuất FLAC & Tối ưu RAM",
        summary = "Hỗ trợ Android Auto + Hey Google, sửa metadata & nhập lời bài hát, xuất nhạc lossless FLAC, chọn thư mục lưu, Innertube X & giảm RAM chạy nền.",
    ),
    WhatsNewHighlight(
        icon = R.drawable.smartphone,
        title = "Hỗ trợ đầy đủ máy 32-bit & 64-bit",
        summary = "Bổ sung gói Universal (armeabi-v7a) cho Android 7.0+ như Samsung Galaxy J6 và tự động chọn đúng bản chip khi cập nhật OTA.",
    ),
    WhatsNewHighlight(
        icon = R.drawable.ic_duchi_locket,
        title = "Mochy Cloud & Duchi Locket",
        summary = "Giữ nguyên giao diện Mochy gốc 100%, đồng bộ đám mây Yêu thích/Playlist/Lịch sử/Avatar & chia sẻ 1 chạm sang Duchi Locket.",
    ),
)

@Composable
fun WhatsNewPromptSheet(
    userName: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val currentVersion = remember { shortVersion(BuildConfig.VERSION_NAME) }
    val locketInstalled = remember(context) { getInstalledLocketInfo(context) != null }

    MenuSheetSurface(
        onDismiss = onDismiss,
        modifier = modifier,
        wrapHeight = true,
        fullDetent = 0.92f,
    ) { _ ->
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(top = 12.dp, bottom = 24.dp)
                .animateContentSize()
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(MenuRowSurface)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_mochy_mark),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(52.dp),
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = if (userName.isBlank()) {
                    "Có gì mới trên v$currentVersion"
                } else {
                    "Có gì mới trên v$currentVersion, $userName!"
                },
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                V110Highlights.forEach { item ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier
                            .fillMaxWidth()
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
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = item.summary,
                                color = Color(0xFF9A9A9A),
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            if (locketInstalled) {
                WhatsNewSheetButton(
                    label = "Mở Duchi Locket",
                    onClick = {
                        onDismiss()
                        openDuchiLocketApp(context)
                    },
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            WhatsNewSheetButton(
                label = "Đã hiểu",
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun WhatsNewSheetButton(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MenuRowSurface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = RemoteFocus,
                onClick = onClick,
            )
            .padding(vertical = 14.dp),
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
