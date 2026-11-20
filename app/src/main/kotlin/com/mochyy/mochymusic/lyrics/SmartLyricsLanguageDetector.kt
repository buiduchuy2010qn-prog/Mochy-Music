package com.mochyy.mochymusic.lyrics

import java.text.Normalizer

enum class LyricsLanguage {
    VIETNAMESE,
    ENGLISH,
    JAPANESE,
    KOREAN,
    CHINESE,
    OTHER,
    UNKNOWN,
}

data class SmartLyricsDecision(
    val detectedLanguage: LyricsLanguage,
    val shouldAutoTranslate: Boolean,
    val targetLanguage: String,
    val sourceLanguageHint: String,
    val shouldForceRomanization: Boolean,
    val shouldHideRomanization: Boolean,
)

/**
 * Pure on-device lyrics language detector and Smart Lyrics policy engine (#5):
 * - Vietnamese lyrics -> automatically hide translation and romanization.
 * - English lyrics -> automatically translate to Vietnamese (no romanization).
 * - Japanese / Korean / Chinese lyrics -> automatically translate to Vietnamese AND enable
 *   Romaji / Romanization / Pinyin automatically without per-song manual toggling.
 */
object SmartLyricsLanguageDetector {

    private val TimestampTagRegex = Regex("\\[\\d{1,2}:\\d{2}(?:\\.\\d{1,3})?\\]|<\\d{1,2}:\\d{2}(?:\\.\\d{1,3})?>")
    private val MetaPrefixRegex = Regex("^\\s*(?:\\{[^}]+\\})*")
    private val MetadataHeaderRegex = Regex(
        "^\\s*(?:tiêu\\s*đề|ca\\s*sĩ|sáng\\s*tác|tác\\s*giả|lời\\s*bài\\s*hát|composer|lyricist|arr(?:anged)?\\s*by|title|artist|writer|producer|作词|作詞|作曲|编曲|編曲|制作人|製作人|混音|母带|演唱|监制|出品|词|詞|曲|작사|작곡|편곡|노래)\\s*[:：].*$",
        RegexOption.IGNORE_CASE,
    )
    private val WordSplitRegex = Regex("[^\\p{L}’']+")

    /** Characters exclusive to Vietnamese Quốc Ngữ (never used in French, Spanish, German, or English). */
    private val UniquelyVietnameseChars: Set<Char> = (
        "đĐăĂơƠưƯ" +
            "ắằẳẵặẮẰẲẴẶ" +
            "ấầẩẫậẤẦẨẪẬ" +
            "ếềểễệẾỀỂỄỆ" +
            "ốồổỗộỐỒỔỖỘ" +
            "ớờởỡợỚỜỞỠỢ" +
            "ứừửữựỨỪỬỮỰ" +
            "ảạẢẠ" +
            "ẻẽẹẺẼẸ" +
            "ỉĩịỈĨỊ" +
            "ỏọỎỌ" +
            "ủũụỦŨỤ" +
            "ỷỹỵỶỸỴ"
        ).toSet()

    private val VietnameseCommonWords: Set<String> = setOf(
        "anh", "em", "mình", "người", "yêu", "không", "thương", "nhớ", "đời", "lòng",
        "nhưng", "vì", "khi", "đã", "đang", "sẽ", "của", "cho", "với", "từ", "đến",
        "một", "ngày", "đêm", "mưa", "nắng", "gió", "mây", "trời", "hoa", "lá",
        "buồn", "vui", "đau", "khóc", "cười", "quên", "nhau", "bên", "nơi", "này",
    )

    private val UnaccentedVietnameseTokens: Set<String> = setOf(
        "anh", "em", "minh", "nguoi", "yeu", "khong", "thuong", "nho", "doi", "long",
        "nhung", "khi", "cua", "cho", "voi", "mot", "ngay", "dem", "mua", "nang",
        "gio", "troi", "buon", "dau", "khoc", "cuoi", "quen", "nhau", "ben", "noi",
        "nay", "sao", "roi", "thoi", "nhe", "qua", "lam", "cung", "chang", "chua",
        "biet", "muon", "tim", "giac", "mo", "tinh", "phai", "nuoc", "mat", "trai",
    )

    private val EnglishStopWords: Set<String> = setOf(
        "the", "and", "you", "that", "with", "for", "this", "have", "from", "your",
        "are", "was", "were", "been", "when", "what", "where", "just", "like", "know",
        "feel", "heart", "night", "never", "always", "time", "down", "away", "back",
        "here", "there", "into", "out", "all", "but", "not", "now", "will", "would",
        "could", "should", "they", "them", "their", "she", "her", "his", "him", "our",
        "don't", "can't", "won't", "i'm", "you're", "we're", "it's", "that's", "i'll",
        "i've", "we'll", "ain't", "gonna", "wanna", "love", "baby", "my", "me", "we",
        "to", "in", "on", "of", "is", "it", "be", "so", "do", "go", "up", "get", "got",
        "let", "see", "say", "take", "make", "come", "want", "need", "yeah", "tonight",
        "eyes", "mind", "life", "world", "wait", "stay", "leave", "dream", "hold", "keep",
        "tell", "give", "everything", "nothing", "something", "someone", "because",
        "before", "after", "again", "still", "around", "through", "without", "about",
        "only", "even", "every", "more", "than", "then", "some", "why", "how", "who",
    )

    private val OtherLatinStopWords: Set<String> = setOf(
        // French
        "je", "tu", "nous", "vous", "les", "des", "une", "est", "dans", "pour", "avec", "ce", "soir", "alors", "mais", "pas", "suis",
        // Spanish / Portuguese
        "que", "por", "para", "los", "las", "una", "pero", "como", "con", "sin", "más", "mas", "estoy", "eres", "não", "você", "com", "uma",
        // German
        "der", "die", "das", "und", "ich", "nicht", "ein", "eine", "mit", "ist", "bin", "wir",
        // Italian
        "che", "non", "per", "sono", "sei", "con", "una", "della", "anche",
    )

    private fun toNfc(text: String): String {
        for (ch in text) {
            if (ch.code >= 128) return Normalizer.normalize(text, Normalizer.Form.NFC)
        }
        return text
    }

    fun detectLanguage(
        lines: List<String>,
        title: String? = null,
        artist: String? = null,
    ): LyricsLanguage {
        val cleanedLines = lines
            .asSequence()
            .map { raw ->
                toNfc(raw).replace(TimestampTagRegex, " ")
                    .replace(MetaPrefixRegex, "")
                    .trim()
            }
            .filter { it.isNotEmpty() && !MetadataHeaderRegex.matches(it) }
            .toList()

        if (cleanedLines.isEmpty()) return LyricsLanguage.UNKNOWN
        val body = cleanedLines.joinToString("\n")

        var totalLetters = 0
        var kanaCount = 0
        var hangulCount = 0
        var hanCount = 0
        var cyrillicCount = 0
        var devanagariCount = 0
        var thaiCount = 0
        var arabicCount = 0
        var asciiLatinCount = 0
        var uniqueViCount = 0

        for (ch in body) {
            if (!ch.isLetter()) continue
            totalLetters++
            when {
                ch in '\u3040'..'\u309F' || ch in '\u30A0'..'\u30FF' -> kanaCount++
                ch in '\uAC00'..'\uD7A3' || ch in '\u1100'..'\u11FF' || ch in '\u3130'..'\u318F' -> hangulCount++
                ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF' -> hanCount++
                ch in '\u0400'..'\u04FF' -> cyrillicCount++
                ch in '\u0900'..'\u097F' -> devanagariCount++
                ch in '\u0E00'..'\u0E7F' -> thaiCount++
                ch in '\u0600'..'\u06FF' -> arabicCount++
                ch in 'A'..'Z' || ch in 'a'..'z' -> asciiLatinCount++
            }
            if (ch in UniquelyVietnameseChars) uniqueViCount++
        }

        if (totalLetters < 4) return LyricsLanguage.UNKNOWN

        val cjkTotal = kanaCount + hangulCount + hanCount
        // Protect clearly Vietnamese songs from a stray 3-4 character foreign ad-lib or residual tag
        if (uniqueViCount >= 5 && uniqueViCount * 2 > cjkTotal) {
            return LyricsLanguage.VIETNAMESE
        }

        // 1. Japanese: Hiragana/Katakana clearly distinguishes J-Pop (even with Kanji + English chorus)
        val kanaRatio = kanaCount.toDouble() / totalLetters
        val jpRatio = (kanaCount + hanCount).toDouble() / totalLetters
        if (kanaCount >= 12 ||
            (kanaCount >= 3 && kanaRatio >= 0.03) ||
            (kanaCount >= 1 && kanaCount + hanCount >= 4 && jpRatio >= 0.08)
        ) {
            return LyricsLanguage.JAPANESE
        }

        // 2. Korean: Hangul syllables/jamo (handles K-Pop with English hooks)
        val hangulRatio = hangulCount.toDouble() / totalLetters
        if (hangulCount >= 12 ||
            (hangulCount >= 3 && hangulRatio >= 0.04) ||
            (hangulCount >= 2 && hangulRatio >= 0.08)
        ) {
            return LyricsLanguage.KOREAN
        }

        // 3. Chinese: CJK Han characters without Kana (require sufficient ratio so stray LRC credits never trigger)
        val hanRatio = hanCount.toDouble() / totalLetters
        if (hanCount >= 16 ||
            (hanCount >= 4 && hanRatio >= 0.08) ||
            (hanCount >= 2 && hanRatio >= 0.15)
        ) {
            return LyricsLanguage.CHINESE
        }

        // 4. Other non-Latin scripts (Russian/Cyrillic, Hindi, Thai, Arabic)
        val otherScriptCount = cyrillicCount + devanagariCount + thaiCount + arabicCount
        if (otherScriptCount >= 4 && otherScriptCount.toDouble() / totalLetters >= 0.20) {
            return LyricsLanguage.OTHER
        }

        val tokens = body
            .lowercase()
            .replace('’', '\'')
            .split(WordSplitRegex)
            .filter { it.isNotEmpty() }

        // 5. Vietnamese with Quốc Ngữ diacritics
        if (uniqueViCount >= 2) {
            return LyricsLanguage.VIETNAMESE
        }
        if (uniqueViCount == 1 && tokens.any { it in VietnameseCommonWords }) {
            return LyricsLanguage.VIETNAMESE
        }
        val normTitle = title?.let(::toNfc)
        val normArtist = artist?.let(::toNfc)
        val titleHasVi = !normTitle.isNullOrBlank() && normTitle.any { it in UniquelyVietnameseChars }
        val artistHasVi = !normArtist.isNullOrBlank() && normArtist.any { it in UniquelyVietnameseChars }
        if (tokens.isNotEmpty()) {
            val englishHits = tokens.count { it in EnglishStopWords }
            val englishRatio = englishHits.toDouble() / tokens.size
            val otherLatinHits = tokens.count { it in OtherLatinStopWords }
            val otherLatinRatio = otherLatinHits.toDouble() / tokens.size
            val unaccentedViHits = tokens.count { it in UnaccentedVietnameseTokens }
            val unaccentedViRatio = unaccentedViHits.toDouble() / tokens.size

            // Unaccented Vietnamese karaoke lyrics
            if ((unaccentedViHits >= 4 && unaccentedViRatio >= 0.30 && englishRatio < 0.12 && otherLatinRatio < 0.12) ||
                ((titleHasVi || artistHasVi) && unaccentedViHits >= 2 && englishRatio < 0.15)
            ) {
                return LyricsLanguage.VIETNAMESE
            }

            // Reject predominantly French/Spanish/German/Italian/Portuguese lyrics before English check
            if (otherLatinHits >= 2 && otherLatinRatio >= 0.14 && otherLatinHits >= englishHits) {
                return LyricsLanguage.OTHER
            }

            // 6. English: predominantly ASCII Latin letters + English stopwords
            val asciiLatinRatio = asciiLatinCount.toDouble() / totalLetters
            if (asciiLatinRatio >= 0.72 && (englishHits >= 3 || (tokens.size <= 16 && englishHits >= 1 && englishRatio >= 0.12))) {
                return LyricsLanguage.ENGLISH
            }
        }

        return LyricsLanguage.OTHER
    }

    fun decide(
        smartLyricsEnabled: Boolean,
        lines: List<String>,
        configuredTargetLanguage: String?,
        title: String? = null,
        artist: String? = null,
    ): SmartLyricsDecision {
        val detected = detectLanguage(lines, title = title, artist = artist)
        val explicitTarget = configuredTargetLanguage?.trim()?.takeIf { it.isNotBlank() && it != "auto" }

        if (!smartLyricsEnabled) {
            return SmartLyricsDecision(
                detectedLanguage = detected,
                shouldAutoTranslate = false,
                targetLanguage = explicitTarget ?: "vi",
                sourceLanguageHint = "auto",
                shouldForceRomanization = false,
                shouldHideRomanization = false,
            )
        }

        return when (detected) {
            LyricsLanguage.VIETNAMESE -> SmartLyricsDecision(
                detectedLanguage = LyricsLanguage.VIETNAMESE,
                shouldAutoTranslate = false,
                targetLanguage = explicitTarget ?: "vi",
                sourceLanguageHint = "vi",
                shouldForceRomanization = false,
                shouldHideRomanization = true,
            )

            LyricsLanguage.ENGLISH -> SmartLyricsDecision(
                detectedLanguage = LyricsLanguage.ENGLISH,
                shouldAutoTranslate = true,
                // Never do English -> English no-op when Smart Lyrics is active; default to Vietnamese
                targetLanguage = if (explicitTarget == null || explicitTarget == "en") "vi" else explicitTarget,
                sourceLanguageHint = "en",
                shouldForceRomanization = false,
                shouldHideRomanization = false,
            )

            LyricsLanguage.JAPANESE -> SmartLyricsDecision(
                detectedLanguage = LyricsLanguage.JAPANESE,
                shouldAutoTranslate = true,
                targetLanguage = explicitTarget ?: "vi",
                sourceLanguageHint = "ja",
                shouldForceRomanization = true,
                shouldHideRomanization = false,
            )

            LyricsLanguage.KOREAN -> SmartLyricsDecision(
                detectedLanguage = LyricsLanguage.KOREAN,
                shouldAutoTranslate = true,
                targetLanguage = explicitTarget ?: "vi",
                sourceLanguageHint = "ko",
                shouldForceRomanization = true,
                shouldHideRomanization = false,
            )

            LyricsLanguage.CHINESE -> SmartLyricsDecision(
                detectedLanguage = LyricsLanguage.CHINESE,
                shouldAutoTranslate = true,
                targetLanguage = explicitTarget ?: "vi",
                sourceLanguageHint = "zh-CN",
                shouldForceRomanization = true,
                shouldHideRomanization = false,
            )

            LyricsLanguage.OTHER -> SmartLyricsDecision(
                detectedLanguage = LyricsLanguage.OTHER,
                shouldAutoTranslate = false,
                targetLanguage = explicitTarget ?: "vi",
                sourceLanguageHint = "auto",
                shouldForceRomanization = false,
                shouldHideRomanization = false,
            )

            LyricsLanguage.UNKNOWN -> SmartLyricsDecision(
                detectedLanguage = LyricsLanguage.UNKNOWN,
                shouldAutoTranslate = false,
                targetLanguage = explicitTarget ?: "vi",
                sourceLanguageHint = "auto",
                shouldForceRomanization = false,
                shouldHideRomanization = false,
            )
        }
    }
}
