package com.mochyy.mochymusic.modules

import com.mochyy.mochymusic.module.TrackQuery
import com.mochyy.mochymusic.module.applemusic.AppleMusicModule
import com.mochyy.mochymusic.module.monochrome.MonochromeModule
import com.mochyy.mochymusic.module.soundcloud.SoundCloudModule
import com.mochyy.mochymusic.module.zingmp3.ZingMp3Module
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartProviderRouterTest {

    private val allBaseOrder = listOf(
        MonochromeModule.Id,
        AppleMusicModule.Id,
        ZingMp3Module.Id,
        SoundCloudModule.Id,
        SourceModules.YouTubeId,
    )

    @Test
    fun detectsVietnameseTracksByDiacriticsAndArtistsWithoutForeignFalsePositives() {
        assertTrue(VietnameseTrackDetector.isVietnameseTrack("Nơi Này Có Anh", listOf("Sơn Tùng M-TP")))
        assertTrue(VietnameseTrackDetector.isVietnameseTrack("Chạy Ngay Đi", listOf("Son Tung M-TP")))
        assertTrue(VietnameseTrackDetector.isVietnameseTrack("Exit Sign", listOf("HIEUTHUHAI", "marzuz")))
        assertTrue(VietnameseTrackDetector.isVietnameseTrack("Từng Quen", listOf("Wren Evans")))
        assertTrue(VietnameseTrackDetector.isVietnameseTrack("Simple Cypher", listOf("tlinh x Low G")))
        assertTrue(VietnameseTrackDetector.isVietnameseTrack("Chìm Sâu", listOf("RPT MCK")))
        assertTrue(VietnameseTrackDetector.isVietnameseTrack("Summer Mix (V-Pop)", listOf("Unknown DJ")))

        // Foreign tracks with shared Latin accents or ambiguous international artist names must NOT be misclassified
        assertFalse(VietnameseTrackDetector.isVietnameseTrack("Déjà Vu", listOf("Olivia Rodrigo")))
        assertFalse(VietnameseTrackDetector.isVietnameseTrack("Café con Leche", listOf("Celia Cruz")))
        assertFalse(VietnameseTrackDetector.isVietnameseTrack("Blinding Lights", listOf("The Weeknd")))
        assertFalse(VietnameseTrackDetector.isVietnameseTrack("アイドル", listOf("YOASOBI")))
        assertFalse(VietnameseTrackDetector.isVietnameseTrack("Ditto", listOf("NewJeans")))
        assertFalse(VietnameseTrackDetector.isVietnameseTrack("Ashes in the Snow", listOf("MONO")))
        assertFalse(VietnameseTrackDetector.isVietnameseTrack("Gymnopédie No. 1", listOf("Erik Satie")))
        assertFalse(VietnameseTrackDetector.isVietnameseTrack("Sunset", listOf("Orange")))
        assertFalse(VietnameseTrackDetector.isVietnameseTrack(null))

        // Decomposed Unicode NFD Vietnamese text (common in Apple Music / YouTube metadata) MUST still be detected
        val nfdTitle = java.text.Normalizer.normalize("Nơi Này Có Anh", java.text.Normalizer.Form.NFD)
        assertTrue(VietnameseTrackDetector.isVietnameseTrack(nfdTitle, listOf("Unknown")))
    }

    @Test
    fun wifiPrioritizesLosslessAndCellularPrioritizesFastSourcesForForeignSongs() {
        val policy = SmartRoutingPolicy()
        val userOrder = listOf(
            SourceModules.YouTubeId,
            ZingMp3Module.Id,
            AppleMusicModule.Id,
            MonochromeModule.Id,
        )

        val wifiOrder = SmartProviderRouter.computeOrder(
            baseOrder = userOrder,
            usable = { true },
            skip = emptySet(),
            networkTier = NetworkTier.WIFI_UNMETERED,
            isVietnamese = false,
            remembered = null,
            policy = policy,
        )
        assertEquals(MonochromeModule.Id, wifiOrder[0])
        assertEquals(AppleMusicModule.Id, wifiOrder[1])

        val cellOrder = SmartProviderRouter.computeOrder(
            baseOrder = userOrder,
            usable = { true },
            skip = emptySet(),
            networkTier = NetworkTier.CELLULAR_METERED,
            isVietnamese = false,
            remembered = null,
            policy = policy,
        )
        // Foreign tracks on 4G prioritize instant YouTube Music first, then lightweight Zing MP3
        assertEquals(SourceModules.YouTubeId, cellOrder[0])
        assertEquals(ZingMp3Module.Id, cellOrder[1])
    }

    @Test
    fun vietnameseSongsPrioritizeZingMp3ThenYouTubeRegardlessOfNetwork() {
        val policy = SmartRoutingPolicy()
        val vnTrack = TrackQuery(title = "Nơi Này Có Anh", artists = listOf("Sơn Tùng M-TP"))
        assertTrue(VietnameseTrackDetector.isVietnameseTrack(vnTrack))

        val wifiOrder = SmartProviderRouter.computeOrder(
            baseOrder = allBaseOrder,
            usable = { true },
            skip = emptySet(),
            networkTier = NetworkTier.WIFI_UNMETERED,
            isVietnamese = true,
            remembered = null,
            policy = policy,
        )
        assertEquals(ZingMp3Module.Id, wifiOrder[0])
        assertEquals(SourceModules.YouTubeId, wifiOrder[1])
        assertEquals(MonochromeModule.Id, wifiOrder[2])

        // If Zing MP3 is disabled, YouTube takes #1 cleanly and Zing MP3 is never injected
        val noZingOrder = SmartProviderRouter.computeOrder(
            baseOrder = allBaseOrder,
            usable = { it != ZingMp3Module.Id },
            skip = emptySet(),
            networkTier = NetworkTier.WIFI_UNMETERED,
            isVietnamese = true,
            remembered = null,
            policy = policy,
        )
        assertEquals(SourceModules.YouTubeId, noZingOrder[0])
        assertFalse(ZingMp3Module.Id in noZingOrder)
    }

    @Test
    fun rememberedBestProviderWinsOnCompatibleNetworkButNotHeavyLosslessOnCellular() {
        val policy = SmartRoutingPolicy()
        val rememberedMono = RememberedProvider(
            songId = "song_1",
            providerId = MonochromeModule.Id,
            networkTier = NetworkTier.WIFI_UNMETERED.name,
            updatedAtMs = 10_000L,
        )

        // On Wi-Fi, remembered Monochrome is #1 even on a Vietnamese track
        val wifiWithMemory = SmartProviderRouter.computeOrder(
            baseOrder = allBaseOrder,
            usable = { true },
            skip = emptySet(),
            networkTier = NetworkTier.WIFI_UNMETERED,
            isVietnamese = true,
            remembered = rememberedMono,
            policy = policy,
        )
        assertEquals(MonochromeModule.Id, wifiWithMemory[0])

        // On 4G/5G cellular with networkAware=true, heavy Monochrome/Apple Music memory does NOT override fast sources
        val cellWithMonoMemory = SmartProviderRouter.computeOrder(
            baseOrder = allBaseOrder,
            usable = { true },
            skip = emptySet(),
            networkTier = NetworkTier.CELLULAR_METERED,
            isVietnamese = false,
            remembered = rememberedMono,
            policy = policy,
        )
        assertEquals(SourceModules.YouTubeId, cellWithMonoMemory[0])
        assertEquals(ZingMp3Module.Id, cellWithMonoMemory[1])

        val rememberedApple = RememberedProvider(
            songId = "song_1",
            providerId = AppleMusicModule.Id,
            networkTier = NetworkTier.WIFI_UNMETERED.name,
            updatedAtMs = 10_000L,
        )
        val cellWithAppleMemory = SmartProviderRouter.computeOrder(
            baseOrder = allBaseOrder,
            usable = { true },
            skip = emptySet(),
            networkTier = NetworkTier.CELLULAR_METERED,
            isVietnamese = false,
            remembered = rememberedApple,
            policy = policy,
        )
        assertEquals(SourceModules.YouTubeId, cellWithAppleMemory[0])

        // Automatic 4G YouTube memory must NOT poison Wi-Fi lossless priority for foreign tracks
        val rememberedCellYt = RememberedProvider(
            songId = "song_en",
            providerId = SourceModules.YouTubeId,
            networkTier = NetworkTier.CELLULAR_METERED.name,
            updatedAtMs = 10_000L,
            manualPick = false,
        )
        val wifiAfterCellPlay = SmartProviderRouter.computeOrder(
            baseOrder = allBaseOrder,
            usable = { true },
            skip = emptySet(),
            networkTier = NetworkTier.WIFI_UNMETERED,
            isVietnamese = false,
            remembered = rememberedCellYt,
            policy = policy,
        )
        assertEquals(MonochromeModule.Id, wifiAfterCellPlay[0])

        // Explicit manual pick on Wi-Fi IS honored
        val manualYtPick = rememberedCellYt.copy(manualPick = true)
        val wifiWithManualPick = SmartProviderRouter.computeOrder(
            baseOrder = allBaseOrder,
            usable = { true },
            skip = emptySet(),
            networkTier = NetworkTier.WIFI_UNMETERED,
            isVietnamese = false,
            remembered = manualYtPick,
            policy = policy,
        )
        assertEquals(SourceModules.YouTubeId, wifiWithManualPick[0])

        // Failed remembered provider in `skip` is cleanly bypassed
        val skippedMemory = SmartProviderRouter.computeOrder(
            baseOrder = allBaseOrder,
            usable = { true },
            skip = setOf(MonochromeModule.Id),
            networkTier = NetworkTier.WIFI_UNMETERED,
            isVietnamese = false,
            remembered = rememberedMono,
            policy = policy,
        )
        assertFalse(MonochromeModule.Id in skippedMemory)
        assertEquals(AppleMusicModule.Id, skippedMemory[0])
    }

    @Test
    fun disabledMasterTogglePreservesExactManualOrder() {
        val manual = listOf(SoundCloudModule.Id, SourceModules.YouTubeId, MonochromeModule.Id)
        val out = SmartProviderRouter.computeOrder(
            baseOrder = manual,
            usable = { true },
            skip = emptySet(),
            networkTier = NetworkTier.WIFI_UNMETERED,
            isVietnamese = true,
            remembered = RememberedProvider("s", MonochromeModule.Id),
            policy = SmartRoutingPolicy(enabled = false),
        )
        assertEquals(manual, out)
    }
}
