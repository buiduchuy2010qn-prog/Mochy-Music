// PlayerCoverStyles.kt

package com.mochyy.mochymusic.ui.player.customize

import androidx.annotation.DrawableRes
import androidx.datastore.preferences.core.Preferences
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.constants.CanvasThumbnailAnimationKey
import com.mochyy.mochymusic.constants.DiscNameKey
import com.mochyy.mochymusic.constants.DiscRealisticModeKey
import com.mochyy.mochymusic.constants.DiscRotatingAnimationKey
import com.mochyy.mochymusic.constants.PlayVideoBackgroundKey
import com.mochyy.mochymusic.constants.PlayerCoverStyle
import com.mochyy.mochymusic.constants.YtVideoBackgroundLyricsSyncKey

val PlayerCoverStyle.isDisc: Boolean
    get() = when (this) {
        PlayerCoverStyle.EDGE_TO_EDGE, PlayerCoverStyle.SQUARED -> false
        else -> true
    }

val PlayerCoverStyle.usesBoxedStage: Boolean
    get() = when (this) {
        PlayerCoverStyle.EDGE_TO_EDGE,
        PlayerCoverStyle.DISC_BIG_FULL,
        PlayerCoverStyle.DISC_BIG_LABEL,
        PlayerCoverStyle.TURNTABLE,
        -> false
        else -> true
    }

val PlayerCoverStyle.isBigDisc: Boolean
    get() = this == PlayerCoverStyle.DISC_BIG_FULL || this == PlayerCoverStyle.DISC_BIG_LABEL

val PlayerCoverStyle.hasTonearm: Boolean
    get() = this == PlayerCoverStyle.DISC_BIG_LABEL

val PlayerCoverStyle.displayName: String
    get() = when (this) {
        PlayerCoverStyle.EDGE_TO_EDGE -> "Tràn viền toàn màn hình"
        PlayerCoverStyle.SQUARED -> "Logo vuông (Squared)"
        PlayerCoverStyle.DISC_SMALL_FULL -> "Đĩa nhạc nhỏ - Tràn viền"
        PlayerCoverStyle.DISC_SMALL_LABEL -> "Đĩa nhạc nhỏ - Nhãn tâm"
        PlayerCoverStyle.DISC_BIG_FULL -> "Đĩa nhạc lớn - Tràn viền"
        PlayerCoverStyle.DISC_BIG_LABEL -> "Đĩa nhạc lớn - Có kim đĩa"
        PlayerCoverStyle.DISC_ALBUM -> "Đĩa nhạc kèm bìa Album"
        PlayerCoverStyle.TURNTABLE -> "Mâm đĩa than (Turntable)"
    }

sealed interface CoverOption {
    val title: String
    val description: String
    @get:DrawableRes val icon: Int

    data class Switch(
        override val title: String,
        override val description: String,
        @param:DrawableRes override val icon: Int,
        val key: Preferences.Key<Boolean>,
        val default: Boolean,
        val inverted: Boolean = false,
        val parent: Switch? = null,
    ) : CoverOption

    data class Text(
        override val title: String,
        override val description: String,
        @param:DrawableRes override val icon: Int,
        val key: Preferences.Key<String>,
        val placeholder: String,
    ) : CoverOption
}

private val AnimatedCanvasOption = CoverOption.Switch(
    title = "Ảnh bìa động (Canvas)",
    description = "Ảnh bìa chuyển động (không lấy từ YouTube)",
    icon = R.drawable.sparks,
    key = CanvasThumbnailAnimationKey,
    default = false,
)

private val YtVideoOption = CoverOption.Switch(
    title = "Nền video YouTube",
    description = "Phát video YouTube trên nền ảnh bìa",
    icon = R.drawable.slow_motion_video,
    key = PlayVideoBackgroundKey,
    default = false,
)

private val TimestampMatchingOption = CoverOption.Switch(
    title = "Khớp mốc thời gian",
    description = "Dựa trên phụ đề",
    icon = R.drawable.lyrics,
    key = YtVideoBackgroundLyricsSyncKey,
    default = true,
    parent = YtVideoOption,
)

private val RotatingAnimationOption = CoverOption.Switch(
    title = "Hiệu ứng xoay",
    description = "Dành cho đĩa nhạc",
    icon = R.drawable.refresh,
    key = DiscRotatingAnimationKey,
    default = true,
)

private val RealisticModeOption = CoverOption.Switch(
    title = "Chế độ chân thực",
    description = "Dành cho đĩa nhạc",
    icon = R.drawable.palette,
    key = DiscRealisticModeKey,
    default = true,
)

private val DiscNameOption = CoverOption.Text(
    title = "Tên trên đĩa",
    description = "In trên mặt đĩa xoay",
    icon = R.drawable.edit,
    key = DiscNameKey,
    placeholder = "Để trống nếu muốn ẩn",
)

private val DiscOptions = listOf(RotatingAnimationOption, RealisticModeOption, DiscNameOption)

val PlayerCoverStyle.options: List<CoverOption>
    get() = when (this) {
        PlayerCoverStyle.SQUARED -> listOf(AnimatedCanvasOption)
        PlayerCoverStyle.EDGE_TO_EDGE -> listOf(
            YtVideoOption,
            TimestampMatchingOption,
            AnimatedCanvasOption,
        )
        else -> DiscOptions
    }

