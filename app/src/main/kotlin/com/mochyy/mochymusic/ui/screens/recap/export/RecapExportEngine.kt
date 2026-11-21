// RecapExportEngine.kt
//
// Deterministic offscreen Compose -> PNG / 60fps MP4 export engine for Mochy Re'cap.
// Reuses Mochy's proven OffscreenFrames + ForegroundEffect + Media3 Transformer + ShareAudioFade
// pipeline so exported videos and per-part images match the live Mochy Recap pixel-for-pixel.

package com.mochyy.mochymusic.ui.screens.recap.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.constants.BlurQuality
import com.mochyy.mochymusic.db.MusicDatabase
import com.mochyy.mochymusic.extensions.toMediaItem
import com.mochyy.mochymusic.models.toMediaMetadata
import com.mochyy.mochymusic.ui.component.LocalBlurQuality
import com.mochyy.mochymusic.ui.player.share.song.clippedItem
import com.mochyy.mochymusic.ui.player.share.song.export.ForegroundEffect
import com.mochyy.mochymusic.ui.player.share.song.export.FrameImage
import com.mochyy.mochymusic.ui.player.share.song.export.OffscreenFrames
import com.mochyy.mochymusic.ui.player.share.song.export.ShareAudioFade
import com.mochyy.mochymusic.ui.player.share.song.export.ShareEncoders
import com.mochyy.mochymusic.ui.player.share.song.export.ShareExportJob
import com.mochyy.mochymusic.ui.player.share.song.export.ShareMediaSources
import com.mochyy.mochymusic.ui.player.share.song.export.ShareQuality
import com.mochyy.mochymusic.ui.player.share.song.export.ShareTheme
import com.mochyy.mochymusic.ui.player.share.song.export.saveMediaToGallery
import com.mochyy.mochymusic.ui.player.share.song.export.shareFileUri
import com.mochyy.mochymusic.ui.screens.recap.P_ARTIST
import com.mochyy.mochymusic.ui.screens.recap.P_ARTISTS
import com.mochyy.mochymusic.ui.screens.recap.P_END
import com.mochyy.mochymusic.ui.screens.recap.P_HEY
import com.mochyy.mochymusic.ui.screens.recap.P_JOURNEY
import com.mochyy.mochymusic.ui.screens.recap.P_MELODY
import com.mochyy.mochymusic.ui.screens.recap.P_MONTH
import com.mochyy.mochymusic.ui.screens.recap.P_PERSONA
import com.mochyy.mochymusic.ui.screens.recap.P_RECEIPT
import com.mochyy.mochymusic.ui.screens.recap.P_SONG
import com.mochyy.mochymusic.ui.screens.recap.P_SONGS
import com.mochyy.mochymusic.ui.screens.recap.P_STREAK
import com.mochyy.mochymusic.ui.screens.recap.P_WEEKDAY
import com.mochyy.mochymusic.ui.screens.recap.RecapPalette
import com.mochyy.mochymusic.ui.screens.recap.RecapPalettes
import com.mochyy.mochymusic.ui.screens.recap.RecapStoryData
import com.mochyy.mochymusic.ui.screens.recap.RecapStoryScene
import com.mochyy.mochymusic.ui.screens.recap.RecapTime
import com.mochyy.mochymusic.ui.screens.recap.StoryGeometry
import com.mochyy.mochymusic.ui.screens.recap.StoryState
import com.mochyy.mochymusic.ui.screens.recap.displayName
import com.mochyy.mochymusic.ui.screens.recap.lerpF
import com.mochyy.mochymusic.ui.screens.setup.onboarding.IdCardAspect
import com.mochyy.mochymusic.ui.utils.resize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.DayOfWeek
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.math.roundToInt

private const val RecapExportDir = "recap_export"
private const val Tag = "RecapExport"
private const val ExportVirtualWidthDp = 450f
private const val ExportTopInsetDp = 24f
private const val StillWarmUpFrames = 4
private const val VideoWarmUpFrames = 3

internal data class RecapVideoConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val includeAudio: Boolean = true,
)

internal data class RecapAssets(
    val artistPalette: RecapPalette,
    val songPalette: RecapPalette,
)

internal data class RecapExportResult(
    val file: File,
    val mimeType: String,
    val audioIncluded: Boolean = false,
    val fellBackToSilent: Boolean = false,
    val savedToGallery: Boolean = false,
)

internal fun availableRecapParts(data: RecapStoryData): List<RecapPart> =
    availableRecapParts(hasHeroArtist = data.heroArtist != null, hasHeroSong = data.heroSong != null)

internal fun recapPartForStoryPage(page: Int, data: RecapStoryData): RecapPart = when (page) {
    P_HEY, P_JOURNEY -> RecapPart.JOURNEY
    P_MONTH -> RecapPart.MONTH
    P_WEEKDAY -> RecapPart.WEEKDAY
    P_STREAK -> RecapPart.STREAK
    P_ARTISTS, P_ARTIST -> if (data.heroArtist != null) RecapPart.TOP_ARTIST else RecapPart.STREAK
    P_SONGS, P_MELODY, P_SONG -> if (data.heroSong != null) RecapPart.TOP_SONG else RecapPart.PERSONA
    P_PERSONA -> RecapPart.PERSONA
    P_RECEIPT -> RecapPart.RECEIPT
    P_END -> RecapPart.SUMMARY_CARD
    else -> RecapPart.SUMMARY_CARD
}

internal fun selectSoundtrackSongId(data: RecapStoryData, part: RecapPart?): String? =
    selectSoundtrackSongId(
        heroSongId = data.heroSong?.id,
        heroArtistTopSongId = data.heroArtist?.topSong?.id,
        part = part,
    )

internal fun supportsRecap60Fps(lowEndMode: Boolean): Boolean =
    !lowEndMode && Build.VERSION.SDK_INT > Build.VERSION_CODES.O_MR1 && ShareEncoders.canEncode(ShareQuality.FullHd, 60)

internal fun defaultRecapVideoConfig(lowEndMode: Boolean): RecapVideoConfig = when {
    lowEndMode || Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1 ->
        RecapVideoConfig(width = 720, height = 1280, fps = 30, includeAudio = true)
    ShareEncoders.canEncode(ShareQuality.FullHd, 60) ->
        RecapVideoConfig(width = 1080, height = 1920, fps = 60, includeAudio = true)
    else ->
        RecapVideoConfig(width = 1080, height = 1920, fps = 30, includeAudio = true)
}

/** Computes an uncut dynamic canvas height (even px) for the full zigzag receipt PNG. */
internal fun estimateFullReceiptHeightPx(data: RecapStoryData, widthPx: Int = 1080): Int {
    val density = widthPx / ExportVirtualWidthDp
    val rows = data.artists.size + data.songs.take(10).size
    val totalDp = 164f + 426f + rows * 31f + 64f
    val rawPx = (totalDp * density).roundToInt().coerceIn(1920, 4400)
    return if (rawPx % 2 == 0) rawPx else rawPx + 1
}

/** Preloads every resized cover/avatar into Coil memory cache (software-safe) and extracts palettes. */
internal suspend fun preloadRecapAssets(context: Context, data: RecapStoryData): RecapAssets = coroutineScope {
    val artistPaletteDeferred = async {
        RecapPalettes.extract(context, data.heroArtist?.thumbnailUrl ?: data.heroSong?.thumbnailUrl)
    }
    val songPaletteDeferred = async {
        RecapPalettes.extract(context, data.heroSong?.thumbnailUrl ?: data.heroArtist?.thumbnailUrl)
    }

    val mediaTargets = buildList<Any> {
        data.photo?.let { add(it) }
        data.artists.forEach { a ->
            a.thumbnailUrl?.let {
                add(it.resize(544, 544))
                add(it.resize(96, 96))
            }
        }
        data.heroArtist?.topSong?.thumbnailUrl?.let { add(it.resize(240, 240)) }
        data.songs.forEach { s ->
            s.thumbnailUrl?.let { add(it.resize(360, 360)) }
        }
        data.songs.take(10).forEach { s ->
            s.thumbnailUrl?.let { add(it.resize(96, 96)) }
        }
        data.heroSong?.thumbnailUrl?.let { add(it.resize(544, 544)) }
        (data.heroSong?.artistThumbnailUrl ?: data.heroSong?.thumbnailUrl)?.let { add(it.resize(400, 400)) }
    }.distinct()

    withTimeoutOrNull(6_000L) {
        mediaTargets.map { target ->
            async(Dispatchers.IO) {
                runCatching {
                    context.imageLoader.execute(
                        ImageRequest.Builder(context)
                            .data(target)
                            .allowHardware(false)
                            .build(),
                    )
                }
            }
        }.awaitAll()
    }

    RecapAssets(
        artistPalette = artistPaletteDeferred.await(),
        songPalette = songPaletteDeferred.await(),
    )
}

/** Snaps every Animatable & mutable state inside [state] and [time] to match [values] deterministically. */
internal suspend fun applyRecapFrameValues(
    state: StoryState,
    time: RecapTime,
    values: RecapFrameValues,
    data: RecapStoryData,
    assets: RecapAssets,
) {
    time.frameNanos = values.timeMs * 1_000_000L
    state.reveal.snapTo(values.reveal)
    state.backdrop.snapTo(values.backdrop)
    state.band.snapTo(values.band)
    state.headerClip.snapTo(values.headerClip)
    state.toHeader.snapTo(values.toHeader)
    state.mesh.snapTo(values.mesh)
    state.dots.snapTo(values.dots)

    when (values.paletteMode) {
        RecapPaletteMode.DARK -> {
            state.paletteFrom = RecapPalette.Fallback
            state.paletteTo = RecapPalette.Fallback
            state.paletteMix.snapTo(1f)
        }
        RecapPaletteMode.ARTIST -> {
            state.paletteFrom = assets.artistPalette
            state.paletteTo = assets.artistPalette
            state.paletteMix.snapTo(1f)
        }
        RecapPaletteMode.SONG -> {
            state.paletteFrom = assets.songPalette
            state.paletteTo = assets.songPalette
            state.paletteMix.snapTo(1f)
        }
        RecapPaletteMode.ARTIST_TO_SONG -> {
            state.paletteFrom = assets.artistPalette
            state.paletteTo = assets.songPalette
            state.paletteMix.snapTo(values.paletteMix)
        }
    }

    state.logoSide.snapTo(values.logoSide)

    val endCardTop = state.geo.top + state.geo.dp(175f)
    val offscreenCardTop = state.geo.height + state.geo.dp(40f)
    state.cardX.snapTo(state.geo.inset)
    state.cardY.snapTo(lerpF(offscreenCardTop, endCardTop, values.cardYProgress))
    state.cardRot.snapTo(values.cardRot)
    state.cardAlpha.snapTo(values.cardAlpha)
    state.cardBack = false
    state.scan.snapTo(0f)

    val fromAv = state.geo.avatar(values.avPageFrom)
    val toAv = state.geo.avatar(values.avPageTo)
    state.avX.snapTo(lerpF(fromAv[0], toAv[0], values.avMoveT))
    state.avY.snapTo(lerpF(fromAv[1], toAv[1], values.avMoveT))
    state.avD.snapTo(lerpF(fromAv[2], toAv[2], values.avMoveT))
    state.avRing.snapTo(values.avRing)
    state.avRingAlpha.snapTo(values.avRingAlpha)
    state.avAlpha.snapTo(values.avAlpha)
    state.avPop.snapTo(values.avPop)

    val wordStr = when (values.textWord) {
        RecapGiantWord.MOCHY -> "MOCHY"
        RecapGiantWord.MONTH -> data.monthName.uppercase()
        RecapGiantWord.WEEKDAY -> DayOfWeek.of(data.favouriteDay + 1).displayName().uppercase()
        RecapGiantWord.STREAK -> "STREAK"
        RecapGiantWord.PERSONA -> data.insights.persona.id.giantWord
    }
    for (idx in 0 until RecapFrameValues.ROW_COUNT) {
        val row = state.text.row(idx - RecapFrameValues.ROW_HALF)
        if (row.word != wordStr) row.word = wordStr
        row.side = values.textRowSides[idx]
        row.fill.snapTo(values.textRowFills[idx])
    }
    state.text.rotation.snapTo(values.textRotation)
    state.text.scale.snapTo(values.textScale)
    state.text.centerX.snapTo(values.textCenterX)
    state.text.offsetY.snapTo(state.geo.dp(values.textOffsetYDp))
    state.text.alpha.snapTo(values.textAlpha)

    for (i in state.pages.indices) {
        state.pages[i].enter.snapTo(values.pageEnters[i])
        state.pages[i].exit.snapTo(values.pageExits[i])
    }

    state.spin.snapTo(values.spin)
    state.spinGlow.snapTo(values.spinGlow)
    state.heroArtist.snapTo(values.heroArtist)
    state.gridDrift.snapTo(values.gridDrift)
    state.gridPick.snapTo(values.gridPick)
    state.heroCover.snapTo(values.heroCover)
    state.heroCoverUp.snapTo(values.heroCoverUp)
    state.receiptScrollProgress = values.receiptScroll
    state.dragX = 0f
}

/** Exports one Recap part as a sharp PNG (1080x1920, or uncut dynamic height for full receipt). */
internal suspend fun exportRecapStill(
    context: Context,
    host: ViewGroup,
    data: RecapStoryData,
    payload: String,
    caption: String,
    theme: ShareTheme,
    part: RecapPart,
    fullReceiptImage: Boolean = true,
    preloadedAssets: RecapAssets? = null,
    saveToGallery: Boolean = false,
): RecapExportResult {
    val assets = preloadedAssets ?: preloadRecapAssets(context, data)
    val width = 1080
    val isFullReceipt = part == RecapPart.RECEIPT && fullReceiptImage
    val height = if (isFullReceipt) estimateFullReceiptHeightPx(data, width) else 1920
    val exportDensity = width / ExportVirtualWidthDp
    val geo = StoryGeometry(
        width = width.toFloat(),
        height = height.toFloat(),
        top = ExportTopInsetDp * exportDensity,
        density = exportDensity,
    )
    val cardWidth = geo.width - 2f * geo.inset
    val cardRect = Rect(
        offset = Offset(geo.inset, geo.top + geo.dp(175f)),
        size = Size(cardWidth, cardWidth / IdCardAspect),
    )
    val state = StoryState(geo, cardRect)
    val time = RecapTime(epochNanos = 0L)
    applyRecapFrameValues(
        state = state,
        time = time,
        values = RecapExportTimeline.stillFrameFor(part, data.artists.size),
        data = data,
        assets = assets,
    )

    val frames = OffscreenFrames(host, width, height) {
        ExportRecapComposition(
            theme = theme,
            density = exportDensity,
            state = state,
            data = data,
            payload = payload,
            caption = caption,
            time = time,
            cardWidth = cardWidth,
            stillMode = true,
            fullReceiptLayout = isFullReceipt,
        )
    }
    try {
        frames.warmUp(StillWarmUpFrames)
        val captured = frames.capture()
        val copy = Bitmap.createBitmap(captured.bitmap, 0, 0, captured.width, captured.height)
        val out = withContext(Dispatchers.IO) {
            try {
                newRecapExportFile(context, "recap-${part.id}", "png").also { file ->
                    file.outputStream().use { copy.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            } finally {
                if (copy !== captured.bitmap) copy.recycle()
            }
        }
        val saved = if (saveToGallery) saveMediaToGallery(context, out, "image/png") else false
        return RecapExportResult(file = out, mimeType = "image/png", savedToGallery = saved)
    } finally {
        frames.release()
    }
}

/** Exports every available Recap part as PNGs and saves each to the phone Gallery. */
internal suspend fun exportAllRecapStills(
    context: Context,
    host: ViewGroup,
    data: RecapStoryData,
    payload: String,
    caption: String,
    theme: ShareTheme,
    fullReceiptImage: Boolean,
    job: ShareExportJob,
): List<RecapExportResult> {
    val parts = availableRecapParts(data)
    job.progress = 0.05f
    val assets = preloadRecapAssets(context, data)
    val results = mutableListOf<RecapExportResult>()
    parts.forEachIndexed { idx, part ->
        if (job.cancelled) throw CancellationException("Recap export cancelled")
        job.progress = (0.10f + 0.85f * idx.toFloat() / parts.size.coerceAtLeast(1)).coerceIn(0f, 0.99f)
        val result = exportRecapStill(
            context = context,
            host = host,
            data = data,
            payload = payload,
            caption = caption,
            theme = theme,
            part = part,
            fullReceiptImage = fullReceiptImage,
            preloadedAssets = assets,
            saveToGallery = true,
        )
        results += result
    }
    job.progress = 1f
    return results
}

/**
 * Exports either the Full Mochy Re'cap Video (`part == null`, ~24.6s) or a single-part animated clip
 * (`part != null`, ~5.0-6.6s) at 60fps/30fps with Mochy animations and soundtrack (auto silent fallback).
 */
internal suspend fun exportRecapVideo(
    context: Context,
    host: ViewGroup,
    database: MusicDatabase,
    data: RecapStoryData,
    payload: String,
    caption: String,
    theme: ShareTheme,
    config: RecapVideoConfig,
    part: RecapPart?,
    songSource: DataSource.Factory?,
    job: ShareExportJob,
    saveToGallery: Boolean = false,
): RecapExportResult {
    job.progress = 0.02f
    val assets = preloadRecapAssets(context, data)
    if (job.cancelled) throw CancellationException("Recap export cancelled")
    job.progress = 0.06f

    val plan = if (part == null) {
        RecapExportTimeline.buildFullTimeline(
            hasHeroArtist = data.heroArtist != null,
            hasHeroSong = data.heroSong != null,
            artistCount = data.artists.size,
        )
    } else {
        RecapExportTimeline.buildPartTimeline(
            part = part,
            hasHeroArtist = data.heroArtist != null,
            hasHeroSong = data.heroSong != null,
            artistCount = data.artists.size,
        )
    }

    val width = config.width
    val height = config.height
    val fps = config.fps
    val exportDensity = width / ExportVirtualWidthDp
    val geo = StoryGeometry(
        width = width.toFloat(),
        height = height.toFloat(),
        top = ExportTopInsetDp * exportDensity,
        density = exportDensity,
    )
    val cardWidth = geo.width - 2f * geo.inset
    val cardRect = Rect(
        offset = Offset(geo.inset, geo.top + geo.dp(175f)),
        size = Size(cardWidth, cardWidth / IdCardAspect),
    )
    val state = StoryState(geo, cardRect)
    val time = RecapTime(epochNanos = 0L)
    applyRecapFrameValues(state, time, RecapExportTimeline.evaluateFrame(plan, 0L), data, assets)

    val frames = OffscreenFrames(host, width, height) {
        ExportRecapComposition(
            theme = theme,
            density = exportDensity,
            state = state,
            data = data,
            payload = payload,
            caption = caption,
            time = time,
            cardWidth = cardWidth,
            stillMode = false,
            fullReceiptLayout = false,
        )
    }
    val totalFrames = (plan.totalDurationMs * fps / 1000L).coerceAtLeast(1L)
    val feeder = RecapVideoFrameFeeder(
        frames = frames,
        state = state,
        time = time,
        plan = plan,
        data = data,
        assets = assets,
        job = job,
        totalFrames = totalFrames,
    )

    try {
        frames.warmUp(VideoWarmUpFrames)
        val soundtrackId = if (config.includeAudio && songSource != null) selectSoundtrackSongId(data, part) else null
        val soundtrackItem = if (soundtrackId != null) resolveSoundtrackMediaItem(database, soundtrackId) else null

        val prefix = if (part == null) "recap-full-${fps}fps" else "recap-${part.id}-${fps}fps"
        val out = newRecapExportFile(context, prefix, "mp4")
        var audioIncluded = false
        var fellBackToSilent = false

        if (soundtrackId != null && soundtrackItem != null && songSource != null) {
            try {
                runRecapTransformer(
                    context = context,
                    out = out,
                    durationMs = plan.totalDurationMs,
                    width = width,
                    height = height,
                    fps = fps,
                    feeder = feeder,
                    soundtrackId = soundtrackId,
                    soundtrackItem = soundtrackItem,
                    songSource = songSource,
                    job = job,
                )
                audioIncluded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (job.cancelled) throw CancellationException("Recap export cancelled")
                Log.w(Tag, "Audio mux failed for $soundtrackId, retrying silent video export", e)
                out.delete()
                feeder.reset()
                fellBackToSilent = true
                runRecapTransformer(
                    context = context,
                    out = out,
                    durationMs = plan.totalDurationMs,
                    width = width,
                    height = height,
                    fps = fps,
                    feeder = feeder,
                    soundtrackId = null,
                    soundtrackItem = null,
                    songSource = null,
                    job = job,
                )
            }
        } else {
            runRecapTransformer(
                context = context,
                out = out,
                durationMs = plan.totalDurationMs,
                width = width,
                height = height,
                fps = fps,
                feeder = feeder,
                soundtrackId = null,
                soundtrackItem = null,
                songSource = null,
                job = job,
            )
        }

        val saved = if (saveToGallery) saveMediaToGallery(context, out, "video/mp4") else false
        job.progress = 1f
        return RecapExportResult(
            file = out,
            mimeType = "video/mp4",
            audioIncluded = audioIncluded,
            fellBackToSilent = fellBackToSilent,
            savedToGallery = saved,
        )
    } finally {
        Log.i(Tag, "Recap video ${width}x$height @ ${fps}fps (${part?.id ?: "full"}): ${frames.summary()}, ${feeder.summary()}")
        frames.release()
    }
}

internal fun shareRecapFile(context: Context, file: File, mimeType: String, caption: String) {
    val uri = shareFileUri(context, file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, caption)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(intent, context.getString(R.string.rc_export_share_title))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

internal fun shareRecapImages(context: Context, files: List<File>, caption: String) {
    if (files.isEmpty()) return
    if (files.size == 1) {
        shareRecapFile(context, files.first(), "image/png", caption)
        return
    }
    val uris = ArrayList<Uri>(files.map { shareFileUri(context, it) })
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "image/png"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        putExtra(Intent.EXTRA_TEXT, caption)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(intent, context.getString(R.string.rc_export_share_title))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

// ---------------------------------------------------------------------------------------------
// Internal composition & Media3 Transformer helpers

@Composable
private fun ExportRecapComposition(
    theme: ShareTheme,
    density: Float,
    state: StoryState,
    data: RecapStoryData,
    payload: String,
    caption: String,
    time: RecapTime,
    cardWidth: Float,
    stillMode: Boolean,
    fullReceiptLayout: Boolean,
) {
    MaterialTheme(colorScheme = theme.colors, typography = theme.typography) {
        CompositionLocalProvider(
            LocalDensity provides Density(density, fontScale = 1f),
            LocalContentColor provides Color.White,
            LocalBlurQuality provides BlurQuality.HIGH,
        ) {
            RecapStoryScene(
                s = state,
                data = data,
                payload = payload,
                caption = caption,
                time = time,
                cardWidth = cardWidth,
                headerBottom = 0f,
                exportMode = true,
                stillMode = stillMode,
                fullReceiptLayout = fullReceiptLayout,
            )
        }
    }
}

private suspend fun resolveSoundtrackMediaItem(database: MusicDatabase, songId: String): MediaItem =
    withContext(Dispatchers.IO) {
        runCatching { database.getSongsByIds(listOf(songId)).firstOrNull()?.toMediaMetadata()?.toMediaItem() }.getOrNull()
            ?: MediaItem.Builder()
                .setMediaId(songId)
                .setUri(songId)
                .setCustomCacheKey(songId)
                .build()
    }

private suspend fun runRecapTransformer(
    context: Context,
    out: File,
    durationMs: Long,
    width: Int,
    height: Int,
    fps: Int,
    feeder: RecapVideoFrameFeeder,
    soundtrackId: String?,
    soundtrackItem: MediaItem?,
    songSource: DataSource.Factory?,
    job: ShareExportJob,
) {
    val carrier = EditedMediaItem.Builder(
        MediaItem.Builder()
            .setUri(Uri.fromFile(recapCarrierImage(context)))
            .setImageDurationMs(durationMs)
            .build(),
    )
        .setFrameRate(fps)
        .setEffects(
            Effects(
                emptyList(),
                listOf(
                    Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_STRETCH_TO_FIT),
                    ForegroundEffect(feeder::frameAt),
                ),
            ),
        )
        .build()

    val audioId = soundtrackId
    val audioItem = soundtrackItem
    val audioSource = songSource
    val sequences = buildList {
        add(EditedMediaItemSequence.Builder(carrier).build())
        if (audioId != null && audioItem != null && audioSource != null) {
            val clipped = clippedItem(audioItem.buildUpon(), 0L, durationMs)
            val audio = EditedMediaItem.Builder(clipped)
                .setRemoveVideo(true)
                .setEffects(Effects(listOf(ShareAudioFade(durationMs)), emptyList()))
                .build()
            add(EditedMediaItemSequence.Builder(audio).build())
        }
    }
    val composition = Composition.Builder(sequences).build()
    val localFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context))
    val sources: MediaSource.Factory = if (audioId != null && audioItem != null && audioSource != null) {
        ShareMediaSources(
            songId = audioId,
            song = DefaultMediaSourceFactory(audioSource),
            web = localFactory,
        )
    } else {
        localFactory
    }

    suspendCancellableCoroutine { continuation ->
        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                continuation.resume(Unit)
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                continuation.resumeWithException(exportException)
            }
        }
        val encoders = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(ShareEncoders.bitrateForSize(width, height, fps))
                    .build(),
            )
            .setEnableFallback(true)
            .build()
        val loaders = DefaultAssetLoaderFactory(
            context,
            DefaultDecoderFactory.Builder(context).build(),
            Clock.DEFAULT,
            sources,
            DataSourceBitmapLoader(context),
        )
        val built = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .apply { if (includeAudio) setAudioMimeType(MimeTypes.AUDIO_AAC) }
            .setEncoderFactory(encoders)
            .setAssetLoaderFactory(loaders)
            .addListener(listener)
            .build()
        built.start(composition, out.path)
        continuation.invokeOnCancellation {
            job.cancelled = true
            Handler(Looper.getMainLooper()).post { built.cancel() }
            out.delete()
        }
    }
}

private class RecapVideoFrameFeeder(
    private val frames: OffscreenFrames,
    private val state: StoryState,
    private val time: RecapTime,
    private val plan: RecapTimelinePlan,
    private val data: RecapStoryData,
    private val assets: RecapAssets,
    private val job: ShareExportJob,
    private val totalFrames: Long,
) {
    private val main = Handler(Looper.getMainLooper())
    private var lastPtsUs = -1L
    private var drawn = 0L
    private var failed = 0

    fun reset() {
        lastPtsUs = -1L
        drawn = 0L
        failed = 0
    }

    fun frameAt(presentationTimeUs: Long): FrameImage? {
        if (job.cancelled) return null
        val done = CountDownLatch(1)
        var result: FrameImage? = null
        main.post {
            frames.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                result = runCatching { draw(presentationTimeUs) }
                    .onFailure {
                        if (failed == 0) Log.e(Tag, "Couldn't draw recap frame at $presentationTimeUs us", it)
                        failed++
                    }
                    .getOrElse { frames.lastFrame }
                done.countDown()
            }
        }
        while (!done.await(100, TimeUnit.MILLISECONDS)) {
            if (job.cancelled) return null
        }
        return result
    }

    fun summary(): String = "handed over $drawn frames, $failed failed"

    private suspend fun draw(presentationTimeUs: Long): FrameImage {
        val stepMs = if (lastPtsUs < 0L) 0.0 else (presentationTimeUs - lastPtsUs) / 1000.0
        lastPtsUs = presentationTimeUs
        val tMs = (presentationTimeUs / 1000L).coerceAtLeast(0L)
        val values = RecapExportTimeline.evaluateFrame(plan, tMs)
        applyRecapFrameValues(state, time, values, data, assets)
        frames.advance(stepMs)
        val frame = frames.capture()
        drawn++
        job.progress = min(0.99f, 0.08f + 0.90f * (drawn.toFloat() / totalFrames))
        return frame
    }
}

private fun recapExportDir(context: Context): File = File(context.cacheDir, RecapExportDir).apply { mkdirs() }

private fun recapCarrierImage(context: Context): File {
    val file = File(recapExportDir(context), "carrier.png")
    if (!file.exists()) {
        val black = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
        try {
            file.outputStream().use { black.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            black.recycle()
        }
    }
    return file
}

private fun newRecapExportFile(context: Context, prefix: String, extension: String): File {
    val dir = recapExportDir(context)
    dir.listFiles()
        ?.filter { it.name != "carrier.png" && System.currentTimeMillis() - it.lastModified() > 5 * 60_000L }
        ?.forEach { it.delete() }
    return File(dir, "mochy-$prefix-${System.currentTimeMillis()}.$extension")
}
