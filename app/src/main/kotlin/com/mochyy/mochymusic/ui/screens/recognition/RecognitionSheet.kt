// RecognitionSheet.kt

package com.mochyy.mochymusic.ui.screens.recognition

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.LocalDatabase
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.LocalSyncUtils
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.cloud.MochyCloudSyncManager
import com.mochyy.mochymusic.db.MusicDatabase
import com.mochyy.mochymusic.db.entities.RecognitionHistory
import com.mochyy.mochymusic.db.entities.SongEntity
import com.mochyy.mochymusic.models.toMediaMetadata
import com.mochyy.mochymusic.playback.queues.YouTubeQueue
import com.mochyy.mochymusic.recognition.MusicRecognitionService
import com.mochyy.mochymusic.ui.component.PopupSheetHandle
import com.mochyy.mochymusic.ui.component.PopupSheetState
import com.mochyy.mochymusic.ui.component.RemoteFocus
import com.mochyy.mochymusic.ui.component.RoundedHighlight
import com.mochyy.mochymusic.ui.component.TopToaster
import com.mochyy.mochymusic.ui.component.damageBoundary
import com.mochyy.mochymusic.ui.component.press3D
import com.mochyy.mochymusic.ui.component.pressBounce
import com.mochyy.mochymusic.ui.component.rememberRevealSeenState
import com.mochyy.mochymusic.ui.component.revealOnAppear
import com.mochyy.mochymusic.ui.player.menu.MenuRowSurface
import com.mochyy.mochymusic.ui.screens.setup.onboarding.Onb
import com.mochyy.mochymusic.ui.screens.setup.onboarding.PageBadge
import com.mochyy.mochymusic.ui.screens.update.SheetPrimaryButton
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import com.mochyy.mochymusic.ui.utils.AppMotion
import com.mochyy.mochymusic.utils.LowEndModeManager
import com.mochyy.mochymusic.utils.SyncUtils
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.innertube.models.WatchEndpoint
import com.music.shazamkit.models.RecognitionResult
import com.music.shazamkit.models.RecognitionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val AccentGreen = Color(0xFF9CF58A)
private val AccentHeart = Color(0xFFFF5E7E)
private val AccentError = Color(0xFFE0736B)
private var activeRecognitionHandle: PopupSheetHandle? = null

fun PopupSheetState.showRecognitionSheet(
    autoStart: Boolean = true,
    onClosed: (() -> Unit)? = null,
): PopupSheetHandle {
    activeRecognitionHandle?.takeIf { !it.isClosing }?.let { return it }

    var handle: PopupSheetHandle? = null
    val created = showWithHandle(
        onClosed = {
            if (activeRecognitionHandle === handle) {
                activeRecognitionHandle = null
            }
            MusicRecognitionService.reset()
            onClosed?.invoke()
        },
        buttonBar = {
            SheetPrimaryButton(
                text = stringResource(R.string.done),
                onClick = { handle?.dismiss() },
            )
        },
    ) {
        RecognitionSheetContent(
            autoStart = autoStart,
            onDismiss = { handle?.dismiss() },
        )
    }
    handle = created
    activeRecognitionHandle = created
    return created
}

@Composable
private fun ColumnScope.RecognitionSheetContent(
    autoStart: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val syncUtils = LocalSyncUtils.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    val status by MusicRecognitionService.recognitionStatus.collectAsState()
    val history by remember(database) { database.recognitionHistory() }.collectAsState(initial = emptyList())
    val seenState = rememberRevealSeenState()

    var recognitionJob by remember { mutableStateOf<Job?>(null) }
    var permissionMissing by remember { mutableStateOf(!MusicRecognitionService.hasRecordPermission(context)) }
    var latestHistoryId by remember { mutableStateOf<Long?>(null) }
    var latestLiked by remember { mutableStateOf(false) }
    var resolvingPlayId by remember { mutableStateOf<String?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }

    fun startRecognition() {
        if (!MusicRecognitionService.hasRecordPermission(context)) {
            permissionMissing = true
            return
        }
        permissionMissing = false
        recognitionJob?.cancel()
        latestHistoryId = null
        latestLiked = false
        recognitionJob = scope.launch {
            val outcome = MusicRecognitionService.recognize(context)
            if (outcome is RecognitionStatus.Success) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                val ytId = outcome.result.youtubeVideoId?.takeIf { it.isNotBlank() }
                val (insertedId, initialLiked) = withContext(Dispatchers.IO) {
                    val alreadyLiked = ytId?.let { id ->
                        database.getSongByIdBlocking(id)?.song?.liked == true
                    } ?: false
                    val id = database.insert(outcome.result.toHistoryEntity().copy(liked = alreadyLiked))
                    id to alreadyLiked
                }
                latestHistoryId = insertedId
                latestLiked = initialLiked
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionMissing = !granted
        if (granted) {
            startRecognition()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recognitionJob?.cancel()
            activeRecognitionHandle = null
            MusicRecognitionService.reset()
        }
    }

    LaunchedEffect(autoStart) {
        if (!autoStart) return@LaunchedEffect
        delay(140L)
        if (MusicRecognitionService.hasRecordPermission(context)) {
            startRecognition()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val isListeningOrProcessing = status is RecognitionStatus.Listening || status is RecognitionStatus.Processing

    fun onOrbClicked() {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (isListeningOrProcessing) {
            recognitionJob?.cancel()
            recognitionJob = null
            MusicRecognitionService.reset()
            return
        }
        if (MusicRecognitionService.hasRecordPermission(context)) {
            startRecognition()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun playTrack(
        key: String,
        title: String,
        artist: String,
        youtubeVideoId: String?,
        fallbackThumb: String?,
        historyId: Long? = null,
    ) {
        if (resolvingPlayId != null) return
        scope.launch {
            resolvingPlayId = key
            try {
                val song = resolveYouTubeSong(title, artist, youtubeVideoId)
                if (song == null) {
                    TopToaster.error("Không tìm thấy \"$title\" trên YouTube Music.")
                    return@launch
                }
                if (historyId != null && youtubeVideoId.isNullOrBlank() && song.id.isNotBlank()) {
                    withContext(Dispatchers.IO) {
                        database.updateRecognitionHistoryYoutubeId(historyId, song.id)
                    }
                }
                val conn = playerConnection
                if (conn == null) {
                    TopToaster.error("Trình phát nhạc chưa sẵn sàng.")
                    return@launch
                }
                conn.playQueue(
                    YouTubeQueue(
                        WatchEndpoint(videoId = song.id),
                        song.toMediaMetadata(),
                    ),
                )
                TopToaster.show(
                    text = "Đang phát: ${song.title}",
                    thumbnail = song.thumbnail.ifBlank { fallbackThumb },
                    iconRes = R.drawable.music_note,
                )
                currentOnDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                TopToaster.error("Không thể phát bài hát này.")
            } finally {
                resolvingPlayId = null
            }
        }
    }

    fun toggleFavoriteTrack(
        historyId: Long?,
        currentLiked: Boolean,
        title: String,
        artist: String,
        youtubeVideoId: String?,
        coverUrl: String?,
        onUpdated: (Boolean) -> Unit,
    ) {
        val nextLiked = !currentLiked
        onUpdated(nextLiked)
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        scope.launch(Dispatchers.IO) {
            if (historyId != null) {
                database.updateRecognitionHistoryLiked(historyId, nextLiked)
            }
            val song = resolveYouTubeSong(title, artist, youtubeVideoId)
            if (song != null) {
                if (historyId != null && youtubeVideoId.isNullOrBlank() && song.id.isNotBlank()) {
                    database.updateRecognitionHistoryYoutubeId(historyId, song.id)
                }
                setSongLikedInDatabase(
                    context = context,
                    database = database,
                    syncUtils = syncUtils,
                    song = song,
                    liked = nextLiked,
                )
            }
            withContext(Dispatchers.Main) {
                if (nextLiked) {
                    TopToaster.show(
                        text = "Đã thêm \"$title\" vào Yêu thích",
                        thumbnail = song?.thumbnail ?: coverUrl,
                        iconRes = R.drawable.favorite,
                    )
                } else {
                    TopToaster.show(
                        text = "Đã bỏ \"$title\" khỏi Yêu thích",
                        thumbnail = song?.thumbnail ?: coverUrl,
                        iconRes = R.drawable.favorite_border,
                    )
                }
            }
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
            PageBadge(painterResource(R.drawable.mic), size = 44.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.recognize_music),
                    color = Color.White,
                    fontFamily = InterFontFamily,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.035).em,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Nhận diện nhanh bài hát đang phát quanh bạn",
                    color = Onb.Subtitle,
                    fontFamily = InterFontFamily,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
        }

        // Orb nhận diện 96.dp + sóng âm đồng tâm bọc damageBoundary()
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MenuRowSurface)
                .padding(vertical = 18.dp, horizontal = 16.dp),
        ) {
            RecognitionPulseOrb(
                isActive = isListeningOrProcessing,
                isSuccess = status is RecognitionStatus.Success,
                onClick = ::onOrbClicked,
            )

            Spacer(Modifier.height(10.dp))

            AnimatedContent(
                targetState = status,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
                label = "recognitionStatusText",
            ) { s ->
                val (headline, headlineColor) = when (s) {
                    RecognitionStatus.Ready -> stringResource(R.string.tap_to_recognize) to Color.White
                    RecognitionStatus.Listening -> stringResource(R.string.listening) to AccentGreen
                    RecognitionStatus.Processing -> stringResource(R.string.processing) to AccentGreen
                    is RecognitionStatus.Success -> stringResource(R.string.recognized_track) to AccentGreen
                    is RecognitionStatus.NoMatch -> s.message to Onb.Subtitle
                    is RecognitionStatus.Error -> s.message to AccentError
                }
                Text(
                    text = headline,
                    color = headlineColor,
                    fontFamily = InterFontFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    lineHeight = 19.sp,
                )
            }

            if (permissionMissing) {
                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompactActionChip(
                        label = "Cấp quyền micro",
                        highlighted = true,
                        onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    )
                    CompactActionChip(
                        label = "Mở cài đặt",
                        highlighted = false,
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null),
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                    )
                }
            }
        }

        // Thẻ kết quả nhận diện (press3D + haptic LongPress)
        val successStatus = status as? RecognitionStatus.Success
        if (successStatus != null) {
            val result = successStatus.result
            val resultRevealModifier = if (!LowEndModeManager.isActive && AppMotion.enabled) {
                Modifier.revealOnAppear(
                    key = "rec_result_${result.trackId}_${latestHistoryId ?: 0L}",
                    seenState = seenState,
                    cascade = true,
                )
            } else {
                Modifier
            }
            RecognizedResultCard(
                result = result,
                liked = latestLiked,
                loadingPlay = resolvingPlayId == "active_${result.trackId}",
                onPlayClick = {
                    playTrack(
                        key = "active_${result.trackId}",
                        title = result.title,
                        artist = result.artist,
                        youtubeVideoId = result.youtubeVideoId,
                        fallbackThumb = result.coverArtHqUrl ?: result.coverArtUrl,
                        historyId = latestHistoryId,
                    )
                },
                onFavoriteClick = {
                    toggleFavoriteTrack(
                        historyId = latestHistoryId,
                        currentLiked = latestLiked,
                        title = result.title,
                        artist = result.artist,
                        youtubeVideoId = result.youtubeVideoId,
                        coverUrl = result.coverArtHqUrl ?: result.coverArtUrl,
                        onUpdated = { latestLiked = it },
                    )
                },
                modifier = resultRevealModifier,
            )
        }

        // Danh sách lịch sử nhận diện gần đây
        if (history.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.recognition_history),
                    color = Color.White,
                    fontFamily = InterFontFamily,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
                    modifier = Modifier.weight(1f),
                )
                if (!confirmClearAll) {
                    CompactActionChip(
                        label = stringResource(R.string.clear),
                        highlighted = false,
                        onClick = { confirmClearAll = true },
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CompactActionChip(
                            label = "Hủy",
                            highlighted = false,
                            onClick = { confirmClearAll = false },
                        )
                        CompactActionChip(
                            label = "Xóa hết",
                            highlighted = true,
                            danger = true,
                            onClick = {
                                confirmClearAll = false
                                scope.launch(Dispatchers.IO) {
                                    database.clearRecognitionHistory()
                                }
                            },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                history.take(20).forEach { item ->
                    val revealMod = if (!LowEndModeManager.isActive && AppMotion.enabled) {
                        Modifier.revealOnAppear(
                            key = "rec_hist_${item.id}",
                            seenState = seenState,
                            cascade = true,
                        )
                    } else {
                        Modifier
                    }
                    RecognitionHistoryRow(
                        item = item,
                        loadingPlay = resolvingPlayId == "hist_${item.id}",
                        onClick = {
                            playTrack(
                                key = "hist_${item.id}",
                                title = item.title,
                                artist = item.artist,
                                youtubeVideoId = item.youtubeVideoId,
                                fallbackThumb = item.coverArtHqUrl ?: item.coverArtUrl,
                                historyId = item.id,
                            )
                        },
                        onToggleLike = {
                            toggleFavoriteTrack(
                                historyId = item.id,
                                currentLiked = item.liked,
                                title = item.title,
                                artist = item.artist,
                                youtubeVideoId = item.youtubeVideoId,
                                coverUrl = item.coverArtHqUrl ?: item.coverArtUrl,
                                onUpdated = {},
                            )
                        },
                        onDelete = {
                            scope.launch(Dispatchers.IO) {
                                database.deleteRecognitionHistoryById(item.id)
                            }
                        },
                        modifier = revealMod,
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun RecognitionPulseOrb(
    isActive: Boolean,
    isSuccess: Boolean,
    onClick: () -> Unit,
) {
    val lowEndOrStatic = LowEndModeManager.isActive || !AppMotion.enabled
    val orbInteraction = remember { MutableInteractionSource() }
    val orbBg by animateColorAsState(
        targetValue = when {
            isActive -> AccentGreen.copy(alpha = 0.20f)
            isSuccess -> AccentGreen.copy(alpha = 0.14f)
            else -> Onb.ButtonSecondary
        },
        animationSpec = tween(220),
        label = "orbBg",
    )
    val orbStroke by animateColorAsState(
        targetValue = when {
            isActive -> AccentGreen.copy(alpha = 0.78f)
            isSuccess -> AccentGreen.copy(alpha = 0.45f)
            else -> Color.White.copy(alpha = 0.18f)
        },
        animationSpec = tween(220),
        label = "orbStroke",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(148.dp),
    ) {
        if (isActive && !lowEndOrStatic) {
            ConcentricSoundWaves(
                color = AccentGreen,
                modifier = Modifier
                    .matchParentSize()
                    .damageBoundary(),
            )
        } else if (isActive) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .damageBoundary()
                    .border(2.dp, AccentGreen.copy(alpha = 0.42f), CircleShape),
            )
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .pressBounce(orbInteraction, pressedScale = 0.88f)
                .size(96.dp)
                .clip(CircleShape)
                .background(orbBg)
                .border(1.5.dp, orbStroke, CircleShape)
                .clickable(
                    interactionSource = orbInteraction,
                    indication = RemoteFocus,
                    onClick = onClick,
                ),
        ) {
            Icon(
                painter = painterResource(if (isActive) R.drawable.graphic_eq else R.drawable.mic),
                contentDescription = stringResource(R.string.recognize_music),
                tint = if (isActive || isSuccess) AccentGreen else Color.White,
                modifier = Modifier.size(38.dp),
            )
        }
    }
}

@Composable
private fun ConcentricSoundWaves(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "recognitionWaves")
    val wave1 = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wave1",
    )
    val wave2 = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(600),
        ),
        label = "wave2",
    )
    val wave3 = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(1200),
        ),
        label = "wave3",
    )

    Canvas(modifier = modifier) {
        val minRadius = 48.dp.toPx()
        val maxRadius = size.minDimension / 2f
        val strokePx = 2.dp.toPx()
        for (p in floatArrayOf(wave1.value, wave2.value, wave3.value)) {
            val radius = minRadius + (maxRadius - minRadius) * p
            val alpha = (1f - p).coerceIn(0f, 1f) * 0.42f
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = radius,
                style = Stroke(width = strokePx),
            )
        }
    }
}

@Composable
private fun RecognizedResultCard(
    result: RecognitionResult,
    liked: Boolean,
    loadingPlay: Boolean,
    onPlayClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enable3D = !LowEndModeManager.isActive && AppMotion.enabled
    Column(
        modifier = modifier
            .press3D(maxTilt = 7f, pressedScale = 0.97f, enabled = enable3D)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MenuRowSurface)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(22.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Onb.ButtonSecondary),
            ) {
                val artUrl = result.coverArtHqUrl ?: result.coverArtUrl
                if (!artUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = artUrl,
                        contentDescription = result.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.music_note),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.title,
                    color = Color.White,
                    fontFamily = InterFontFamily,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = result.artist,
                    color = Onb.Subtitle,
                    fontFamily = InterFontFamily,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val metaLine = listOfNotNull(
                    result.album?.takeIf { it.isNotBlank() },
                    result.releaseDate?.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (metaLine.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = metaLine,
                        color = Onb.Muted,
                        fontFamily = InterFontFamily,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            val playInteraction = remember { MutableInteractionSource() }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .pressBounce(playInteraction, pressedScale = 0.96f)
                    .weight(1f)
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f))
                    .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                    .clickable(
                        enabled = !loadingPlay,
                        interactionSource = playInteraction,
                        indication = RemoteFocus,
                        onClick = onPlayClick,
                    )
                    .padding(horizontal = 14.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.play),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (loadingPlay) "Đang tìm…" else "Phát ngay trên Mochy",
                    color = Color.White,
                    fontFamily = InterFontFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val favInteraction = remember { MutableInteractionSource() }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .pressBounce(favInteraction, pressedScale = 0.95f)
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (liked) AccentHeart.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.1f),
                    )
                    .clickable(
                        interactionSource = favInteraction,
                        indication = RemoteFocus,
                        onClick = onFavoriteClick,
                    )
                    .padding(horizontal = 14.dp),
            ) {
                Icon(
                    painter = painterResource(if (liked) R.drawable.favorite else R.drawable.favorite_border),
                    contentDescription = "Thêm vào Yêu thích",
                    tint = if (liked) AccentHeart else Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (liked) "Đã thích" else "Thêm vào Yêu thích",
                    color = if (liked) AccentHeart else Color.White,
                    fontFamily = InterFontFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun RecognitionHistoryRow(
    item: RecognitionHistory,
    loadingPlay: Boolean,
    onClick: () -> Unit,
    onToggleLike: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowInteraction = remember { MutableInteractionSource() }
    val likeInteraction = remember { MutableInteractionSource() }
    val deleteInteraction = remember { MutableInteractionSource() }
    val dateText = remember(item.recognizedAt) {
        runCatching {
            item.recognizedAt.format(DateTimeFormatter.ofPattern("dd/MM · HH:mm"))
        }.getOrDefault("")
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MenuRowSurface)
            .clickable(
                enabled = !loadingPlay,
                interactionSource = rowInteraction,
                indication = RoundedHighlight(18.dp),
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Onb.ButtonSecondary),
        ) {
            val cover = item.coverArtHqUrl ?: item.coverArtUrl
            if (!cover.isNullOrBlank()) {
                AsyncImage(
                    model = cover,
                    contentDescription = item.title,
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

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (loadingPlay) "Đang tìm \"${item.title}\"…" else item.title,
                color = Color.White,
                fontFamily = InterFontFamily,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (dateText.isNotBlank()) "${item.artist} · $dateText" else item.artist,
                color = Onb.Muted,
                fontFamily = InterFontFamily,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .pressBounce(likeInteraction, pressedScale = 0.85f)
                .size(34.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = likeInteraction,
                    indication = RoundedHighlight(17.dp),
                    onClick = onToggleLike,
                ),
        ) {
            Icon(
                painter = painterResource(if (item.liked) R.drawable.favorite else R.drawable.favorite_border),
                contentDescription = null,
                tint = if (item.liked) AccentHeart else Onb.Subtitle,
                modifier = Modifier.size(18.dp),
            )
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .pressBounce(deleteInteraction, pressedScale = 0.85f)
                .size(34.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = deleteInteraction,
                    indication = RoundedHighlight(17.dp),
                    onClick = onDelete,
                ),
        ) {
            Icon(
                painter = painterResource(R.drawable.close),
                contentDescription = stringResource(R.string.delete_from_history),
                tint = Onb.Muted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun CompactActionChip(
    label: String,
    highlighted: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .pressBounce(interaction, pressedScale = 0.95f)
            .clip(CircleShape)
            .background(
                when {
                    danger && highlighted -> AccentError.copy(alpha = 0.22f)
                    highlighted -> Onb.ButtonPrimary
                    else -> Color.White.copy(alpha = 0.1f)
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = RemoteFocus,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = if (danger && highlighted) AccentError else Color.White,
            fontFamily = InterFontFamily,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun RecognitionResult.toHistoryEntity(): RecognitionHistory = RecognitionHistory(
    trackId = trackId.ifBlank { "${title}_${artist}" },
    title = title,
    artist = artist,
    album = album,
    coverArtUrl = coverArtUrl,
    coverArtHqUrl = coverArtHqUrl,
    genre = genre,
    releaseDate = releaseDate,
    label = label,
    shazamUrl = shazamUrl,
    appleMusicUrl = appleMusicUrl,
    spotifyUrl = spotifyUrl,
    isrc = isrc,
    youtubeVideoId = youtubeVideoId,
    recognizedAt = LocalDateTime.now(),
    liked = false,
)

private suspend fun resolveYouTubeSong(
    title: String,
    artist: String,
    youtubeVideoId: String?,
): SongItem? = withContext(Dispatchers.IO) {
    if (!youtubeVideoId.isNullOrBlank()) {
        YouTube.queue(listOf(youtubeVideoId)).getOrNull()?.firstOrNull()?.let { return@withContext it }
    }
    val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ").trim()
    if (query.isNotBlank()) {
        YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
            .getOrNull()?.items?.filterIsInstance<SongItem>()?.firstOrNull()?.let { return@withContext it }
        YouTube.search(query, YouTube.SearchFilter.FILTER_VIDEO)
            .getOrNull()?.items?.filterIsInstance<SongItem>()?.firstOrNull()?.let { return@withContext it }
    }
    null
}

private suspend fun setSongLikedInDatabase(
    context: Context,
    database: MusicDatabase,
    syncUtils: SyncUtils,
    song: SongItem,
    liked: Boolean,
) {
    val metadata = song.toMediaMetadata()
    var updatedEntity: SongEntity? = null
    database.withTransaction {
        val existing = getSongByIdBlocking(metadata.id)
        if (existing == null) {
            if (liked) {
                insert(metadata) { entity ->
                    entity.toggleLike().also { updatedEntity = it }
                }
            }
        } else if (existing.song.liked != liked) {
            val toggled = existing.song.toggleLike()
            update(toggled)
            updatedEntity = toggled
        }
    }
    updatedEntity?.let { entity ->
        syncUtils.likeSong(entity)
        MochyCloudSyncManager.onFavoriteToggled(context.applicationContext)
    }
}
