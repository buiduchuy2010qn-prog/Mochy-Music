package com.mochyy.mochymusic.ui.player.studio

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.constants.PlayerMenuSlotsKey
import com.mochyy.mochymusic.constants.PlayerSideButtonKey
import com.mochyy.mochymusic.utils.rememberPreference

/**
 * Everything the player's round buttons can do: the button beside the chevron and the four in the
 * menu the chevron opens are each set to one of these in the editor ("Edit menu").
 */
enum class PlayerMenuAction(val label: String, @param:DrawableRes val icon: Int) {
    LIKE("Yêu thích", R.drawable.ic_untitled_heart_unfill),
    OTHER_MENU("Menu khác", R.drawable.more_horiz),
    SLEEP_TIMER("Hẹn giờ ngủ", R.drawable.sleep_timer),
    SEE_VIDEO("Xem video", R.drawable.slow_motion_video),
    REPEAT("Lặp lại", R.drawable.repeat),
    SHUFFLE("Phát ngẫu nhiên", R.drawable.shuffle),
    LYRICS("Lời bài hát", R.drawable.lyrics),
    QUEUE("Hàng đợi", R.drawable.queue_music),
    SPEED("Tốc độ phát", R.drawable.speed),
    // the equalizer page, the player stepping down out of its way
    EQUALIZER("Bộ chỉnh âm", R.drawable.equalizer),
    OUTPUT("Thiết bị phát", R.drawable.cast),
    // the player turned sideways around the song's youtube video
    FULL_VIDEO("Toàn màn hình", R.drawable.fullscreen),

    // the ones below are worked by PlayerMenuExtras
    KEEP_AWAKE("Giữ sáng màn hình", R.drawable.lock_open),
    TRANSLATE_LYRICS("Dịch lời bài hát", R.drawable.translate),
    DOWNLOAD("Tải xuống", R.drawable.download),
    ADD_TO_PLAYLIST("Thêm vào DS phát", R.drawable.playlist_add);

    companion object {
        val DefaultSlots = listOf(OTHER_MENU, SLEEP_TIMER, SEE_VIDEO, REPEAT)
        val DefaultSide = LIKE

        fun parseSlots(stored: String): List<PlayerMenuAction> {
            val parsed = stored.split(',').mapNotNull { name -> entries.firstOrNull { it.name == name } }
            return if (parsed.size == DefaultSlots.size) parsed else DefaultSlots
        }

        fun parseSide(stored: String): PlayerMenuAction = entries.firstOrNull { it.name == stored } ?: DefaultSide
    }
}

/** the four chevron-menu actions and the side button, as set in the editor, with their setters */
class PlayerMenuLayout(
    val slots: List<PlayerMenuAction>,
    val side: PlayerMenuAction,
    private val writeSlots: (String) -> Unit,
    private val writeSide: (String) -> Unit,
) {
    /**
     * puts [action] at [slot] (0..3, or [SideSlot] for the side button). an action can only be in
     * one place: if it was already somewhere else, whatever was at [slot] moves there instead
     */
    fun assign(slot: Int, action: PlayerMenuAction) {
        val all = slots + side
        val previous = all[slot]
        if (previous == action) return
        val elsewhere = all.indexOf(action)
        val next = all.toMutableList()
        next[slot] = action
        if (elsewhere >= 0) next[elsewhere] = previous
        writeSlots(next.take(slots.size).joinToString(",") { it.name })
        writeSide(next.last().name)
    }

    fun actionAt(slot: Int): PlayerMenuAction = if (slot == SideSlot) side else slots[slot]

    companion object {
        const val SideSlot = 4
    }
}

@Composable
fun rememberPlayerMenuLayout(): PlayerMenuLayout {
    val (slotsStored, onSlots) = rememberPreference(PlayerMenuSlotsKey, defaultValue = "")
    val (sideStored, onSide) = rememberPreference(PlayerSideButtonKey, defaultValue = "")
    val slots = remember(slotsStored) { PlayerMenuAction.parseSlots(slotsStored) }
    val side = remember(sideStored) { PlayerMenuAction.parseSide(sideStored) }
    return remember(slots, side, onSlots, onSide) { PlayerMenuLayout(slots, side, onSlots, onSide) }
}
