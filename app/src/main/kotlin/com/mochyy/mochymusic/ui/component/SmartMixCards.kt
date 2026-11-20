package com.mochyy.mochymusic.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.smart.DaySlot
import com.mochyy.mochymusic.smart.SmartMixKind
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import com.mochyy.mochymusic.viewmodels.SmartMixCardItem

@Composable
fun smartMixTitle(kind: SmartMixKind, daySlot: DaySlot): String = when (kind) {
    SmartMixKind.TIME_OF_DAY -> when (daySlot) {
        DaySlot.MORNING -> stringResource(R.string.smart_mix_morning)
        DaySlot.AFTERNOON -> stringResource(R.string.smart_mix_afternoon)
        DaySlot.EVENING -> stringResource(R.string.smart_mix_evening)
        DaySlot.LATE_NIGHT -> stringResource(R.string.smart_mix_late_night)
    }
    SmartMixKind.ON_REPEAT -> stringResource(R.string.smart_mix_on_repeat)
    SmartMixKind.FORGOTTEN_FAVORITES -> stringResource(R.string.smart_mix_forgotten)
    SmartMixKind.CHILL_BPM -> stringResource(R.string.smart_mix_chill)
    SmartMixKind.UPBEAT_BPM -> stringResource(R.string.smart_mix_upbeat)
}

@Composable
fun smartMixTag(kind: SmartMixKind): String = when (kind) {
    SmartMixKind.TIME_OF_DAY -> stringResource(R.string.smart_mix_tag_time)
    SmartMixKind.ON_REPEAT -> stringResource(R.string.smart_mix_tag_repeat)
    SmartMixKind.FORGOTTEN_FAVORITES -> stringResource(R.string.smart_mix_tag_forgotten)
    SmartMixKind.CHILL_BPM -> stringResource(R.string.smart_mix_tag_chill)
    SmartMixKind.UPBEAT_BPM -> stringResource(R.string.smart_mix_tag_upbeat)
}

private fun smartMixGradient(kind: SmartMixKind, daySlot: DaySlot): List<Color> = when (kind) {
    SmartMixKind.TIME_OF_DAY -> when (daySlot) {
        DaySlot.MORNING -> listOf(Color(0xFFD97736), Color(0xFF7B3F1D))
        DaySlot.AFTERNOON -> listOf(Color(0xFF2F7FB8), Color(0xFF1A4366))
        DaySlot.EVENING -> listOf(Color(0xFF7A459E), Color(0xFF391D52))
        DaySlot.LATE_NIGHT -> listOf(Color(0xFF2A3B6E), Color(0xFF141B33))
    }
    SmartMixKind.ON_REPEAT -> listOf(Color(0xFFBF3B5E), Color(0xFF5A1A2D))
    SmartMixKind.FORGOTTEN_FAVORITES -> listOf(Color(0xFF287C6F), Color(0xFF133E38))
    SmartMixKind.CHILL_BPM -> listOf(Color(0xFF2D6C8A), Color(0xFF143445))
    SmartMixKind.UPBEAT_BPM -> listOf(Color(0xFFC94B38), Color(0xFF5E2028))
}

private fun smartMixIcon(kind: SmartMixKind, daySlot: DaySlot): ImageVector = when (kind) {
    SmartMixKind.TIME_OF_DAY -> when (daySlot) {
        DaySlot.MORNING -> Icons.Rounded.WbSunny
        DaySlot.AFTERNOON -> Icons.Rounded.WbSunny
        DaySlot.EVENING -> Icons.Rounded.WbTwilight
        DaySlot.LATE_NIGHT -> Icons.Rounded.NightsStay
    }
    SmartMixKind.ON_REPEAT -> Icons.Rounded.Repeat
    SmartMixKind.FORGOTTEN_FAVORITES -> Icons.Rounded.History
    SmartMixKind.CHILL_BPM -> Icons.Rounded.Spa
    SmartMixKind.UPBEAT_BPM -> Icons.Rounded.Bolt
}

/**
 * Compact 1-tap Smart Mix card for HomeScreen:
 * - Tapping the card opens the full tracklist in SectionDetailScreen.
 * - Tapping the Play pill launches the mix immediately with 1 tap.
 */
@Composable
fun SmartMixCard(
    mix: SmartMixCardItem,
    onOpen: () -> Unit,
    onPlayNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = smartMixTitle(mix.kind, mix.daySlot)
    val tag = smartMixTag(mix.kind)
    val gradient = remember(mix.kind, mix.daySlot) { smartMixGradient(mix.kind, mix.daySlot) }
    val icon = remember(mix.kind, mix.daySlot) { smartMixIcon(mix.kind, mix.daySlot) }
    val covers = remember(mix.songs) {
        mix.songs.mapNotNull { it.thumbnailUrl?.takeIf(String::isNotBlank) }.distinct().take(4)
    }
    val subtitle = if (mix.avgBpm != null && (mix.kind == SmartMixKind.CHILL_BPM || mix.kind == SmartMixKind.UPBEAT_BPM)) {
        stringResource(R.string.smart_mix_subtitle_bpm, mix.songs.size, mix.avgBpm)
    } else {
        stringResource(R.string.smart_mix_subtitle_count, mix.songs.size)
    }

    val cardInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val playInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }

    Box(
        modifier = modifier
            .width(196.dp)
            .height(142.dp)
            .pressBounce(cardInteraction, pressedScale = 0.96f)
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(gradient))
            .clickable(
                interactionSource = cardInteraction,
                indication = HomeCardHighlight,
                onClick = onOpen,
            ),
    ) {
        // Subtle background cover mosaic on the right half
        if (covers.isNotEmpty()) {
            AsyncImage(
                model = covers.first(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.24f,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                gradient.first().copy(alpha = 0.88f),
                                gradient.last().copy(alpha = 0.72f),
                            ),
                        ),
                    ),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top row: mood pill + mini covers preview
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.28f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.92f),
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = tag,
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.92f),
                        maxLines = 1,
                    )
                }

                // 1-tap Play button
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .pressBounce(playInteraction, pressedScale = 0.86f)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.92f))
                        .clickable(
                            interactionSource = playInteraction,
                            indication = RemoteFocus,
                            onClick = onPlayNow,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.play),
                        tint = Color.Black,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            // Bottom row: title + track count / BPM + mini cover stack
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        lineHeight = 19.sp,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = subtitle,
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (covers.size >= 2) {
                    Spacer(Modifier.width(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                        covers.take(2).forEach { url ->
                            AsyncImage(
                                model = url,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                        }
                    }
                }
            }
        }
    }
}
