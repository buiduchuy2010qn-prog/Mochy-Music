package com.mochyy.mochymusic.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartLyricsLanguageDetectorTest {

    @Test
    fun vietnameseLyricsHideTranslationAndRomanization() {
        val viLines = listOf(
            "[00:12.34]Em là ai từ đâu bước đến nơi đây dịu dàng pha sắc nắng",
            "[00:18.20]Cầm tay anh dựa vai anh kề bên anh nơi này có anh",
            "[00:24.10]Mùa xuân đến bình yên cho anh những giấc mơ",
        )
        val decision = SmartLyricsLanguageDetector.decide(
            smartLyricsEnabled = true,
            lines = viLines,
            configuredTargetLanguage = null,
        )
        assertEquals(LyricsLanguage.VIETNAMESE, decision.detectedLanguage)
        assertFalse(decision.shouldAutoTranslate)
        assertTrue(decision.shouldHideRomanization)
        assertFalse(decision.shouldForceRomanization)
    }

    @Test
    fun unaccentedVietnameseKaraokeLyricsStillDetectAsVietnamese() {
        val lines = listOf(
            "Anh yeu em nhieu lam nhung khong the noi ra",
            "Mot ngay mua roi ben noi nay nho em",
        )
        val detected = SmartLyricsLanguageDetector.detectLanguage(lines)
        assertEquals(LyricsLanguage.VIETNAMESE, detected)
    }

    @Test
    fun englishLyricsAutoTranslateToVietnameseWithoutRomanization() {
        val enLines = listOf(
            "I've been tryna call, I've been on my own for long enough",
            "Maybe you can show me how to love, maybe",
            "I said, ooh, I'm blinded by the lights",
            "No, I can't sleep until I feel your touch",
        )
        val decision = SmartLyricsLanguageDetector.decide(
            smartLyricsEnabled = true,
            lines = enLines,
            configuredTargetLanguage = null,
        )
        assertEquals(LyricsLanguage.ENGLISH, decision.detectedLanguage)
        assertTrue(decision.shouldAutoTranslate)
        assertEquals("vi", decision.targetLanguage)
        assertEquals("en", decision.sourceLanguageHint)
        assertFalse(decision.shouldForceRomanization)
        assertFalse(decision.shouldHideRomanization)

        // Legacy upstream default "en" must NOT cause an EN->EN no-op under Smart Lyrics
        val withLegacyEnTarget = SmartLyricsLanguageDetector.decide(
            smartLyricsEnabled = true,
            lines = enLines,
            configuredTargetLanguage = "en",
        )
        assertEquals("vi", withLegacyEnTarget.targetLanguage)
    }

    @Test
    fun japaneseKoreanChineseAutoTranslateToVietnameseAndForceRomanization() {
        // J-Pop with English chorus still resolves as JAPANESE
        val jaLines = listOf(
            "無敵の笑顔で荒らすメディア",
            "知りたいその秘密ミステリアス",
            "You are my perfect ultimate idol",
        )
        val jaDecision = SmartLyricsLanguageDetector.decide(true, jaLines, null)
        assertEquals(LyricsLanguage.JAPANESE, jaDecision.detectedLanguage)
        assertTrue(jaDecision.shouldAutoTranslate)
        assertEquals("vi", jaDecision.targetLanguage)
        assertEquals("ja", jaDecision.sourceLanguageHint)
        assertTrue(jaDecision.shouldForceRomanization)

        // K-Pop with English hook still resolves as KOREAN
        val koLines = listOf(
            "Stay in the middle, like you a little",
            "훌쩍 커버렸어 함께한 기억처럼",
            "널 보는 내 마음은 어느새 여름 지나 가을",
        )
        val koDecision = SmartLyricsLanguageDetector.decide(true, koLines, null)
        assertEquals(LyricsLanguage.KOREAN, koDecision.detectedLanguage)
        assertTrue(koDecision.shouldAutoTranslate)
        assertEquals("vi", koDecision.targetLanguage)
        assertEquals("ko", koDecision.sourceLanguageHint)
        assertTrue(koDecision.shouldForceRomanization)

        // C-Pop (Han characters without Kana) resolves as CHINESE
        val zhLines = listOf(
            "窗外的麻雀在电线杆上多嘴",
            "你说这一句很有夏天的感觉",
            "手中的铅笔在纸上来来回回",
        )
        val zhDecision = SmartLyricsLanguageDetector.decide(true, zhLines, null)
        assertEquals(LyricsLanguage.CHINESE, zhDecision.detectedLanguage)
        assertTrue(zhDecision.shouldAutoTranslate)
        assertEquals("vi", zhDecision.targetLanguage)
        assertEquals("zh-CN", zhDecision.sourceLanguageHint)
        assertTrue(zhDecision.shouldForceRomanization)
    }

    @Test
    fun frenchSpanishInstrumentalAndEmptyLyricsAreNotMisclassified() {
        val french = listOf("Alors on danse, déjà vu dans le café ce soir")
        assertEquals(LyricsLanguage.OTHER, SmartLyricsLanguageDetector.detectLanguage(french))

        val instrumental = listOf("♪ ♪ ♪", "...")
        assertEquals(LyricsLanguage.UNKNOWN, SmartLyricsLanguageDetector.detectLanguage(instrumental))
        assertEquals(LyricsLanguage.UNKNOWN, SmartLyricsLanguageDetector.detectLanguage(emptyList()))
    }

    @Test
    fun disabledSmartLyricsReturnsPassiveDecisionAndManualOverrideIsRespected() {
        val enLines = listOf("Never gonna give you up, never gonna let you down")
        val disabled = SmartLyricsLanguageDetector.decide(
            smartLyricsEnabled = false,
            lines = enLines,
            configuredTargetLanguage = "vi",
        )
        assertFalse(disabled.shouldAutoTranslate)
        assertFalse(disabled.shouldForceRomanization)
        assertFalse(disabled.shouldHideRomanization)

        // Manual override on current song is preserved until song changes
        LyricsTranslationHelper.onSongChanged("song_en", sessionWide = false)
        LyricsTranslationHelper.applySmartTranslationState("song_en", true)
        assertTrue(LyricsTranslationHelper.translationOn.value)

        // User taps "Show original" manually on song_en -> Smart Lyrics must not re-enable it
        LyricsTranslationHelper.triggerClearTranslations()
        assertFalse(LyricsTranslationHelper.translationOn.value)
        assertTrue(LyricsTranslationHelper.hasManualOverrideFor("song_en"))
        LyricsTranslationHelper.applySmartTranslationState("song_en", true)
        assertFalse("Manual clear must stay off on same song", LyricsTranslationHelper.translationOn.value)

        // Next song resets override so Smart Lyrics runs fresh again
        LyricsTranslationHelper.onSongChanged("song_ja", sessionWide = false)
        assertFalse(LyricsTranslationHelper.hasManualOverrideFor("song_ja"))
        LyricsTranslationHelper.applySmartTranslationState("song_ja", true)
        assertTrue(LyricsTranslationHelper.translationOn.value)
    }
}
