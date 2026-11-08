// every page after the welcome screen as stateless composables, OnboardingFlow picks the page and wires the view model

package com.mochyy.mochymusic.ui.screens.setup.onboarding

import com.mochyy.mochymusic.ui.component.rememberDeviceTilt
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.remember
import com.mochyy.mochymusic.ui.component.BlurDirection
import com.mochyy.mochymusic.ui.component.GlassState
import com.mochyy.mochymusic.ui.component.ProgressiveGlassBackground
import com.mochyy.mochymusic.ui.component.glassRoot
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.importer.account.AccountImportState
import com.mochyy.mochymusic.importer.account.AccountService
import com.mochyy.mochymusic.importer.account.RemotePlaylist
import com.mochyy.mochymusic.importer.transfer.TransferManifest
import com.mochyy.mochymusic.viewmodels.TransferStep
import com.mochyy.mochymusic.viewmodels.YouTubeSyncChoices

private const val GoBack = "thôi, quay lại"

// ---- 1. import? ----

@Composable
internal fun ImportAskPage(onYes: () -> Unit, onSkip: () -> Unit) {
    OnboardingPage(
        badge = { PageBadge(painterResource(R.drawable.download)) },
        title = "Nhập nhạc",
        subtitle = "Bạn có muốn nhập nhạc từ các nền tảng khác như Spotify, Apple Music, Tidal, SoundCloud hoặc đồng bộ dữ liệu với YouTube Music không?",
        footer = {
            OnbButton("Có!", onClick = onYes)
            OnbButton("Bỏ qua", onClick = onSkip, style = OnbButtonStyle.Secondary)
        },
    )
}

// ---- 2. which kind ----

@Composable
internal fun ImportModePage(
    onPreviousData: () -> Unit,
    onImport: () -> Unit,
    onBackup: () -> Unit,
    onBack: () -> Unit,
    backupError: String?,
) {
    OnboardingPage(
        badge = { PageBadge(painterResource(R.drawable.download)) },
        title = "Nhập nhạc",
        subtitle = "bạn muốn làm gì?",
        footer = { OnbButton(GoBack, onClick = onBack, style = OnbButtonStyle.Secondary) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OnbChoiceCard(
                icon = painterResource(R.drawable.restore),
                title = "Nhập dữ liệu trước đây",
                description = "tệp .zip xuất từ ứng dụng cũ, gồm thư viện, lịch sử, nhạc đã tải và cài đặt",
                onClick = onPreviousData,
            )
            OnbChoiceCard(
                icon = painterResource(R.drawable.playlist_add),
                title = "Nhập nhạc",
                description = "nhập nhạc, danh sách phát và album từ các dịch vụ như Apple Music, Spotify, Tidal và YouTube Music",
                onClick = onImport,
            )
            OnbChoiceCard(
                icon = painterResource(R.drawable.backup),
                title = "Nhập từ bản sao lưu",
                description = "nếu bạn đã có sẵn một bản sao lưu Mochy",
                onClick = onBackup,
            )
            if (backupError != null) {
                Text(backupError, color = Color(0xFFFF8A80), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ---- 2b. bringing previous data over ----

@Composable
internal fun RestoringPage(step: TransferStep, manifest: TransferManifest?) {
    val done = step is TransferStep.Done
    val progress = if (done) 1f else (step as? TransferStep.Working)?.progress ?: 0f
    OnboardingPage(
        badge = { PageBadge(painterResource(if (done) R.drawable.check else R.drawable.restore)) },
        title = if (done) "Đã hoàn tất!" else "Đang chuyển nhạc của bạn sang",
        subtitle = when {
            done -> "Mochy sẽ khởi động lại một lần để hoàn tất. Hẹn gặp lại ngay nhé!"
            manifest != null -> "${manifest.songs} bài hát, ${manifest.playlists} danh sách phát và " +
                "${manifest.plays} lượt nghe từ ứng dụng cũ của bạn."
            else -> "Đang đọc tệp dữ liệu…"
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LinearProgressIndicator(
                progress = { progress },
                color = Color.White,
                trackColor = Onb.Card,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
            )
            Text(
                if (done) "Đang khởi động lại…" else "${(progress * 100).toInt()}% · vui lòng giữ Mochy mở, ứng dụng sẽ tự khởi động lại khi hoàn tất",
                color = Onb.Subtitle,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun countOf(count: Int, thing: String): String = "%,d %s".format(count, if (count == 1) thing else "${thing}s")

// ---- 3. provider ----

@Composable
internal fun ProviderListPage(onPick: (AccountService) -> Unit, onBack: () -> Unit) {
    OnboardingPage(
        badge = { PageBadge(painterResource(R.drawable.library_music)) },
        title = "Chọn dịch vụ",
        footer = { OnbButton(GoBack, onClick = onBack, style = OnbButtonStyle.Secondary) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                AccountService.SPOTIFY to "Bài hát đã thích và danh sách phát",
                AccountService.APPLE_MUSIC to "Danh sách phát thư viện và Bài hát yêu thích",
                AccountService.YOUTUBE_MUSIC to "Nhạc đã thích và danh sách phát · hỗ trợ đồng bộ",
                AccountService.TIDAL to "Bài hát của tôi và danh sách phát của bạn",
            ).forEach { (service, description) ->
                OnbCard(modifier = Modifier.fillMaxWidth(), onClick = { onPick(service) }, shape = RoundedCornerShape(20.dp)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProviderSquircle(service, size = 40.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(service.label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(description, color = Onb.Muted, fontSize = 12.sp, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

// ---- 4. setup (instructions, the browser sheet opens over this) ----

@Composable
internal fun ProviderSetupPage(
    service: AccountService,
    state: AccountImportState?,
    browserOpen: Boolean,
    onOpenBrowser: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    OnboardingPage(
        badge = { ProviderSquircle(service, size = 40.dp) },
        title = "Thiết lập ${service.label}",
        subtitle = "Bạn sẽ đăng nhập vào tài khoản của mình. Chúng tôi không bao giờ biết mật khẩu của bạn. Một trang web sẽ mở ra.",
        footer = {
            when (state) {
                is AccountImportState.Failed -> OnbButton(if (state.signedOut) "Đăng nhập lại" else "Thử lại", onClick = onRetry)
                is AccountImportState.Loading, is AccountImportState.Choosing -> Unit
                else -> if (!browserOpen) OnbButton("Mở trang đăng nhập", onClick = onOpenBrowser)
            }
            OnbButton(GoBack, onClick = onBack, style = OnbButtonStyle.Secondary)
        },
    ) {
        when (state) {
            is AccountImportState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(Modifier.width(12.dp))
                Text(state.label, color = Onb.Subtitle, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            is AccountImportState.Failed -> Text(state.message, color = Color(0xFFFF8A80), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            else -> Text(
                "Đăng nhập trên trang chính thức của ${service.label}. Sau khi đăng nhập xong, trang sẽ tự đóng và danh sách phát của bạn sẽ hiển thị tại đây.",
                color = Onb.Muted,
                fontSize = 14.sp,
            )
        }
    }
}

// ---- 5. what to sync or import ----

@Composable
internal fun WhatToSyncPage(
    service: AccountService,
    state: AccountImportState?,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onConfirm: () -> Unit,
    onCancelReading: () -> Unit,
    onBack: () -> Unit,
) {
    val choosing = state as? AccountImportState.Choosing
    val reading = state as? AccountImportState.Reading
    val selectedCount = choosing?.selected?.size ?: 0
    OnboardingPage(
        badge = { ProviderSquircle(service, size = 40.dp) },
        title = "Chọn mục muốn nhập",
        subtitle = choosing?.accountName?.let { "Đã đăng nhập là $it" },
        scrollable = false,
        footer = {
            if (reading != null) {
                OnbButton("Dừng", onClick = onCancelReading, style = OnbButtonStyle.Secondary)
            } else {
                OnbButton(
                    text = when {
                        selectedCount == 0 -> "Chọn mục muốn nhập"
                        service == AccountService.YOUTUBE_MUSIC -> "Tiếp theo"
                        else -> "Nhập $selectedCount mục"
                    },
                    enabled = selectedCount > 0,
                    onClick = onConfirm,
                )
                OnbButton(GoBack, onClick = onBack, style = OnbButtonStyle.Secondary)
            }
        },
    ) {
        if (reading != null) {
            Text("${reading.label} (${reading.done + 1} / ${reading.total})", color = Onb.Subtitle, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { reading.done.toFloat() / reading.total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                trackColor = Onb.Card,
            )
            return@OnboardingPage
        }
        if (choosing == null) return@OnboardingPage
        val allSelected = selectedCount == choosing.playlists.size && selectedCount > 0
        Text(
            text = if (allSelected) "Bỏ chọn tất cả" else "Chọn tất cả",
            color = Onb.Subtitle,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = if (allSelected) onSelectNone else onSelectAll)
                .padding(vertical = 6.dp),
        )
        Spacer(Modifier.height(10.dp))
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val columns = if (maxWidth >= 480.dp) 3 else 2
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (choosing.playlists.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("Chưa có gì trong tài khoản này.", color = Onb.Muted, fontSize = 14.sp)
                    }
                }
                items(choosing.playlists, key = { it.id }) { playlist ->
                    CoverTile(playlist = playlist, selected = playlist.id in choosing.selected, onClick = { onToggle(playlist.id) })
                }
            }
        }
    }
}

@Composable
private fun CoverTile(playlist: RemotePlaylist, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .graphicsLayer {
                    val s = if (selected) 0.94f else 1f
                    scaleX = s
                    scaleY = s
                }
                .clip(RoundedCornerShape(20.dp))
                .background(Onb.Card)
                .then(if (selected) Modifier.border(3.dp, Color.White, RoundedCornerShape(20.dp)) else Modifier),
        ) {
            when {
                playlist.isLiked -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.linearGradient(listOf(Color(0xFF5B5B66), Color(0xFF1F1F24)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.favorite), contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
                }
                playlist.coverUrl != null -> AsyncImage(
                    model = playlist.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        playlist.name.take(1).uppercase(),
                        color = Onb.Muted,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.check), contentDescription = "Đã chọn", tint = Color.Black, modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(playlist.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val detail = listOfNotNull(playlist.trackCount?.let { "$it bài hát" }, playlist.subtitle).joinToString(" · ")
        if (detail.isNotEmpty()) Text(detail, color = Onb.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---- 6. youtube only: also keep it in sync? ----

@Composable
internal fun SyncOptionsPage(
    choices: YouTubeSyncChoices,
    onChange: (YouTubeSyncChoices) -> Unit,
    reading: AccountImportState.Reading?,
    onSync: () -> Unit,
    onJustImport: () -> Unit,
    onBack: () -> Unit,
) {
    OnboardingPage(
        badge = { ProviderSquircle(AccountService.YOUTUBE_MUSIC, size = 40.dp) },
        title = "Đồng thời duy trì đồng bộ?",
        subtitle = "YouTube Music có thể đồng bộ hai chiều với Mochy: danh sách phát, bài hát đã thích và lịch sử nghe.",
        footer = {
            if (reading != null) {
                Text(
                    "${reading.label} (${reading.done + 1} / ${reading.total})",
                    color = Onb.Subtitle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                OnbButton("Nhập & đồng bộ", onClick = onSync)
                OnbButton("Chỉ nhập nhạc", onClick = onJustImport, style = OnbButtonStyle.Secondary)
                OnbButton(GoBack, onClick = onBack, style = OnbButtonStyle.Secondary)
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OnbToggleCard(
                title = "Đồng bộ thư viện khi mở ứng dụng",
                description = "Bài hát đã thích, thư viện và các danh sách phát bạn đã chọn mỗi khi mở Mochy",
                checked = choices.syncOnLaunch,
                onCheckedChange = { onChange(choices.copy(syncOnLaunch = it)) },
            )
            OnbToggleCard(
                title = "Tạo danh sách phát mới trên YouTube",
                description = "Danh sách phát bạn tạo tại đây cũng sẽ được tạo trên YouTube",
                checked = choices.newPlaylistsToYouTube,
                onCheckedChange = { onChange(choices.copy(newPlaylistsToYouTube = it)) },
            )
            OnbToggleCard(
                title = "Gửi lượt phát vào lịch sử YouTube",
                description = "Những gì bạn nghe tại đây sẽ hiển thị trong lịch sử YouTube",
                checked = choices.reportPlays,
                onCheckedChange = { onChange(choices.copy(reportPlays = it)) },
            )
            OnbToggleCard(
                title = "Đưa lịch sử YouTube về đây",
                description = "Lượt nghe từ các thiết bị khác sẽ được gộp vào lịch sử Mochy",
                checked = choices.copyHistory,
                onCheckedChange = { onChange(choices.copy(copyHistory = it)) },
            )
        }
    }
}

// ---- 7. profile ----

@Composable
internal fun ProfilePage(
    username: String,
    onUsernameChange: (String) -> Unit,
    photo: Any?,
    onPhotoClick: () -> Unit,
    cardNumber: String,
    joinedText: String,
    onNext: () -> Unit,
) {
    OnboardingPage(
        badge = { PageBadge(painterResource(R.drawable.person)) },
        title = "Hãy tạo hồ sơ của bạn!",
        footer = {
            Text(
                "Mochy không bao giờ lưu trữ dữ liệu của bạn trực tuyến!",
                color = Onb.Faint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp),
            )
            OnbButton("Tiếp theo", onClick = onNext, enabled = username.isNotBlank())
        },
    ) {
        Text("tên người dùng của bạn là gì?", color = Onb.Muted, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        OnbTextField(value = username, onValueChange = { onUsernameChange(it.take(32)) }, placeholder = "tên người dùng")
        Spacer(Modifier.height(24.dp))
        Text("Đặt ảnh đại diện tại đây!", color = Onb.Muted, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        val deviceTilt = rememberDeviceTilt()
        ProfileIdCard(
            username = username,
            photo = photo,
            cardNumber = cardNumber,
            joinedText = joinedText,
            onPhotoClick = onPhotoClick,
            modifier = Modifier.graphicsLayer {
                rotationX = deviceTilt.rotationX
                rotationY = deviceTilt.rotationY
                cameraDistance = 14f * density
            },
        )
    }
}

@Composable
internal fun BlurWarningPage(
    disableBlur: Boolean,
    onDisableBlurChange: (Boolean) -> Unit,
    detected: String?,
    onContinue: () -> Unit,
) {
    val glass = remember { GlassState() }
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val side = rememberSidePadding(maxWidth)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .glassRoot(glass),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = Onb.MaxContentWidth)
                    .fillMaxSize()
                    .padding(top = Onb.TopClearance + 18.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = side)) {
                    PageBadge(painterResource(R.drawable.warning))
                    Spacer(Modifier.height(22.dp))
                    Text("Lưu ý", color = Onb.Title, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = OnbTitleTracking)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Thiết bị của bạn có thể hơi yếu để chạy hiệu ứng làm mờ. Bạn có muốn tắt làm mờ không? Bạn luôn có thể đổi lại trong Cài đặt.",
                        color = Onb.Subtitle,
                        fontSize = 18.sp,
                        lineHeight = 23.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = OnbSubtitleTracking,
                    )
                    Spacer(Modifier.height(22.dp))
                    OnbToggleCard(
                        title = "Tắt hiệu ứng làm mờ",
                        description = detected?.let { "Đo được: $it" } ?: "",
                        checked = disableBlur,
                        onCheckedChange = onDisableBlurChange,
                        leading = { Box(Modifier.size(22.dp).clip(CircleShape).background(Onb.Badge)) },
                    )
                    Spacer(Modifier.height(18.dp))
                }
                Image(
                    painter = painterResource(R.drawable.onboarding_phone_internals),
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = side + 6.dp),
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(132.dp + navBar),
        ) {
            ProgressiveGlassBackground(
                state = glass,
                maxBlurRadius = { 30f },
                direction = BlurDirection.TopToBottom,
                steps = 3,
                modifier = Modifier.matchParentSize(),
            )
            val progressiveBar = com.mochyy.mochymusic.ui.component.rememberProgressiveBlur()
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        if (progressiveBar) {
                            Brush.verticalGradient(0f to Color.Transparent, 0.5f to Onb.Page.copy(alpha = 0.7f), 1f to Onb.Page)
                        } else {
                            androidx.compose.ui.graphics.SolidColor(Onb.Page.copy(alpha = 0.6f))
                        },
                    ),
            )
            OnbButton(
                text = if (disableBlur) "tiếp tục" else "không, tiếp tục",
                onClick = onContinue,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = Onb.MaxContentWidth)
                    .navigationBarsPadding()
                    .padding(horizontal = side, vertical = 18.dp),
            )
        }
    }
}

// ---- 10. last one ----

@Composable
internal fun LastOnePage(
    autoCrossfade: Boolean,
    onAutoCrossfadeChange: (Boolean) -> Unit,
    aiArtistFilter: Boolean,
    onAiArtistFilterChange: (Boolean) -> Unit,
    onNext: () -> Unit,
) {
    OnboardingPage(
        badge = { PageBadge(painterResource(R.drawable.tune)) },
        title = "Bước cuối cùng",
        subtitle = "Tắt bất kỳ tính năng nào bạn không thích",
        footer = { OnbButton("tiếp theo", onClick = onNext) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OnbToggleCard(
                title = "Chuyển bài mượt tự động (Auto Crossfade)",
                description = "Tự động chuyển mượt giữa các bài hát bằng cách phân tích cao độ, nhịp độ để chọn thời điểm hoàn hảo",
                checked = autoCrossfade,
                onCheckedChange = onAutoCrossfadeChange,
                header = { CrossfadeBanner() },
            )
            OnbToggleCard(
                title = "Bộ lọc nghệ sĩ AI",
                description = "Tắt tính năng này có thể khiến xuất hiện nhạc rác do AI tạo ra trong đề xuất",
                checked = aiArtistFilter,
                onCheckedChange = onAiArtistFilterChange,
            )
        }
    }
}
