// WhatsNewPromptSheet.kt

package com.mochyy.mochymusic.ui.screens.update

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.mochyy.mochymusic.core.updater.getInstalledLocketInfo
import com.mochyy.mochymusic.core.updater.openDuchiLocketApp
import com.mochyy.mochymusic.ui.player.menu.MenuRowSurface
import com.mochyy.mochymusic.ui.player.menu.MenuSurface
import com.mochyy.mochymusic.ui.theme.InterFontFamily

private val WhatsNewAccentGreen = Color(0xFF9CF58A)

private data class WhatsNewHighlight(
    @DrawableRes val icon: Int,
    val title: String,
    val summary: String,
)

private val V104Highlights = listOf(
    WhatsNewHighlight(
        icon = R.drawable.cloud,
        title = "Sửa lỗi Mochy Cloud & Avatar",
        summary = "Khắc phục triệt để lỗi đồng bộ mã 400 và tự đẩy avatar hiện có của bạn lên Cloud.",
    ),
    WhatsNewHighlight(
        icon = R.drawable.refresh,
        title = "Nhận diện cập nhật nhanh hơn",
        summary = "Kiểm tra OTA mỗi 15 phút, so khớp cả số build -bXXXX và nhớ 6 giờ khi chọn Để sau.",
    ),
    WhatsNewHighlight(
        icon = R.drawable.ic_duchi_locket,
        title = "Kết nối Duchi Locket",
        summary = "Tự nhận diện Duchi Locket đã cài và hỗ trợ cập nhật OTA trực tiếp trong Cài đặt.",
    ),
    WhatsNewHighlight(
        icon = R.drawable.music_note,
        title = "Đăng bài hát lên Duchi Locket",
        summary = "Nút 1 chạm trong trình phát & menu bài hát để phát ngay và mở khung Nhạc bên Locket.",
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

    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val popupScale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.94f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "whatsNewScale",
    )
    val popupAlpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "whatsNewAlpha",
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.68f * popupAlpha))
                .clickable(
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
                            .size(52.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MenuRowSurface)
                            .border(1.dp, WhatsNewAccentGreen.copy(alpha = 0.3f), RoundedCornerShape(16.dp)),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.sparks),
                            contentDescription = null,
                            tint = WhatsNewAccentGreen,
                            modifier = Modifier.size(26.dp),
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (userName.isBlank()) "Có gì mới trên Mochy?" else "$userName ơi, có gì mới?",
                            fontFamily = InterFontFamily,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(WhatsNewAccentGreen.copy(alpha = 0.16f))
                                .border(1.dp, WhatsNewAccentGreen.copy(alpha = 0.45f), CircleShape)
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                        ) {
                            Text(
                                text = "Phiên bản v$currentVersion",
                                fontFamily = InterFontFamily,
                                color = WhatsNewAccentGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.2).sp,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    V104Highlights.forEach { item ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(MenuRowSurface)
                                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(WhatsNewAccentGreen.copy(alpha = 0.14f)),
                            ) {
                                Icon(
                                    painter = painterResource(item.icon),
                                    contentDescription = null,
                                    tint = WhatsNewAccentGreen,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    fontFamily = InterFontFamily,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = (-0.2).sp,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = item.summary,
                                    fontFamily = InterFontFamily,
                                    color = Color.White.copy(alpha = 0.72f),
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (locketInstalled) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MenuRowSurface)
                                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                                .clickable {
                                    onDismiss()
                                    openDuchiLocketApp(context)
                                },
                        ) {
                            Text(
                                text = "Mở Duchi Locket",
                                fontFamily = InterFontFamily,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-0.3).sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(WhatsNewAccentGreen)
                            .clickable(onClick = onDismiss),
                    ) {
                        Text(
                            text = "Khám phá ngay",
                            fontFamily = InterFontFamily,
                            color = Color.Black,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.3).sp,
                        )
                    }
                }
            }
        }
    }
}
