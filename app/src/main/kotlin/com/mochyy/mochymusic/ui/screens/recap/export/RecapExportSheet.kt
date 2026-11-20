// RecapExportSheet.kt
//
// Mochy-styled dark frosted modal sheet for exporting Re'cap as a 60fps/30fps MP4 video with music,
// or as per-section high-resolution PNG images / standalone animated clips.

package com.mochyy.mochymusic.ui.screens.recap.export

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.mochyy.mochymusic.LocalDatabase
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.constants.LowEndModeKey
import com.mochyy.mochymusic.ui.player.share.song.export.ShareExportJob
import com.mochyy.mochymusic.ui.player.share.song.export.ShareTheme
import com.mochyy.mochymusic.ui.screens.recap.RecapStoryData
import com.mochyy.mochymusic.ui.screens.recap.recapBold
import com.mochyy.mochymusic.utils.rememberPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val SheetSurface = Color(0xFF141416)
private val SheetCard = Color(0xFF1F1F24)
private val SheetCardBorder = Color(0x26FFFFFF)
private val SheetMuted = Color(0xFFA8A8B3)
private val SheetAccent = Color(0xFF5EE89A)

@StringRes
internal fun RecapPart.titleRes(): Int = when (this) {
    RecapPart.JOURNEY -> R.string.rc_part_journey
    RecapPart.MONTH -> R.string.rc_part_month
    RecapPart.WEEKDAY -> R.string.rc_part_weekday
    RecapPart.TOP_ARTIST -> R.string.rc_part_top_artist
    RecapPart.TOP_SONG -> R.string.rc_part_top_song
    RecapPart.RECEIPT -> R.string.rc_part_receipt
    RecapPart.SUMMARY_CARD -> R.string.rc_part_summary_card
}

@StringRes
internal fun RecapPart.subtitleRes(): Int = when (this) {
    RecapPart.JOURNEY -> R.string.rc_part_journey_sub
    RecapPart.MONTH -> R.string.rc_part_month_sub
    RecapPart.WEEKDAY -> R.string.rc_part_weekday_sub
    RecapPart.TOP_ARTIST -> R.string.rc_part_top_artist_sub
    RecapPart.TOP_SONG -> R.string.rc_part_top_song_sub
    RecapPart.RECEIPT -> R.string.rc_part_receipt_sub
    RecapPart.SUMMARY_CARD -> R.string.rc_part_summary_card_sub
}

/** Standalone edge-to-edge Dialog wrapper so StatsScreen can open the Export sheet above the mini-player. */
@Composable
internal fun RecapExportDialog(
    data: RecapStoryData,
    payload: String,
    caption: String,
    initialTab: RecapExportTab = RecapExportTab.VIDEO,
    initialPart: RecapPart = RecapPart.SUMMARY_CARD,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.run {
                setWindowAnimations(0)
                setDimAmount(0f)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                @Suppress("DEPRECATION")
                clearFlags(
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                        WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
                )
                addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
                @Suppress("DEPRECATION")
                statusBarColor = android.graphics.Color.TRANSPARENT
                @Suppress("DEPRECATION")
                navigationBarColor = android.graphics.Color.TRANSPARENT
                if (Build.VERSION.SDK_INT >= 28) {
                    attributes = attributes.also {
                        it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
                if (Build.VERSION.SDK_INT >= 29) isNavigationBarContrastEnforced = false
                if (Build.VERSION.SDK_INT >= 30) {
                    attributes = attributes.also { it.fitInsetsTypes = 0 }
                }
            }
        }
        RecapExportSheetOverlay(
            visible = true,
            data = data,
            payload = payload,
            caption = caption,
            initialTab = initialTab,
            initialPart = initialPart,
            onDismiss = onDismiss,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecapExportSheetOverlay(
    visible: Boolean,
    data: RecapStoryData,
    payload: String,
    caption: String,
    initialTab: RecapExportTab,
    initialPart: RecapPart,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val shareTheme = remember(colorScheme, typography) { ShareTheme(colorScheme, typography) }
    val (lowEndMode) = rememberPreference(LowEndModeKey, defaultValue = false)

    val parts = remember(data) { availableRecapParts(data) }
    val can60Fps = remember(lowEndMode) { supportsRecap60Fps(lowEndMode) }
    val defaultConfig = remember(lowEndMode) { defaultRecapVideoConfig(lowEndMode) }

    var activeTab by remember(visible, initialTab) { mutableStateOf(initialTab) }
    var selectedPart by remember(visible, initialPart, parts) {
        mutableStateOf(if (initialPart in parts) initialPart else parts.first())
    }
    var use60Fps by remember(visible, defaultConfig) { mutableStateOf(defaultConfig.fps >= 60) }
    var includeAudio by remember { mutableStateOf(true) }
    var fullReceiptImage by remember { mutableStateOf(true) }

    var offscreenHost by remember { mutableStateOf<ViewGroup?>(null) }
    var exportCoroutine by remember { mutableStateOf<Job?>(null) }
    var exportJob by remember { mutableStateOf<ShareExportJob?>(null) }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var exportProgress by remember { mutableFloatStateOf(0f) }

    val isExporting = exportJob != null

    fun cancelExport() {
        exportJob?.cancelled = true
        exportCoroutine?.cancel()
        exportCoroutine = null
        exportJob = null
        exportStatus = null
        exportProgress = 0f
    }

    DisposableEffect(Unit) {
        onDispose { cancelExport() }
    }

    LaunchedEffect(exportJob) {
        val current = exportJob ?: return@LaunchedEffect
        while (exportJob === current) {
            exportProgress = current.progress.coerceIn(0f, 1f)
            delay(80L)
        }
    }

    BackHandler(enabled = visible) {
        if (isExporting) cancelExport() else onDismiss()
    }

    fun currentVideoConfig(): RecapVideoConfig {
        val fps = if (can60Fps && use60Fps) 60 else 30
        return RecapVideoConfig(
            width = defaultConfig.width,
            height = defaultConfig.height,
            fps = fps,
            includeAudio = includeAudio,
        )
    }

    fun shareCaptionText(): String = context.getString(R.string.rc_export_share_caption, data.monthName)

    fun launchExport(statusLabel: String, block: suspend (ViewGroup, ShareExportJob) -> Unit) {
        val host = offscreenHost
        if (host == null) {
            Toast.makeText(context, context.getString(R.string.rc_export_failed), Toast.LENGTH_SHORT).show()
            return
        }
        if (isExporting) return
        val job = ShareExportJob()
        exportJob = job
        exportStatus = statusLabel
        exportProgress = 0f
        exportCoroutine = scope.launch {
            try {
                block(host, job)
            } catch (_: CancellationException) {
                Toast.makeText(context, context.getString(R.string.rc_export_cancelled), Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(context, context.getString(R.string.rc_export_failed), Toast.LENGTH_SHORT).show()
            } finally {
                if (exportJob === job) {
                    exportJob = null
                    exportCoroutine = null
                    exportStatus = null
                    exportProgress = 0f
                }
            }
        }
    }

    fun runVideoExport(part: RecapPart?, shareAfter: Boolean) {
        val cfg = currentVideoConfig()
        val label = context.getString(R.string.rc_export_rendering_video, cfg.fps)
        launchExport(label) { host, job ->
            val result = exportRecapVideo(
                context = context,
                host = host,
                database = database,
                data = data,
                payload = payload,
                caption = caption,
                theme = shareTheme,
                config = cfg,
                part = part,
                songSource = playerConnection?.service?.shareDataSourceFactory(),
                job = job,
                saveToGallery = true,
            )
            val toastRes = when {
                result.fellBackToSilent -> R.string.rc_export_saved_silent_fallback
                result.savedToGallery -> R.string.rc_export_video_saved
                else -> R.string.rc_export_ready_to_share
            }
            Toast.makeText(context, context.getString(toastRes), Toast.LENGTH_SHORT).show()
            if (shareAfter) {
                shareRecapFile(context, result.file, result.mimeType, shareCaptionText())
            }
        }
    }

    fun runStillExport(part: RecapPart, shareAfter: Boolean) {
        val label = context.getString(R.string.rc_export_rendering_image)
        launchExport(label) { host, job ->
            job.progress = 0.25f
            val result = exportRecapStill(
                context = context,
                host = host,
                data = data,
                payload = payload,
                caption = caption,
                theme = shareTheme,
                part = part,
                fullReceiptImage = fullReceiptImage,
                saveToGallery = true,
            )
            job.progress = 1f
            val toastRes = if (result.savedToGallery) R.string.rc_export_image_saved else R.string.rc_export_ready_to_share
            Toast.makeText(context, context.getString(toastRes), Toast.LENGTH_SHORT).show()
            if (shareAfter) {
                shareRecapFile(context, result.file, result.mimeType, shareCaptionText())
            }
        }
    }

    fun runAllStillsExport() {
        val label = context.getString(R.string.rc_export_rendering_all_images, parts.size)
        launchExport(label) { host, job ->
            val results = exportAllRecapStills(
                context = context,
                host = host,
                data = data,
                payload = payload,
                caption = caption,
                theme = shareTheme,
                fullReceiptImage = fullReceiptImage,
                job = job,
            )
            val savedCount = results.count { it.savedToGallery }
            Toast.makeText(
                context,
                context.getString(R.string.rc_export_all_images_saved, savedCount),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Attached invisible host ViewGroup so OffscreenFrames has a live Window/Lifecycle owner
        if (visible) {
            DisposableEffect(Unit) {
                onDispose { offscreenHost = null }
            }
            AndroidView(
                factory = { ctx ->
                    FrameLayout(ctx).apply { visibility = View.INVISIBLE }.also { offscreenHost = it }
                },
                modifier = Modifier.size(1.dp),
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.68f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            if (!isExporting) onDismiss()
                        },
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                        .background(SheetSurface)
                        .border(
                            width = 1.dp,
                            color = SheetCardBorder,
                            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        )
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                // Drag handle
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(40.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.25f)),
                )
                Spacer(Modifier.height(14.dp))

                // Title row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.rc_export_sheet_title),
                            style = recapBold(20f, lineHeight = 24f, tracking = -0.4f),
                        )
                        Text(
                            text = stringResource(R.string.rc_export_sheet_subtitle, data.monthName),
                            style = recapBold(13f, lineHeight = 16f, tracking = 0f, weight = FontWeight.Medium).copy(color = SheetMuted),
                        )
                    }
                    Box(
                        modifier =
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.1f))
                                .clickable(enabled = !isExporting, onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.close),
                            contentDescription = stringResource(R.string.rc_export_close),
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 2-tab pill switcher
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(SheetCard)
                            .padding(4.dp),
                ) {
                    ExportTabPill(
                        label = stringResource(R.string.rc_export_tab_video),
                        iconRes = R.drawable.slow_motion_video,
                        selected = activeTab == RecapExportTab.VIDEO,
                        enabled = !isExporting,
                        onClick = { activeTab = RecapExportTab.VIDEO },
                        modifier = Modifier.weight(1f),
                    )
                    ExportTabPill(
                        label = stringResource(R.string.rc_export_tab_parts),
                        iconRes = R.drawable.image,
                        selected = activeTab == RecapExportTab.PARTS,
                        enabled = !isExporting,
                        onClick = { activeTab = RecapExportTab.PARTS },
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(14.dp))

                // Scrollable body
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                ) {
                    if (isExporting) {
                        ExportProgressCard(
                            status = exportStatus.orEmpty(),
                            progress = exportProgress,
                            onCancel = ::cancelExport,
                        )
                    } else if (activeTab == RecapExportTab.VIDEO) {
                        val fullPlan = remember(data) {
                            RecapExportTimeline.buildFullTimeline(
                                hasHeroArtist = data.heroArtist != null,
                                hasHeroSong = data.heroSong != null,
                                artistCount = data.artists.size,
                            )
                        }
                        val soundtrackSong = remember(data) { data.heroSong ?: data.heroArtist?.topSong }

                        // Full video overview card
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(SheetCard)
                                    .border(1.dp, SheetCardBorder, RoundedCornerShape(20.dp))
                                    .padding(16.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = stringResource(R.string.rc_export_full_video_title),
                                    style = recapBold(16f, lineHeight = 20f, tracking = -0.2f),
                                )
                                Box(
                                    modifier =
                                        Modifier
                                            .clip(CircleShape)
                                            .background(SheetAccent.copy(alpha = 0.16f))
                                            .padding(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.rc_export_duration_sec, (fullPlan.totalDurationMs / 1000L).toInt()),
                                        style = recapBold(12f, lineHeight = 14f, tracking = 0f).copy(color = SheetAccent),
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.rc_export_full_video_desc),
                                style = recapBold(13f, lineHeight = 18f, tracking = 0f, weight = FontWeight.Normal).copy(color = SheetMuted),
                            )

                            // Resolution / FPS selector
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            ) {
                                if (can60Fps) {
                                    OptionChip(
                                        label = stringResource(R.string.rc_export_quality_1080_60),
                                        selected = use60Fps,
                                        onClick = { use60Fps = true },
                                        modifier = Modifier.weight(1f),
                                    )
                                    OptionChip(
                                        label = stringResource(R.string.rc_export_quality_1080_30),
                                        selected = !use60Fps,
                                        onClick = { use60Fps = false },
                                        modifier = Modifier.weight(1f),
                                    )
                                } else {
                                    OptionChip(
                                        label = if (defaultConfig.width < 1080) {
                                            stringResource(R.string.rc_export_quality_720_30_lite)
                                        } else {
                                            stringResource(R.string.rc_export_quality_1080_30)
                                        },
                                        selected = true,
                                        onClick = {},
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }

                            // Soundtrack toggle
                            if (soundtrackSong != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(Color.White.copy(alpha = 0.06f))
                                            .clickable { includeAudio = !includeAudio }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(if (includeAudio) R.drawable.check else R.drawable.close),
                                        contentDescription = null,
                                        tint = if (includeAudio) SheetAccent else SheetMuted,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.rc_export_soundtrack_label),
                                            style = recapBold(13f, lineHeight = 16f, tracking = 0f),
                                        )
                                        Text(
                                            text = "${soundtrackSong.title} · ${soundtrackSong.artistName}",
                                            style = recapBold(12f, lineHeight = 15f, tracking = 0f, weight = FontWeight.Normal).copy(color = SheetMuted),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Text(
                                        text = stringResource(if (includeAudio) R.string.rc_export_on else R.string.rc_export_off),
                                        style = recapBold(12f, lineHeight = 14f, tracking = 0f).copy(
                                            color = if (includeAudio) SheetAccent else SheetMuted,
                                        ),
                                    )
                                }
                            }
                        }

                        // Primary video buttons
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            PrimaryActionButton(
                                label = stringResource(R.string.rc_export_save_video),
                                iconRes = R.drawable.download,
                                filled = true,
                                onClick = { runVideoExport(part = null, shareAfter = false) },
                                modifier = Modifier.weight(1f),
                            )
                            PrimaryActionButton(
                                label = stringResource(R.string.rc_export_share),
                                iconRes = R.drawable.ios_share,
                                filled = false,
                                onClick = { runVideoExport(part = null, shareAfter = true) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        // PARTS TAB: selectable chips for every available Recap moment
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            parts.forEach { part ->
                                val isSelected = part == selectedPart
                                Box(
                                    modifier =
                                        Modifier
                                            .clip(CircleShape)
                                            .background(if (isSelected) Color.White else SheetCard)
                                            .border(
                                                1.dp,
                                                if (isSelected) Color.White else SheetCardBorder,
                                                CircleShape,
                                            )
                                            .clickable { selectedPart = part }
                                            .padding(horizontal = 14.dp, vertical = 9.dp),
                                ) {
                                    Text(
                                        text = stringResource(part.titleRes()),
                                        style = recapBold(13f, lineHeight = 16f, tracking = -0.1f).copy(
                                            color = if (isSelected) Color(0xFF121212) else Color.White,
                                        ),
                                    )
                                }
                            }
                        }

                        // Selected part detail + actions
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(SheetCard)
                                    .border(1.dp, SheetCardBorder, RoundedCornerShape(20.dp))
                                    .padding(16.dp),
                        ) {
                            Text(
                                text = stringResource(selectedPart.titleRes()),
                                style = recapBold(16f, lineHeight = 20f, tracking = -0.2f),
                            )
                            Text(
                                text = stringResource(selectedPart.subtitleRes()),
                                style = recapBold(13f, lineHeight = 17f, tracking = 0f, weight = FontWeight.Normal).copy(color = SheetMuted),
                            )

                            if (selectedPart == RecapPart.RECEIPT) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    OptionChip(
                                        label = stringResource(R.string.rc_export_receipt_full),
                                        selected = fullReceiptImage,
                                        onClick = { fullReceiptImage = true },
                                        modifier = Modifier.weight(1f),
                                    )
                                    OptionChip(
                                        label = stringResource(R.string.rc_export_receipt_story),
                                        selected = !fullReceiptImage,
                                        onClick = { fullReceiptImage = false },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }

                            // Still PNG save / share
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                PrimaryActionButton(
                                    label = stringResource(R.string.rc_export_save_image),
                                    iconRes = R.drawable.download,
                                    filled = true,
                                    onClick = { runStillExport(selectedPart, shareAfter = false) },
                                    modifier = Modifier.weight(1f),
                                )
                                PrimaryActionButton(
                                    label = stringResource(R.string.rc_export_share_image),
                                    iconRes = R.drawable.ios_share,
                                    filled = false,
                                    onClick = { runStillExport(selectedPart, shareAfter = true) },
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            // Single-part animated video clip button
                            PrimaryActionButton(
                                label = stringResource(
                                    R.string.rc_export_part_video,
                                    if (can60Fps && use60Fps) 60 else 30,
                                ),
                                iconRes = R.drawable.slow_motion_video,
                                filled = false,
                                onClick = { runVideoExport(part = selectedPart, shareAfter = false) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        // Batch export all parts as PNGs
                        PrimaryActionButton(
                            label = stringResource(R.string.rc_export_save_all_images, parts.size),
                            iconRes = R.drawable.sparks,
                            filled = false,
                            onClick = ::runAllStillsExport,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportTabPill(
    label: String,
    iconRes: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier =
            modifier
                .height(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) Color.White else Color.Transparent)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 10.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (selected) Color(0xFF121212) else Color.White.copy(alpha = 0.78f),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = recapBold(13.5f, lineHeight = 16f, tracking = -0.1f).copy(
                color = if (selected) Color(0xFF121212) else Color.White.copy(alpha = 0.78f),
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .height(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f))
                .border(
                    width = 1.dp,
                    color = if (selected) SheetAccent.copy(alpha = 0.85f) else SheetCardBorder,
                    shape = RoundedCornerShape(12.dp),
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp),
    ) {
        Text(
            text = label,
            style = recapBold(12.5f, lineHeight = 15f, tracking = 0f).copy(
                color = if (selected) Color.White else SheetMuted,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PrimaryActionButton(
    label: String,
    iconRes: Int,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier =
            modifier
                .height(46.dp)
                .clip(RoundedCornerShape(23.dp))
                .background(if (filled) Color.White else Color.White.copy(alpha = 0.12f))
                .border(
                    width = if (filled) 0.dp else 1.dp,
                    color = if (filled) Color.Transparent else SheetCardBorder,
                    shape = RoundedCornerShape(23.dp),
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (filled) Color(0xFF121212) else Color.White,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = label,
            style = recapBold(14f, lineHeight = 17f, tracking = -0.1f).copy(
                color = if (filled) Color(0xFF121212) else Color.White,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ExportProgressCard(
    status: String,
    progress: Float,
    onCancel: () -> Unit,
) {
    val percent = (progress * 100f).roundToInt().coerceIn(0, 100)
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(SheetCard)
                .border(1.dp, SheetCardBorder, RoundedCornerShape(20.dp))
                .padding(18.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = status,
                style = recapBold(14.5f, lineHeight = 18f, tracking = -0.1f),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$percent%",
                style = recapBold(15f, lineHeight = 18f, tracking = 0f).copy(color = SheetAccent),
            )
        }
        LinearProgressIndicator(
            progress = { progress.coerceIn(0.02f, 1f) },
            color = SheetAccent,
            trackColor = Color.White.copy(alpha = 0.14f),
            strokeCap = StrokeCap.Round,
            modifier = Modifier.fillMaxWidth().height(7.dp),
        )
        Text(
            text = stringResource(R.string.rc_export_keep_open_hint),
            style = recapBold(12f, lineHeight = 16f, tracking = 0f, weight = FontWeight.Normal).copy(color = SheetMuted),
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(21.dp))
                    .background(Color.White.copy(alpha = 0.1f))
                    .clickable(onClick = onCancel),
        ) {
            Text(
                text = stringResource(R.string.rc_export_cancel),
                style = recapBold(14f, lineHeight = 17f, tracking = 0f),
            )
        }
    }
}
