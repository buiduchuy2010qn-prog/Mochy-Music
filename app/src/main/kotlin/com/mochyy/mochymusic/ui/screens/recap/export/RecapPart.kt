// RecapPart.kt
//
// Individual exportable moments of Mochy Re'cap and soundtrack selection logic.
// Kept pure-Kotlin (no Android/Compose dependencies) so JVM unit tests run directly.

package com.mochyy.mochymusic.ui.screens.recap.export

enum class RecapPart(val id: String) {
    JOURNEY("journey"),
    MONTH("month"),
    WEEKDAY("weekday"),
    STREAK("streak"),
    TOP_ARTIST("top_artist"),
    TOP_SONG("top_song"),
    PERSONA("persona"),
    RECEIPT("receipt"),
    SUMMARY_CARD("summary_card"),
}

enum class RecapExportTab {
    VIDEO,
    PARTS,
}

fun availableRecapParts(hasHeroArtist: Boolean, hasHeroSong: Boolean): List<RecapPart> = buildList {
    add(RecapPart.JOURNEY)
    add(RecapPart.MONTH)
    add(RecapPart.WEEKDAY)
    add(RecapPart.STREAK)
    if (hasHeroArtist) add(RecapPart.TOP_ARTIST)
    if (hasHeroSong) add(RecapPart.TOP_SONG)
    add(RecapPart.PERSONA)
    add(RecapPart.RECEIPT)
    add(RecapPart.SUMMARY_CARD)
}

/**
 * Picks the single best soundtrack song ID for an exported video:
 * - For TOP_ARTIST single-part video: the top artist's top song (matching Mochy's live story), falling back to #1 song.
 * - For Full Recap or any other part: the #1 song, falling back to top artist's top song.
 */
fun selectSoundtrackSongId(
    heroSongId: String?,
    heroArtistTopSongId: String?,
    part: RecapPart?,
): String? = when (part) {
    RecapPart.TOP_ARTIST -> heroArtistTopSongId?.takeIf { it.isNotBlank() } ?: heroSongId?.takeIf { it.isNotBlank() }
    else -> heroSongId?.takeIf { it.isNotBlank() } ?: heroArtistTopSongId?.takeIf { it.isNotBlank() }
}
