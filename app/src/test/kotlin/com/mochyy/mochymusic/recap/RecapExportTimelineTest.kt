package com.mochyy.mochymusic.recap

import com.mochyy.mochymusic.ui.screens.recap.export.RecapExportTimeline
import com.mochyy.mochymusic.ui.screens.recap.export.RecapFrameValues
import com.mochyy.mochymusic.ui.screens.recap.export.RecapGiantWord
import com.mochyy.mochymusic.ui.screens.recap.export.RecapPaletteMode
import com.mochyy.mochymusic.ui.screens.recap.export.RecapPart
import com.mochyy.mochymusic.ui.screens.recap.export.RecapSegmentKind
import com.mochyy.mochymusic.ui.screens.recap.export.availableRecapParts
import com.mochyy.mochymusic.ui.screens.recap.export.selectSoundtrackSongId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecapExportTimelineTest {

    @Test
    fun fullTimelineContainsEveryMochyBeatAndIsContinuousAt60Fps() {
        val plan = RecapExportTimeline.buildFullTimeline(
            hasHeroArtist = true,
            hasHeroSong = true,
            artistCount = 10,
        )
        assertTrue("Full video should be ~24-30s", plan.totalDurationMs in 24_000L..30_000L)
        assertEquals(
            listOf(
                RecapSegmentKind.HEY,
                RecapSegmentKind.JOURNEY,
                RecapSegmentKind.MONTH,
                RecapSegmentKind.WEEKDAY,
                RecapSegmentKind.STREAK,
                RecapSegmentKind.ARTIST_SPIN_REVEAL,
                RecapSegmentKind.SONG_GRID_REVEAL,
                RecapSegmentKind.PERSONA,
                RecapSegmentKind.RECEIPT,
                RecapSegmentKind.END_CARD,
            ),
            plan.segments.map { it.kind },
        )

        // Verify monotonicity & bounds across 60fps samples including start, boundaries, and past-end clamping
        val frameStepMs = 1000.0 / 60.0
        val sampleTimes = buildList {
            add(-100L)
            var t = 0.0
            while (t <= plan.totalDurationMs + 50.0) {
                add(t.toLong())
                t += frameStepMs
            }
            add(plan.totalDurationMs + 5_000L)
        }
        var maxReceiptScroll = 0f
        var sawMesh = false
        var sawStreak = false
        var sawPersona = false
        var sawEndCard = false
        for (ms in sampleTimes) {
            val f = RecapExportTimeline.evaluateFrame(plan, ms)
            assertValidFrame(f)
            if (f.mesh > 0.95f) sawMesh = true
            if (f.pageEnters[RecapFrameValues.PAGE_STREAK] > 0.95f) sawStreak = true
            if (f.pageEnters[RecapFrameValues.PAGE_PERSONA] > 0.95f) sawPersona = true
            if (f.cardVisible && f.cardYProgress > 0.95f) sawEndCard = true
            if (f.receiptScroll > maxReceiptScroll) maxReceiptScroll = f.receiptScroll
        }
        assertTrue("Full timeline must transition into Mochy's MeshGradient", sawMesh)
        assertTrue("Full timeline must include Streak page", sawStreak)
        assertTrue("Full timeline must include Persona page", sawPersona)
        assertTrue("Full timeline must scroll receipt all the way to END OF LINE", maxReceiptScroll >= 0.99f)
        assertTrue("Full timeline must conclude with Profile ID Card", sawEndCard)
    }

    @Test
    fun fullTimelineHandlesMissingArtistAndMissingSongGracefully() {
        val noArtist = RecapExportTimeline.buildFullTimeline(hasHeroArtist = false, hasHeroSong = true, artistCount = 0)
        assertFalse(noArtist.segments.any { it.kind == RecapSegmentKind.ARTIST_SPIN_REVEAL })
        assertTrue(noArtist.segments.any { it.kind == RecapSegmentKind.SONG_GRID_REVEAL })
        assertTrue(noArtist.segments.any { it.kind == RecapSegmentKind.STREAK })
        assertTrue(noArtist.segments.any { it.kind == RecapSegmentKind.PERSONA })
        assertValidFrame(RecapExportTimeline.evaluateFrame(noArtist, noArtist.totalDurationMs / 2))
        assertValidFrame(RecapExportTimeline.evaluateFrame(noArtist, noArtist.totalDurationMs))

        val neither = RecapExportTimeline.buildFullTimeline(hasHeroArtist = false, hasHeroSong = false, artistCount = 0)
        assertFalse(neither.segments.any { it.kind == RecapSegmentKind.ARTIST_SPIN_REVEAL })
        assertFalse(neither.segments.any { it.kind == RecapSegmentKind.SONG_GRID_REVEAL })
        assertTrue(neither.segments.any { it.kind == RecapSegmentKind.STREAK })
        assertTrue(neither.segments.any { it.kind == RecapSegmentKind.PERSONA })
        assertTrue(neither.segments.any { it.kind == RecapSegmentKind.RECEIPT })
        assertTrue(neither.segments.any { it.kind == RecapSegmentKind.END_CARD })
        assertValidFrame(RecapExportTimeline.evaluateFrame(neither, 0L))
        assertValidFrame(RecapExportTimeline.evaluateFrame(neither, neither.totalDurationMs / 2))
        assertValidFrame(RecapExportTimeline.evaluateFrame(neither, neither.totalDurationMs))
    }

    @Test
    fun everyPartStillFrameIsSettledCleanAndValid() {
        for (part in RecapPart.entries) {
            val still = RecapExportTimeline.stillFrameFor(part, artistCount = 10)
            assertValidFrame(still)
            when (part) {
                RecapPart.JOURNEY -> {
                    assertEquals(1f, still.pageEnters[RecapFrameValues.PAGE_JOURNEY], 1e-4f)
                    assertEquals(0f, still.pageExits[RecapFrameValues.PAGE_JOURNEY], 1e-4f)
                    assertEquals(1f, still.avAlpha, 1e-4f)
                }
                RecapPart.MONTH -> {
                    assertEquals(1f, still.pageEnters[RecapFrameValues.PAGE_MONTH], 1e-4f)
                    assertEquals(0f, still.pageExits[RecapFrameValues.PAGE_MONTH], 1e-4f)
                }
                RecapPart.WEEKDAY -> {
                    assertEquals(1f, still.pageEnters[RecapFrameValues.PAGE_WEEKDAY], 1e-4f)
                    assertEquals(0f, still.pageExits[RecapFrameValues.PAGE_WEEKDAY], 1e-4f)
                }
                RecapPart.STREAK -> {
                    assertEquals(1f, still.pageEnters[RecapFrameValues.PAGE_STREAK], 1e-4f)
                    assertEquals(0f, still.pageExits[RecapFrameValues.PAGE_STREAK], 1e-4f)
                    assertEquals(RecapGiantWord.STREAK, still.textWord)
                }
                RecapPart.TOP_ARTIST -> {
                    assertEquals(1f, still.pageEnters[RecapFrameValues.PAGE_ARTIST], 1e-4f)
                    assertEquals(0f, still.pageExits[RecapFrameValues.PAGE_ARTIST], 1e-4f)
                    assertEquals(1f, still.heroArtist, 1e-4f)
                    assertEquals(1f, still.mesh, 1e-4f)
                    assertEquals(RecapPaletteMode.ARTIST, still.paletteMode)
                }
                RecapPart.TOP_SONG -> {
                    assertEquals(1f, still.pageEnters[RecapFrameValues.PAGE_SONG], 1e-4f)
                    assertEquals(0f, still.pageExits[RecapFrameValues.PAGE_SONG], 1e-4f)
                    assertEquals(1f, still.heroCover, 1e-4f)
                    assertEquals(1f, still.heroCoverUp, 1e-4f)
                    assertEquals(RecapPaletteMode.SONG, still.paletteMode)
                }
                RecapPart.PERSONA -> {
                    assertEquals(1f, still.pageEnters[RecapFrameValues.PAGE_PERSONA], 1e-4f)
                    assertEquals(0f, still.pageExits[RecapFrameValues.PAGE_PERSONA], 1e-4f)
                    assertEquals(RecapGiantWord.PERSONA, still.textWord)
                    assertEquals(1f, still.mesh, 1e-4f)
                }
                RecapPart.RECEIPT -> {
                    assertEquals(1f, still.pageEnters[RecapFrameValues.PAGE_RECEIPT], 1e-4f)
                    assertEquals(0f, still.pageExits[RecapFrameValues.PAGE_RECEIPT], 1e-4f)
                    assertEquals(0f, still.receiptScroll, 1e-4f)
                }
                RecapPart.SUMMARY_CARD -> {
                    assertEquals(1f, still.pageEnters[RecapFrameValues.PAGE_END], 1e-4f)
                    assertTrue(still.cardVisible)
                    assertEquals(1f, still.cardYProgress, 1e-4f)
                    assertEquals(0f, still.cardRot, 1e-4f)
                }
            }
        }
    }

    @Test
    fun singlePartTimelinesProduceMonotonicReceiptScrollAndValidFrames() {
        for (part in RecapPart.entries) {
            val plan = RecapExportTimeline.buildPartTimeline(
                part = part,
                hasHeroArtist = true,
                hasHeroSong = true,
                artistCount = 8,
            )
            assertTrue("${part.id} clip should be 4.5-8s", plan.totalDurationMs in 4_500L..8_000L)
            var prevReceiptScroll = 0f
            for (step in 0..40) {
                val ms = plan.totalDurationMs * step / 40L
                val frame = RecapExportTimeline.evaluateFrame(plan, ms)
                assertValidFrame(frame)
                if (part == RecapPart.RECEIPT) {
                    assertTrue(
                        "Receipt scroll must be monotonic non-decreasing",
                        frame.receiptScroll >= prevReceiptScroll - 1e-4f,
                    )
                    prevReceiptScroll = frame.receiptScroll
                }
            }
            if (part == RecapPart.RECEIPT) {
                assertEquals(1f, prevReceiptScroll, 1e-3f)
            }
        }
    }

    @Test
    fun availablePartsAndSoundtrackPriorityWorkAsExpected() {
        assertEquals(9, availableRecapParts(hasHeroArtist = true, hasHeroSong = true).size)
        assertEquals(8, availableRecapParts(hasHeroArtist = false, hasHeroSong = true).size)
        assertEquals(7, availableRecapParts(hasHeroArtist = false, hasHeroSong = false).size)

        // Full video prefers heroSong, falls back to heroArtist.topSong
        assertEquals("song1", selectSoundtrackSongId("song1", "artistSong1", part = null))
        assertEquals("artistSong1", selectSoundtrackSongId(null, "artistSong1", part = null))
        assertNull(selectSoundtrackSongId(null, null, part = null))

        // Top Artist single-part video prefers heroArtist.topSong, falls back to heroSong
        assertEquals("artistSong1", selectSoundtrackSongId("song1", "artistSong1", part = RecapPart.TOP_ARTIST))
        assertEquals("song1", selectSoundtrackSongId("song1", null, part = RecapPart.TOP_ARTIST))
    }

    private fun assertValidFrame(f: RecapFrameValues) {
        val unitValues = floatArrayOf(
            f.reveal, f.backdrop, f.band, f.headerClip, f.toHeader,
            f.mesh, f.dots, f.paletteMix, f.logoSide, f.cardYProgress,
            f.cardAlpha, f.avMoveT, f.avRing, f.avRingAlpha, f.avAlpha,
            f.textAlpha, f.spinGlow, f.heroArtist, f.gridDrift, f.gridPick,
            f.heroCover, f.heroCoverUp, f.receiptScroll,
        )
        for (v in unitValues) {
            assertTrue("Value must be finite: $v", v.isFinite())
            assertTrue("Unit value out of [0,1]: $v", v in -0.001f..1.001f)
        }
        assertTrue(f.avPop.isFinite() && f.avPop >= 0f)
        assertTrue(f.spin.isFinite())
        assertTrue(f.cardRot.isFinite())
        assertTrue(f.textRotation.isFinite())
        assertTrue(f.textScale.isFinite() && f.textScale > 0f)
        assertEquals(RecapFrameValues.PAGE_COUNT, f.pageEnters.size)
        assertEquals(RecapFrameValues.PAGE_COUNT, f.pageExits.size)
        assertEquals(RecapFrameValues.ROW_COUNT, f.textRowFills.size)
        for (i in 0 until RecapFrameValues.PAGE_COUNT) {
            assertTrue(f.pageEnters[i] in 0f..1f)
            assertTrue(f.pageExits[i] in 0f..1f)
        }
        for (i in 0 until RecapFrameValues.ROW_COUNT) {
            assertTrue(f.textRowFills[i] in 0f..1f)
        }
    }
}
