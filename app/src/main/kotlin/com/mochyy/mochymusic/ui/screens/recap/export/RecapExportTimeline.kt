// RecapExportTimeline.kt
//
// Pure-JVM deterministic keyframe timeline for Mochy Re'cap video & still-image exports.
// Reproduces every signature Mochy animation (90-degree giant word warp, odometer numbers,
// MonthBar sheen, artist slot machine + glow rings, tilted song cover grid + hero pick,
// scrolling zigzag receipt, and Profile ID card outro) at any target FPS (60fps / 30fps).

package com.mochyy.mochymusic.ui.screens.recap.export

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class RecapGiantWord {
    MOCHY,
    MONTH,
    WEEKDAY,
}

enum class RecapPaletteMode {
    DARK,
    ARTIST,
    SONG,
    ARTIST_TO_SONG,
}

enum class RecapSegmentKind {
    HEY,
    JOURNEY,
    MONTH,
    WEEKDAY,
    ARTIST_SPIN_REVEAL,
    SONG_GRID_REVEAL,
    RECEIPT,
    END_CARD,
}

data class RecapTimelineSegment(
    val kind: RecapSegmentKind,
    val startMs: Long,
    val endMs: Long,
)

data class RecapTimelinePlan(
    val part: RecapPart?,
    val hasHeroArtist: Boolean,
    val hasHeroSong: Boolean,
    val artistCount: Int,
    val totalDurationMs: Long,
    val segments: List<RecapTimelineSegment>,
)

/**
 * Complete snapshot of all Mochy Recap StoryState + GiantTextState values at a single timestamp.
 * All indices match StoryPageCount (11) and GiantTextState rows (-4..4 => indices 0..8).
 */
class RecapFrameValues(
    val timeMs: Long,
    val reveal: Float = 1f,
    val backdrop: Float = 1f,
    val band: Float = 1f,
    val headerClip: Float = 0f,
    val toHeader: Float = 0f,
    val mesh: Float = 0f,
    val dots: Float = 1f,
    val paletteMode: RecapPaletteMode = RecapPaletteMode.DARK,
    val paletteMix: Float = 1f,
    val logoSide: Float = 0f,
    val cardVisible: Boolean = false,
    val cardYProgress: Float = 0f,
    val cardRot: Float = 0f,
    val cardAlpha: Float = 0f,
    val avPageFrom: Int = PAGE_HEY,
    val avPageTo: Int = PAGE_HEY,
    val avMoveT: Float = 1f,
    val avRing: Float = 0f,
    val avRingAlpha: Float = 1f,
    val avAlpha: Float = 0f,
    val avPop: Float = 0f,
    val textWord: RecapGiantWord = RecapGiantWord.MOCHY,
    val textRotation: Float = 0f,
    val textScale: Float = 0.74f,
    val textCenterX: Float = 0.5f,
    val textOffsetYDp: Float = 0f,
    val textAlpha: Float = 1f,
    val textRowFills: FloatArray = FloatArray(ROW_COUNT) { 0f },
    val textRowSides: FloatArray = FloatArray(ROW_COUNT) { 1f },
    val pageEnters: FloatArray = FloatArray(PAGE_COUNT) { 0f },
    val pageExits: FloatArray = FloatArray(PAGE_COUNT) { 0f },
    val spin: Float = -2.6f,
    val spinGlow: Float = 0f,
    val heroArtist: Float = 0f,
    val gridDrift: Float = 0f,
    val gridPick: Float = 0f,
    val heroCover: Float = 0f,
    val heroCoverUp: Float = 0f,
    val receiptScroll: Float = 0f,
) {
    companion object {
        const val PAGE_COUNT = 11
        const val ROW_HALF = 4
        const val ROW_COUNT = ROW_HALF * 2 + 1

        const val PAGE_HEY = 0
        const val PAGE_JOURNEY = 1
        const val PAGE_MONTH = 2
        const val PAGE_WEEKDAY = 3
        const val PAGE_ARTISTS = 4
        const val PAGE_ARTIST = 5
        const val PAGE_SONGS = 6
        const val PAGE_MELODY = 7
        const val PAGE_SONG = 8
        const val PAGE_RECEIPT = 9
        const val PAGE_END = 10
    }
}

object RecapExportTimeline {
    private const val OVERLAP_MS = 400L

    /** Builds the full Mochy Re'cap video timeline (~24.6s with artist + song). */
    fun buildFullTimeline(
        hasHeroArtist: Boolean,
        hasHeroSong: Boolean,
        artistCount: Int,
    ): RecapTimelinePlan {
        val segments = mutableListOf<RecapTimelineSegment>()
        var cursor = 0L

        fun append(kind: RecapSegmentKind, durationMs: Long, overlapPrev: Long = OVERLAP_MS) {
            val start = if (segments.isEmpty()) 0L else max(0L, cursor - overlapPrev)
            val end = start + durationMs
            segments += RecapTimelineSegment(kind, start, end)
            cursor = end
        }

        append(RecapSegmentKind.HEY, 2200L, overlapPrev = 0L)
        append(RecapSegmentKind.JOURNEY, 3200L, overlapPrev = 350L)
        append(RecapSegmentKind.MONTH, 2800L, overlapPrev = 400L)
        append(RecapSegmentKind.WEEKDAY, 3100L, overlapPrev = 400L)
        if (hasHeroArtist) {
            append(RecapSegmentKind.ARTIST_SPIN_REVEAL, 4800L, overlapPrev = 400L)
        }
        if (hasHeroSong) {
            append(RecapSegmentKind.SONG_GRID_REVEAL, 5000L, overlapPrev = 400L)
        }
        append(RecapSegmentKind.RECEIPT, 3800L, overlapPrev = 400L)
        append(RecapSegmentKind.END_CARD, 2800L, overlapPrev = 400L)

        return RecapTimelinePlan(
            part = null,
            hasHeroArtist = hasHeroArtist,
            hasHeroSong = hasHeroSong,
            artistCount = artistCount.coerceAtLeast(1),
            totalDurationMs = cursor,
            segments = segments,
        )
    }

    /** Builds a standalone animated clip (~5.0-6.6s) for one selected Recap part. */
    fun buildPartTimeline(
        part: RecapPart,
        hasHeroArtist: Boolean,
        hasHeroSong: Boolean,
        artistCount: Int,
    ): RecapTimelinePlan {
        val (kind, durationMs) = when (part) {
            RecapPart.JOURNEY -> RecapSegmentKind.JOURNEY to 5200L
            RecapPart.MONTH -> RecapSegmentKind.MONTH to 5000L
            RecapPart.WEEKDAY -> RecapSegmentKind.WEEKDAY to 5200L
            RecapPart.TOP_ARTIST -> RecapSegmentKind.ARTIST_SPIN_REVEAL to 6400L
            RecapPart.TOP_SONG -> RecapSegmentKind.SONG_GRID_REVEAL to 6600L
            RecapPart.RECEIPT -> RecapSegmentKind.RECEIPT to 6400L
            RecapPart.SUMMARY_CARD -> RecapSegmentKind.END_CARD to 5200L
        }
        return RecapTimelinePlan(
            part = part,
            hasHeroArtist = hasHeroArtist,
            hasHeroSong = hasHeroSong,
            artistCount = artistCount.coerceAtLeast(1),
            totalDurationMs = durationMs,
            segments = listOf(RecapTimelineSegment(kind, 0L, durationMs)),
        )
    }

    /** Returns the settled, razor-sharp peak keyframe for still PNG export of [part]. */
    fun stillFrameFor(part: RecapPart, artistCount: Int = 1): RecapFrameValues {
        val n = artistCount.coerceAtLeast(1)
        val enters = FloatArray(RecapFrameValues.PAGE_COUNT) { 0f }
        val exits = FloatArray(RecapFrameValues.PAGE_COUNT) { 0f }
        val rowFills = FloatArray(RecapFrameValues.ROW_COUNT) { 0f }
        val rowSides = FloatArray(RecapFrameValues.ROW_COUNT) { 1f }

        return when (part) {
            RecapPart.JOURNEY -> {
                enters[RecapFrameValues.PAGE_JOURNEY] = 1f
                for (rowIdx in -4..-2) rowFills[rowIdx + RecapFrameValues.ROW_HALF] = 1f
                RecapFrameValues(
                    timeMs = 2400L,
                    mesh = 0f,
                    dots = 1f,
                    paletteMode = RecapPaletteMode.DARK,
                    logoSide = 0f,
                    avPageFrom = RecapFrameValues.PAGE_JOURNEY,
                    avPageTo = RecapFrameValues.PAGE_JOURNEY,
                    avMoveT = 1f,
                    avRing = 1f,
                    avRingAlpha = 1f,
                    avAlpha = 1f,
                    avPop = 1f,
                    textWord = RecapGiantWord.MOCHY,
                    textRotation = 0f,
                    textScale = 0.74f,
                    textCenterX = 0.5f,
                    textOffsetYDp = -46f,
                    textAlpha = 1f,
                    textRowFills = rowFills,
                    textRowSides = rowSides,
                    pageEnters = enters,
                    pageExits = exits,
                )
            }

            RecapPart.MONTH -> {
                enters[RecapFrameValues.PAGE_MONTH] = 1f
                for (rowIdx in -4..3) rowFills[rowIdx + RecapFrameValues.ROW_HALF] = 1f
                RecapFrameValues(
                    timeMs = 2400L,
                    mesh = 0f,
                    dots = 1f,
                    paletteMode = RecapPaletteMode.DARK,
                    logoSide = 0f,
                    avPageFrom = RecapFrameValues.PAGE_MONTH,
                    avPageTo = RecapFrameValues.PAGE_MONTH,
                    avMoveT = 1f,
                    avRing = 1f,
                    avRingAlpha = 1f,
                    avAlpha = 1f,
                    avPop = 1f,
                    textWord = RecapGiantWord.MONTH,
                    textRotation = 0f,
                    textScale = 0.74f,
                    textCenterX = 0.5f,
                    textOffsetYDp = -46f,
                    textAlpha = 1f,
                    textRowFills = rowFills,
                    textRowSides = rowSides,
                    pageEnters = enters,
                    pageExits = exits,
                )
            }

            RecapPart.WEEKDAY -> {
                enters[RecapFrameValues.PAGE_WEEKDAY] = 1f
                for (i in 0 until RecapFrameValues.ROW_COUNT) rowFills[i] = 1f
                RecapFrameValues(
                    timeMs = 2400L,
                    mesh = 0f,
                    dots = 1f,
                    paletteMode = RecapPaletteMode.DARK,
                    logoSide = 0f,
                    avPageFrom = RecapFrameValues.PAGE_WEEKDAY,
                    avPageTo = RecapFrameValues.PAGE_WEEKDAY,
                    avMoveT = 1f,
                    avRing = 1f,
                    avRingAlpha = 1f,
                    avAlpha = 1f,
                    avPop = 1f,
                    textWord = RecapGiantWord.WEEKDAY,
                    textRotation = 0f,
                    textScale = 0.74f,
                    textCenterX = 0.5f,
                    textOffsetYDp = 0f,
                    textAlpha = 1f,
                    textRowFills = rowFills,
                    textRowSides = rowSides,
                    pageEnters = enters,
                    pageExits = exits,
                )
            }

            RecapPart.TOP_ARTIST -> {
                // ArtistSequence draws the hero avatar based on spinAnim.enter (must be 1f) while
                // hiding spin items via spinAnim.exit (1f) and revealing ArtistReveal via reveal.enter (1f).
                enters[RecapFrameValues.PAGE_ARTISTS] = 1f
                exits[RecapFrameValues.PAGE_ARTISTS] = 1f
                enters[RecapFrameValues.PAGE_ARTIST] = 1f
                RecapFrameValues(
                    timeMs = 3600L,
                    mesh = 1f,
                    dots = 0f,
                    paletteMode = RecapPaletteMode.ARTIST,
                    paletteMix = 1f,
                    logoSide = 1f,
                    avPageFrom = RecapFrameValues.PAGE_ARTISTS,
                    avPageTo = RecapFrameValues.PAGE_ARTISTS,
                    avMoveT = 1f,
                    avRing = 1f,
                    avRingAlpha = 0f,
                    avAlpha = 1f,
                    avPop = 1f,
                    textAlpha = 0f,
                    textRowFills = rowFills,
                    textRowSides = rowSides,
                    pageEnters = enters,
                    pageExits = exits,
                    spin = 3f * n,
                    spinGlow = 1f,
                    heroArtist = 1f,
                )
            }

            RecapPart.TOP_SONG -> {
                enters[RecapFrameValues.PAGE_SONGS] = 1f
                exits[RecapFrameValues.PAGE_SONGS] = 1f
                enters[RecapFrameValues.PAGE_MELODY] = 1f
                exits[RecapFrameValues.PAGE_MELODY] = 1f
                enters[RecapFrameValues.PAGE_SONG] = 1f
                RecapFrameValues(
                    timeMs = 4200L,
                    mesh = 1f,
                    dots = 0f,
                    paletteMode = RecapPaletteMode.SONG,
                    paletteMix = 1f,
                    logoSide = 1f,
                    avPageFrom = RecapFrameValues.PAGE_ARTISTS,
                    avPageTo = RecapFrameValues.PAGE_ARTISTS,
                    avMoveT = 1f,
                    avRing = 1f,
                    avRingAlpha = 0f,
                    avAlpha = 1f,
                    avPop = 1f,
                    textAlpha = 0f,
                    textRowFills = rowFills,
                    textRowSides = rowSides,
                    pageEnters = enters,
                    pageExits = exits,
                    gridDrift = 1f,
                    gridPick = 1f,
                    heroCover = 1f,
                    heroCoverUp = 1f,
                )
            }

            RecapPart.RECEIPT -> {
                enters[RecapFrameValues.PAGE_RECEIPT] = 1f
                RecapFrameValues(
                    timeMs = 2600L,
                    mesh = 1f,
                    dots = 0f,
                    paletteMode = RecapPaletteMode.SONG,
                    paletteMix = 1f,
                    logoSide = 1f,
                    avPageFrom = RecapFrameValues.PAGE_ARTISTS,
                    avPageTo = RecapFrameValues.PAGE_ARTISTS,
                    avMoveT = 1f,
                    avRing = 1f,
                    avRingAlpha = 0f,
                    avAlpha = 1f,
                    avPop = 1f,
                    textAlpha = 0f,
                    textRowFills = rowFills,
                    textRowSides = rowSides,
                    pageEnters = enters,
                    pageExits = exits,
                    receiptScroll = 0f,
                )
            }

            RecapPart.SUMMARY_CARD -> {
                enters[RecapFrameValues.PAGE_END] = 1f
                for (i in 0 until RecapFrameValues.ROW_COUNT) rowFills[i] = 1f
                RecapFrameValues(
                    timeMs = 3000L,
                    mesh = 1f,
                    dots = 0f,
                    paletteMode = RecapPaletteMode.SONG,
                    paletteMix = 1f,
                    logoSide = 0f,
                    cardVisible = true,
                    cardYProgress = 1f,
                    cardRot = 0f,
                    cardAlpha = 1f,
                    avAlpha = 0f,
                    avPop = 0f,
                    textWord = RecapGiantWord.MOCHY,
                    textRotation = 0f,
                    textScale = 0.74f,
                    textCenterX = 0.5f,
                    textOffsetYDp = 0f,
                    textAlpha = 0.9f,
                    textRowFills = rowFills,
                    textRowSides = rowSides,
                    pageEnters = enters,
                    pageExits = exits,
                )
            }
        }
    }

    /** Evaluates the complete frame state at timestamp [timeMs] (clamped to `[0, totalDurationMs]`). */
    fun evaluateFrame(plan: RecapTimelinePlan, timeMs: Long): RecapFrameValues {
        val t = timeMs.coerceIn(0L, plan.totalDurationMs.coerceAtLeast(1L))
        return if (plan.part != null) {
            evaluateSinglePart(plan, plan.part, t)
        } else {
            evaluateFull(plan, t)
        }
    }

    private fun evaluateSinglePart(plan: RecapTimelinePlan, part: RecapPart, t: Long): RecapFrameValues {
        val dur = plan.totalDurationMs.coerceAtLeast(1L)
        val n = plan.artistCount.coerceAtLeast(1)
        val enters = FloatArray(RecapFrameValues.PAGE_COUNT) { 0f }
        val exits = FloatArray(RecapFrameValues.PAGE_COUNT) { 0f }
        val rowFills = FloatArray(RecapFrameValues.ROW_COUNT) { 0f }
        val rowSides = FloatArray(RecapFrameValues.ROW_COUNT) { 1f }

        return when (part) {
            RecapPart.JOURNEY -> {
                for (r in -4..-2) {
                    val idx = r + RecapFrameValues.ROW_HALF
                    rowFills[idx] = fastOutSlowIn(window(t, (r + 4) * 70L, 700L))
                }
                enters[RecapFrameValues.PAGE_JOURNEY] = window(t, 150L, 2150L)
                val avPop = easeOutBackClamped(window(t, 0L, 650L), 1.35f)
                RecapFrameValues(
                    timeMs = t,
                    mesh = 0f,
                    dots = 1f,
                    paletteMode = RecapPaletteMode.DARK,
                    logoSide = 0f,
                    avPageFrom = RecapFrameValues.PAGE_JOURNEY,
                    avPageTo = RecapFrameValues.PAGE_JOURNEY,
                    avMoveT = 1f,
                    avRing = 1f,
                    avRingAlpha = 1f,
                    avAlpha = 1f,
                    avPop = avPop,
                    textWord = RecapGiantWord.MOCHY,
                    textOffsetYDp = -46f,
                    textAlpha = 1f,
                    textRowFills = rowFills,
                    textRowSides = rowSides,
                    pageEnters = enters,
                    pageExits = exits,
                )
            }

            RecapPart.MONTH -> {
                for (r in -4..3) {
                    val idx = r + RecapFrameValues.ROW_HALF
                    rowFills[idx] = fastOutSlowIn(window(t, (r + 4) * 60L, 760L))
                }
                enters[RecapFrameValues.PAGE_MONTH] = window(t, 200L, 2000L)
                val avPop = easeOutBackClamped(window(t, 0L, 650L), 1.35f)
                RecapFrameValues(
                    timeMs = t,
                    mesh = 0f,
                    dots = 1f,
                    paletteMode = RecapPaletteMode.DARK,
                    logoSide = 0f,
                    avPageFrom = RecapFrameValues.PAGE_MONTH,
                    avPageTo = RecapFrameValues.PAGE_MONTH,
                    avMoveT = 1f,
                    avRing = 1f,
                    avRingAlpha = 1f,
                    avAlpha = 1f,
                    avPop = avPop,
                    textWord = RecapGiantWord.MONTH,
                    textOffsetYDp = -46f,
                    textAlpha = 1f,
                    textRowFills = rowFills,
                    textRowSides = rowSides,
                    pageEnters = enters,
                    pageExits = exits,
                )
            }

            RecapPart.WEEKDAY -> {
                for (r in -4..4) {
                    val idx = r + RecapFrameValues.ROW_HALF
                    rowFills[idx] = fastOutSlowIn(window(t, (r + 4) * 55L, 760L))
                }
                enters[RecapFrameValues.PAGE_WEEKDAY] = window(t, 200L, 2000L)
                val avPop = easeOutBackClamped(window(t, 0L, 650L), 1.35f)
                RecapFrameValues(
                    timeMs = t,
                    mesh = 0f,
                    dots = 1f,
                    paletteMode = RecapPaletteMode.DARK,
                    logoSide = 0f,
                    avPageFrom = RecapFrameValues.PAGE_WEEKDAY,
                    avPageTo = RecapFrameValues.PAGE_WEEKDAY,
                    avMoveT = 1f,
                    avRing = 1f,
                    avRingAlpha = 1f,
                    avAlpha = 1f,
                    avPop = avPop,
                    textWord = RecapGiantWord.WEEKDAY,
                    textOffsetYDp = 0f,
                    textAlpha = 1f,
                    textRowFills = rowFills,
                    textRowSides = rowSides,
                    pageEnters = enters,
                    pageExits = exits,
                )
            }

            RecapPart.TOP_ARTIST -> {
                enters[RecapFrameValues.PAGE_ARTISTS] = emphasized(window(t, 0L, 720L))
                val spinT = artistSpinEasing(window(t, 100L, 2150L))
                val spinVal = lerp(-2.6f, 3f * n, spinT)
                val spinGlow = window(t, 2250L, 650L)
                val heroMove = emphasized(window(t, 2850L, 850L))
                exits[RecapFrameValues.PAGE_ARTISTS] = fastOutSlowIn(window(t, 2850L, 580L))
                enters[RecapFrameValues.PAGE_ARTIST] = window(t, 3000L, 1850L)
                RecapFrameValues(
                    timeMs = t,
                    mesh = 1f,
                    dots = 0f,
                    paletteMode = RecapPaletteMode.ARTIST,
                    paletteMix = 1f,
                    logoSide = 1f,
                    avPageFrom = RecapFrameValues.PAGE_ARTISTS,
                    avPageTo = RecapFrameValues.PAGE_ARTISTS,
                    avMoveT = 1f,
                    avRing = 1f,
                    avRingAlpha = 0f,
                    avAlpha = 1f,
                    avPop = 1f,
                    textAlpha = 0f,
                    textRowFills = rowFills,
                    textRowSides = rowSides,
                    pageEnters = enters,
                    pageExits = exits,
                    spin = spinVal,
                    spinGlow = spinGlow,
                    heroArtist = heroMove,
                )
            }

            RecapPart.TOP_SONG -> {
                enters[RecapFrameValues.PAGE_SONGS] = emphasized(window(t, 0L, 950L))
                val drift = window(t, 0L, 2150L)
                val pick = emphasized(window(t, 2150L, 580L))
                val heroCover = emphasized(window(t, 2700L, 800L))
                exits[RecapFrameValues.PAGE_SONGS] = fastOutSlowIn(window(t, 2700L, 500L))
                enters[RecapFrameValues.PAGE_MELODY] = emphasized(window(t, 2900L, 700L))
                exits[RecapFrameValues.PAGE_MELODY] = fastOutSlowIn(window(t, 3850L, 450L))
                val heroCoverUp = emphasized(window(t, 3850L, 800L))
                enters[RecapFrameValues.PAGE_SONG] = window(t, 4050L, 1600L)
                RecapFrameValues(
                    timeMs = t,
                    mesh = 1f,
                    dots = 0f,
                    paletteMode = RecapPaletteMode.SONG,
                    paletteMix = 1f,
                    logoSide = 1f,
                    avPageFrom = RecapFrameValues.PAGE_ARTISTS,
                    avPageTo = RecapFrameValues.PAGE_ARTISTS,
                    avMoveT = 1f,
                    avRing = 1f,
                    avRingAlpha = 0f,
                    avAlpha = 1f,
                    avPop = 1f,
                    textAlpha = 0f,
                    textRowFills = rowFills,
                    textRowSides = rowSides,
                    pageEnters = enters,
                    pageExits = exits,
                    gridDrift = drift,
                    gridPick = pick,
                    heroCover = heroCover,
                    heroCoverUp = heroCoverUp,
                )
            }

            RecapPart.RECEIPT -> {
                enters[RecapFrameValues.PAGE_RECEIPT] = emphasized(window(t, 0L, 950L))
                val scroll = easeInOutCubic(window(t, 1350L, (dur - 2350L).coerceAtLeast(1200L)))
                RecapFrameValues(
                    timeMs = t,
                    mesh = 1f,
                    dots = 0f,
                    paletteMode = RecapPaletteMode.SONG,
                    paletteMix = 1f,
                    logoSide = 1f,
                    avPageFrom = RecapFrameValues.PAGE_ARTISTS,
                    avPageTo = RecapFrameValues.PAGE_ARTISTS,
                    avMoveT = 1f,
                    avRing = 1f,
                    avRingAlpha = 0f,
                    avAlpha = 1f,
                    avPop = 1f,
                    textAlpha = 0f,
                    textRowFills = rowFills,
                    textRowSides = rowSides,
                    pageEnters = enters,
                    pageExits = exits,
                    receiptScroll = scroll,
                )
            }

            RecapPart.SUMMARY_CARD -> {
                for (r in -4..4) {
                    val idx = r + RecapFrameValues.ROW_HALF
                    rowFills[idx] = fastOutSlowIn(window(t, (r + 4) * 50L, 850L))
                }
                val textAlpha = lerp(0f, 0.9f, emphasized(window(t, 150L, 950L)))
                val cardY = emphasized(window(t, 200L, 1050L))
                val cardRot = lerp(-8f, 0f, easeOutBackClamped(window(t, 200L, 1300L), 0.7f).coerceAtMost(1.06f))
                enters[RecapFrameValues.PAGE_END] = window(t, 220L, 1450L)
                RecapFrameValues(
                    timeMs = t,
                    mesh = 1f,
                    dots = 0f,
                    paletteMode = RecapPaletteMode.SONG,
                    paletteMix = 1f,
                    logoSide = 0f,
                    cardVisible = true,
                    cardYProgress = cardY,
                    cardRot = cardRot,
                    cardAlpha = 1f,
                    avAlpha = 0f,
                    avPop = 0f,
                    textWord = RecapGiantWord.MOCHY,
                    textRotation = 0f,
                    textScale = 0.74f,
                    textCenterX = 0.5f,
                    textOffsetYDp = 0f,
                    textAlpha = textAlpha,
                    textRowFills = rowFills,
                    textRowSides = rowSides,
                    pageEnters = enters,
                    pageExits = exits,
                )
            }
        }
    }

    private fun evaluateFull(plan: RecapTimelinePlan, t: Long): RecapFrameValues {
        val segs = plan.segments.associateBy { it.kind }
        val hey = segs.getValue(RecapSegmentKind.HEY)
        val journey = segs.getValue(RecapSegmentKind.JOURNEY)
        val month = segs.getValue(RecapSegmentKind.MONTH)
        val weekday = segs.getValue(RecapSegmentKind.WEEKDAY)
        val artist = segs[RecapSegmentKind.ARTIST_SPIN_REVEAL]
        val song = segs[RecapSegmentKind.SONG_GRID_REVEAL]
        val receipt = segs.getValue(RecapSegmentKind.RECEIPT)
        val end = segs.getValue(RecapSegmentKind.END_CARD)

        // First segment that turns on the moving colour mesh (Artist -> Song -> Receipt)
        val colorStartMs = artist?.startMs ?: song?.startMs ?: receipt.startMs

        val n = plan.artistCount.coerceAtLeast(1)
        val enters = FloatArray(RecapFrameValues.PAGE_COUNT) { 0f }
        val exits = FloatArray(RecapFrameValues.PAGE_COUNT) { 0f }
        val rowFills = FloatArray(RecapFrameValues.ROW_COUNT) { 0f }
        val rowSides = FloatArray(RecapFrameValues.ROW_COUNT) { 1f }

        // 1. Page enters & exits
        enters[RecapFrameValues.PAGE_HEY] = emphasized(window(t, hey.startMs + 520L, 760L))
        exits[RecapFrameValues.PAGE_HEY] = emphasized(window(t, journey.startMs, 420L))

        enters[RecapFrameValues.PAGE_JOURNEY] = window(t, journey.startMs + 280L, 1950L)
        exits[RecapFrameValues.PAGE_JOURNEY] = fastOutSlowIn(window(t, month.startMs, 580L))

        enters[RecapFrameValues.PAGE_MONTH] = window(t, month.startMs + 320L, 1680L)
        exits[RecapFrameValues.PAGE_MONTH] = fastOutSlowIn(window(t, weekday.startMs, 560L))

        enters[RecapFrameValues.PAGE_WEEKDAY] = window(t, weekday.startMs + 420L, 1750L)
        exits[RecapFrameValues.PAGE_WEEKDAY] = fastOutSlowIn(window(t, colorStartMs, 560L))

        var spinVal = -2.6f
        var spinGlow = 0f
        var heroArtist = 0f
        if (artist != null) {
            val a0 = artist.startMs
            val nextStart = song?.startMs ?: receipt.startMs
            enters[RecapFrameValues.PAGE_ARTISTS] = emphasized(window(t, a0 + 180L, 780L))
            spinVal = lerp(-2.6f, 3f * n, artistSpinEasing(window(t, a0 + 200L, 2000L)))
            spinGlow = window(t, a0 + 2200L, 580L)
            heroArtist = emphasized(window(t, a0 + 2720L, 780L))
            exits[RecapFrameValues.PAGE_ARTISTS] = fastOutSlowIn(window(t, a0 + 2720L, 520L))
            enters[RecapFrameValues.PAGE_ARTIST] = window(t, a0 + 2860L, 1540L)
            exits[RecapFrameValues.PAGE_ARTIST] = fastOutSlowIn(window(t, nextStart, 580L))
        }

        var gridDrift = 0f
        var gridPick = 0f
        var heroCover = 0f
        var heroCoverUp = 0f
        if (song != null) {
            val s0 = song.startMs
            enters[RecapFrameValues.PAGE_SONGS] = emphasized(window(t, s0 + 140L, 950L))
            gridDrift = window(t, s0, 1950L)
            gridPick = emphasized(window(t, s0 + 1950L, 520L))
            heroCover = emphasized(window(t, s0 + 2450L, 720L))
            exits[RecapFrameValues.PAGE_SONGS] = fastOutSlowIn(window(t, s0 + 2450L, 480L))
            enters[RecapFrameValues.PAGE_MELODY] = emphasized(window(t, s0 + 2650L, 620L))
            exits[RecapFrameValues.PAGE_MELODY] = fastOutSlowIn(window(t, s0 + 3450L, 420L))
            heroCoverUp = emphasized(window(t, s0 + 3450L, 720L))
            enters[RecapFrameValues.PAGE_SONG] = window(t, s0 + 3620L, 1280L)
            exits[RecapFrameValues.PAGE_SONG] = fastOutSlowIn(window(t, receipt.startMs, 560L))
        }

        val r0 = receipt.startMs
        enters[RecapFrameValues.PAGE_RECEIPT] = emphasized(window(t, r0 + 180L, 860L))
        val receiptScroll = easeInOutCubic(window(t, r0 + 1150L, 2050L))
        exits[RecapFrameValues.PAGE_RECEIPT] = emphasizedAccelerate(window(t, end.startMs, 600L))

        val e0 = end.startMs
        enters[RecapFrameValues.PAGE_END] = window(t, e0 + 220L, 1380L)

        // 2. Backdrop mesh, palette & dots
        val mesh = fastOutSlowIn(window(t, colorStartMs, 1100L))
        val dots = 1f - emphasized(window(t, colorStartMs, 800L))
        val paletteMode: RecapPaletteMode
        val paletteMix: Float
        when {
            t < colorStartMs -> {
                paletteMode = RecapPaletteMode.DARK
                paletteMix = 1f
            }
            artist != null && song != null -> {
                if (t < song.startMs) {
                    paletteMode = RecapPaletteMode.ARTIST
                    paletteMix = 1f
                } else {
                    val mixT = fastOutSlowIn(window(t, song.startMs, 1100L))
                    if (mixT < 1f) {
                        paletteMode = RecapPaletteMode.ARTIST_TO_SONG
                        paletteMix = mixT
                    } else {
                        paletteMode = RecapPaletteMode.SONG
                        paletteMix = 1f
                    }
                }
            }
            artist != null -> {
                paletteMode = RecapPaletteMode.ARTIST
                paletteMix = 1f
            }
            else -> {
                paletteMode = RecapPaletteMode.SONG
                paletteMix = 1f
            }
        }

        // 3. Logo side (left -> right for colored pages -> left on EndPage)
        val logoToRight = emphasized(window(t, colorStartMs, 850L))
        val logoBackLeft = emphasized(window(t, e0, 780L))
        val logoSide = (logoToRight * (1f - logoBackLeft)).coerceIn(0f, 1f)

        // 4. Avatar position, ring, pop & alpha
        val avPageFrom: Int
        val avPageTo: Int
        val avMoveT: Float
        when {
            t < journey.startMs -> {
                avPageFrom = RecapFrameValues.PAGE_HEY
                avPageTo = RecapFrameValues.PAGE_HEY
                avMoveT = 1f
            }
            t < month.startMs -> {
                avPageFrom = RecapFrameValues.PAGE_HEY
                avPageTo = RecapFrameValues.PAGE_JOURNEY
                avMoveT = emphasized(window(t, journey.startMs, 850L))
            }
            t < weekday.startMs -> {
                avPageFrom = RecapFrameValues.PAGE_JOURNEY
                avPageTo = RecapFrameValues.PAGE_MONTH
                avMoveT = emphasized(window(t, month.startMs + 80L, 820L))
            }
            t < colorStartMs -> {
                avPageFrom = RecapFrameValues.PAGE_MONTH
                avPageTo = RecapFrameValues.PAGE_WEEKDAY
                avMoveT = emphasized(window(t, weekday.startMs + 80L, 820L))
            }
            else -> {
                avPageFrom = RecapFrameValues.PAGE_WEEKDAY
                avPageTo = RecapFrameValues.PAGE_ARTISTS
                avMoveT = emphasized(window(t, colorStartMs, 850L))
            }
        }
        val avPop = easeOutBackClamped(window(t, hey.startMs + 340L, 680L), 1.45f)
        val avAppear = if (t >= hey.startMs + 320L) 1f else 0f
        val avFadeOut = 1f - emphasized(window(t, e0, 480L))
        val avAlpha = (avAppear * avFadeOut).coerceIn(0f, 1f)
        val avRing = emphasized(window(t, journey.startMs, 850L))
        val avRingAlpha = 1f - emphasized(window(t, colorStartMs, 620L))

        // 5. Giant background words (90-degree warp on Hey -> Journey -> Month -> Weekday -> fade -> MOCHY back on End)
        val textRotation = lerp(90f, 0f, fastOutSlowIn(window(t, hey.startMs, 980L)))
        val textScale = lerp(1f, 0.74f, fastOutSlowIn(window(t, hey.startMs, 980L)))
        val textOffsetYDp = when {
            t < journey.startMs -> 0f
            t < weekday.startMs -> lerp(0f, -46f, emphasized(window(t, journey.startMs, 900L)))
            t < e0 -> lerp(-46f, 0f, emphasized(window(t, weekday.startMs + 220L, 950L)))
            else -> 0f
        }
        val textFadeOut = 1f - fastOutSlowIn(window(t, colorStartMs, 780L))
        val textEndIn = lerp(0f, 0.9f, emphasized(window(t, e0 + 240L, 950L)))
        val textAlpha = if (t < e0) textFadeOut else textEndIn

        val textWord: RecapGiantWord
        when {
            t < month.startMs + 220L -> {
                textWord = RecapGiantWord.MOCHY
                // Start with -1..1 filled, slide in -2,2,-3,3,-4,4 during Hey
                val order = intArrayOf(-2, 2, -3, 3, -4, 4)
                for (r in -1..1) rowFills[r + RecapFrameValues.ROW_HALF] = 1f
                order.forEachIndexed { idx, r ->
                    rowFills[r + RecapFrameValues.ROW_HALF] = fastOutSlowIn(window(t, hey.startMs + idx * 55L, 760L))
                }
                // During Journey, rows -1..4 slide out to make space
                if (t >= journey.startMs) {
                    for (r in -1..4) {
                        val outT = fastOutSlowIn(window(t, journey.startMs + (r + 1) * 50L, 520L))
                        if (outT > 0f) {
                            rowFills[r + RecapFrameValues.ROW_HALF] *= (1f - outT)
                            rowSides[r + RecapFrameValues.ROW_HALF] = -1f
                        }
                    }
                }
            }

            t < weekday.startMs + 360L -> {
                textWord = RecapGiantWord.MONTH
                for (r in -4..-2) rowFills[r + RecapFrameValues.ROW_HALF] = 1f
                for (r in -1..3) {
                    val idx = r + RecapFrameValues.ROW_HALF
                    val inT = fastOutSlowIn(window(t, month.startMs + 220L + (r + 1) * 65L, 780L))
                    rowFills[idx] = inT
                    if (t >= weekday.startMs) {
                        val outT = fastOutSlowIn(window(t, weekday.startMs + (3 - r) * 45L, 380L))
                        if (outT > 0f) {
                            rowFills[idx] *= (1f - outT)
                            rowSides[idx] = -1f
                        }
                    }
                }
            }

            t < e0 -> {
                textWord = RecapGiantWord.WEEKDAY
                for (r in -4..4) {
                    val idx = r + RecapFrameValues.ROW_HALF
                    rowFills[idx] = fastOutSlowIn(window(t, weekday.startMs + 360L + (r + 4) * 50L, 760L))
                }
            }

            else -> {
                textWord = RecapGiantWord.MOCHY
                for (r in -4..4) {
                    val idx = r + RecapFrameValues.ROW_HALF
                    rowFills[idx] = fastOutSlowIn(window(t, e0 + (r + 4) * 45L, 820L))
                }
            }
        }

        // 6. End Profile ID Card
        val cardVisible = t >= e0
        val cardYProgress = if (cardVisible) emphasized(window(t, e0 + 260L, 960L)) else 0f
        val cardRot = if (cardVisible) lerp(-8f, 0f, easeOutBackClamped(window(t, e0 + 220L, 1200L), 0.7f).coerceAtMost(1.06f)) else 0f
        val cardAlpha = if (cardVisible) 1f else 0f

        return RecapFrameValues(
            timeMs = t,
            reveal = 1f,
            backdrop = 1f,
            band = 1f,
            headerClip = 0f,
            toHeader = 0f,
            mesh = mesh,
            dots = dots,
            paletteMode = paletteMode,
            paletteMix = paletteMix,
            logoSide = logoSide,
            cardVisible = cardVisible,
            cardYProgress = cardYProgress,
            cardRot = cardRot,
            cardAlpha = cardAlpha,
            avPageFrom = avPageFrom,
            avPageTo = avPageTo,
            avMoveT = avMoveT,
            avRing = avRing,
            avRingAlpha = avRingAlpha,
            avAlpha = avAlpha,
            avPop = avPop,
            textWord = textWord,
            textRotation = textRotation,
            textScale = textScale,
            textCenterX = 0.5f,
            textOffsetYDp = textOffsetYDp,
            textAlpha = textAlpha,
            textRowFills = rowFills,
            textRowSides = rowSides,
            pageEnters = enters,
            pageExits = exits,
            spin = spinVal,
            spinGlow = spinGlow,
            heroArtist = heroArtist,
            gridDrift = gridDrift,
            gridPick = gridPick,
            heroCover = heroCover,
            heroCoverUp = heroCoverUp,
            receiptScroll = receiptScroll,
        )
    }

    // -----------------------------------------------------------------------------------------
    // Math & Easing (pure JVM, matching Mochy's Compose curves)

    private fun window(tMs: Long, startMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return if (tMs >= startMs) 1f else 0f
        return ((tMs - startMs).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun easeInOutCubic(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return if (c < 0.5f) 4f * c * c * c else 1f - (-2f * c + 2f).let { it * it * it } / 2f
    }

    private fun easeOutBackClamped(t: Float, overshoot: Float): Float {
        val c = t.coerceIn(0f, 1f)
        if (c <= 0f) return 0f
        if (c >= 1f) return 1f
        val c3 = overshoot + 1f
        val x = c - 1f
        return (1f + c3 * x * x * x + overshoot * x * x).coerceAtLeast(0f)
    }

    private fun emphasized(t: Float): Float = cubicBezier(0.2f, 0f, 0f, 1f, t)
    private fun emphasizedAccelerate(t: Float): Float = cubicBezier(0.3f, 0f, 0.8f, 0.15f, t)
    private fun fastOutSlowIn(t: Float): Float = cubicBezier(0.4f, 0f, 0.2f, 1f, t)
    private fun artistSpinEasing(t: Float): Float = cubicBezier(0.35f, 0.1f, 0.15f, 1f, t)

    /** Exact 1D cubic Bezier curve evaluation matching Compose CubicBezierEasing. */
    private fun cubicBezier(x1: Float, y1: Float, x2: Float, y2: Float, x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        var lo = 0f
        var hi = 1f
        var u = x
        repeat(16) {
            val curX = sampleBezier(x1, x2, u)
            if (abs(curX - x) < 1e-5f) return sampleBezier(y1, y2, u).coerceIn(0f, 1f)
            if (curX < x) lo = u else hi = u
            u = (lo + hi) * 0.5f
        }
        return sampleBezier(y1, y2, u).coerceIn(0f, 1f)
    }

    private fun sampleBezier(p1: Float, p2: Float, u: Float): Float {
        val inv = 1f - u
        return 3f * inv * inv * u * p1 + 3f * inv * u * u * p2 + u * u * u
    }
}
